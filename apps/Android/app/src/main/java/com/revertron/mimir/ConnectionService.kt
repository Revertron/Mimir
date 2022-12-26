package com.revertron.mimir

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.preference.PreferenceManager
import com.revertron.mimir.net.*
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex

class ConnectionService : Service(), EventListener, InfoProvider {

    companion object {
        const val TAG = "ConnectionService"
    }

    var mimirServer: MimirServer? = null

    override fun onBind(intent: Intent): IBinder? {
        return null
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
                Log.i(TAG, "Message to $keyString")
                if (pubkey != null && message != null) {
                    val id = storage.addMessage(keyString, false, false, System.currentTimeMillis(), 0, message)
                    Thread{
                        mimirServer?.sendText(pubkey, id, message)
                    }.start()
                }
            }
            "resend" -> {
                val id = intent.getLongExtra("id", 0)
                val pubkey = intent.getByteArrayExtra("pubkey")
                val message = intent.getStringExtra("message")
                if (pubkey != null && message != null) {
                    Thread{
                        mimirServer?.sendText(pubkey, id, message)
                    }.start()
                }
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

    override fun onClientConnected(from: ByteArray, address: String, clientId: Int) {
        val keyString = Hex.toHexString(from)
        val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
        val ttl = preferences.getInt(IP_CACHE_TTL, IP_CACHE_DEFAULT_TTL)
        val expiration = getUtcTime() + ttl
        val storage = (application as App).storage
        storage.saveIp(keyString, address, CONNECTION_PORT, clientId, 0, expiration)
    }

    override fun onMessageReceived(from: ByteArray, address: String, id: Long, message: String) {
        val keyString = Hex.toHexString(from)
        val storage = (application as App).storage
        storage.addMessage(keyString, true, true, System.currentTimeMillis(), 0, message)
    }

    override fun onMessageDelivered(to: ByteArray, id: Long, delivered: Boolean) {
        val keyString = Hex.toHexString(to)
        (application as App).storage.setMessageDelivered(keyString, id, delivered)
    }

    override fun getMyInfo(ifUpdatedSince: Long): InfoResponse? {
        //TODO refactor for multi account
        val info = (application as App).storage.getAccountInfo(1, ifUpdatedSince) ?: return null
        var avatar: ByteArray? = null
        if (info.avatar.isNotEmpty()) {
            val file = getFileStreamPath(info.avatar)
            avatar = file.readBytes()
        }
        return InfoResponse(info.updated, info.name, info.info, avatar)
    }

    override fun getContactUpdateTime(pubkey: String): Long {
        return (application as App).storage.getContactUpdateTime(pubkey)
    }

    override fun updateContactInfo(pubkey: String, info: InfoResponse) {
        val storage = (application as App).storage
        val id = storage.getContactId(pubkey)
        Log.i(TAG, "Renaming contact $id to ${info.nickname}")
        storage.renameContact(id, info.nickname, false)
    }
}