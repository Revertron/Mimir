package com.revertron.mimir

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.revertron.mimir.net.*
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File

class ConnectionService : Service(), EventListener, InfoProvider {

    companion object {
        const val TAG = "ConnectionService"
        private var instance: ConnectionService? = null
        
        @JvmStatic
        fun isConnected(): Boolean {
            return instance?.mimirServer?.isOnline ?: false
        }
    }

    private var mimirServer: MimirServer? = null

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return START_NOT_STICKY
        }

        val command = intent.getStringExtra("command")
        Log.i(TAG, "Starting service with command $command")
        val storage = (application as App).storage
        when (command) {
            "start" -> {
                Log.i(TAG, "Starting server...")
                val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
                if (preferences.getBoolean("enabled", true)) { //TODO change to false
                    if (mimirServer == null) {
                        var accountInfo = storage.getAccountInfo(1, 0L) // TODO use name
                        if (accountInfo == null) {
                            accountInfo = storage.generateNewAccount()
                        }
                        val pubkey = (accountInfo.keyPair.public as Ed25519PublicKeyParameters).encoded
                        val pubkeyHex = Hex.toHexString(pubkey)
                        Log.i(TAG, "Got account ${accountInfo.name} with pubkey $pubkeyHex")
                        mimirServer = MimirServer(storage, accountInfo.clientId, accountInfo.keyPair, this, this, CONNECTION_PORT.toInt()) //TODO make changing port
                        mimirServer!!.start()
                        val n = createServiceNotification(this, State.Enabled)
                        startForeground(1, n)
                    }
                    return START_STICKY
                }
            }
            "send" -> {
                val pubkey = intent.getByteArrayExtra("pubkey")
                val keyString = Hex.toHexString(pubkey)
                val message = intent.getStringExtra("message")
                val replyTo = intent.getLongExtra("replyTo", 0L)
                val type = intent.getIntExtra("type", 0)
                Log.i(TAG, "Replying to $replyTo")
                if (pubkey != null && message != null) {
                    try {
                        val messageData = when (type) {
                            0 -> { // Текстовое сообщение
                                val json = JSONObject(message)
                                val text = json.getString("text")
                                Log.d(TAG, "Processing text message: $text")
                                text.toByteArray(Charsets.UTF_8)
                            }
                            1 -> { // Сообщение с вложением
                                Log.d(TAG, "Processing attachment message: $message")
                                message.toByteArray(Charsets.UTF_8)
                            }
                            else -> {
                                Log.e(TAG, "Unknown message type: $type")
                                return START_NOT_STICKY
                            }
                        }
                        
                        val id = storage.addMessage(pubkey, 0, replyTo, false, false, getUtcTimeMs(), 0, type, messageData)
                        Log.i(TAG, "Message $id to $keyString")
                        Thread {
                            mimirServer?.sendMessages()
                        }.start()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing message", e)
                    }
                }
            }
            "resend" -> {
                val id = intent.getLongExtra("id", 0)
                val pubkey = intent.getByteArrayExtra("pubkey")
                Log.i(TAG, "Resending message $id")
                if (pubkey != null) {
                    Thread{
                        mimirServer?.sendMessages()
                    }.start()
                }
            }
            "resend_all" -> {
                Thread {
                    Log.i(TAG, "Resending unsent messages")
                    mimirServer?.sendMessages()
                }.start()
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onServerStateChanged(online: Boolean) {
        val state = when(online) {
            true -> State.Enabled
            false -> State.Disabled
        }
        val n = createServiceNotification(this, state)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, n)
    }

    override fun onTrackerPing(online: Boolean) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
        preferences.edit {
            putLong("trackerPingTime", getUtcTime())
            apply()
        }
    }

    override fun onClientConnected(from: ByteArray, address: String, clientId: Int) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
        val ttl = preferences.getInt(IP_CACHE_TTL, IP_CACHE_DEFAULT_TTL)
        val expiration = getUtcTime() + ttl
        val storage = (application as App).storage
        storage.saveIp(from, address, CONNECTION_PORT, clientId, 0, expiration)
    }

    override fun onMessageReceived(from: ByteArray, guid: Long, replyTo: Long, sendTime: Long, editTime: Long, type: Int, message: ByteArray) {
        val storage = (application as App).storage
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val voiceMessagesEnabled = preferences.getBoolean(VOICE_MESSAGES_ENABLED, true)
        
        if (type == 1) {
            // Проверяем, включены ли голосовые сообщения
            if (!voiceMessagesEnabled) {
                Log.i(TAG, "Voice message rejected - voice messages disabled")
                return
            }
            
            // Обрабатываем голосовое сообщение
            val bais = ByteArrayInputStream(message)
            val dis = DataInputStream(bais)
            val metaSize = dis.readInt()
            val meta = ByteArray(metaSize)
            dis.read(meta)
            val json = JSONObject(String(meta))
            val rest = dis.available()
            val buf = ByteArray(rest)
            dis.read(buf)
            saveFileForMessage(this, json.getString("name"), buf)
            storage.addMessage(from, guid, replyTo, true, true, sendTime, editTime, type, meta)
        } else {
            // Обрабатываем обычное сообщение
            storage.addMessage(from, guid, replyTo, true, true, sendTime, editTime, type, message)
        }
    }

    override fun onMessageDelivered(to: ByteArray, guid: Long, delivered: Boolean) {
        (application as App).storage.setMessageDelivered(to, guid, delivered)
    }

    override fun getMyInfo(ifUpdatedSince: Long): InfoResponse? {
        //TODO refactor for multi account
        val info = (application as App).storage.getAccountInfo(1, ifUpdatedSince) ?: return null
        var avatar: ByteArray? = null
        if (info.avatar.isNotEmpty()) {
            val file = getFileStreamPath(info.avatar)
            avatar = file.readBytes()
        }
        
        // Добавляем настройку голосовых сообщений в info
        val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
        val voiceMessagesEnabled = preferences.getBoolean(VOICE_MESSAGES_ENABLED, true)
        val infoJson = JSONObject(info.info.ifEmpty { "{}" })
        infoJson.put("voiceMessagesEnabled", voiceMessagesEnabled)
        
        // Добавляем время последнего обновления настроек
        infoJson.put("settingsUpdated", System.currentTimeMillis())
        
        return InfoResponse(info.updated, info.name, infoJson.toString(), avatar)
    }

    override fun getContactInfo(pubkey: ByteArray): com.revertron.mimir.net.InfoResponse? {
        val storage = (application as App).storage
        val storageResponse = storage.getContactInfo(pubkey)
        val contactId = storage.getContactId(pubkey)
        val contactName = storage.getContactName(contactId)
        val updateTime = storage.getContactUpdateTime(pubkey)
        
        return storageResponse?.let {
            com.revertron.mimir.net.InfoResponse(
                time = updateTime,
                nickname = contactName,
                info = it.info,
                avatar = null // TODO: Implement avatar support if needed
            )
        }
    }

    override fun getContactUpdateTime(pubkey: ByteArray): Long {
        return (application as App).storage.getContactUpdateTime(pubkey)
    }

    override fun updateContactInfo(pubkey: ByteArray, info: InfoResponse) {
        val storage = (application as App).storage
        val id = storage.getContactId(pubkey)
        Log.i(TAG, "Renaming contact $id to ${info.nickname}")
        storage.renameContact(id, info.nickname, false)
        
        // Обновляем настройку голосовых сообщений контакта
        try {
            val infoJson = JSONObject(info.info)
            val voiceMessagesEnabled = infoJson.optBoolean("voiceMessagesEnabled", true)
            storage.updateContactVoiceMessagesEnabled(id, voiceMessagesEnabled)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing contact info", e)
        }
    }

    override fun getFilesDirectory(): String {
        return filesDir.absolutePath + "/files"
    }
}
