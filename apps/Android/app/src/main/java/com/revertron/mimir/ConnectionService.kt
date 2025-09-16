package com.revertron.mimir

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.os.postDelayed
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.revertron.mimir.NotificationManager.Companion.INCOMING_CALL_NOTIFICATION_ID
import com.revertron.mimir.NotificationManager.Companion.ONGOING_CALL_NOTIFICATION_ID
import com.revertron.mimir.NotificationManager.Companion.showCallNotification
import com.revertron.mimir.net.CallStatus
import com.revertron.mimir.net.EventListener
import com.revertron.mimir.net.InfoProvider
import com.revertron.mimir.net.InfoResponse
import com.revertron.mimir.net.MimirServer
import com.revertron.mimir.net.PeerStatus
import com.revertron.mimir.storage.PeerProvider
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class ConnectionService : Service(), EventListener, InfoProvider {

    companion object {
        const val TAG = "ConnectionService"
    }

    var mimirServer: MimirServer? = null
    val peerStatuses = HashMap<String, PeerStatus>()
    var broadcastPeerStatuses = true
    var updateAfter = 0L
    lateinit var updaterThread: HandlerThread
    lateinit var handler: Handler

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        updaterThread = HandlerThread("UpdateThread").apply { start() }
        handler = Handler(updaterThread.looper)
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
                Log.i(TAG, "Starting service...")
                val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
                if (preferences.getBoolean("enabled", true)) { //TODO change to false
                    if (mimirServer == null) {
                        Log.i(TAG, "Starting MimirServer...")
                        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mimir:server")
                        var accountInfo = storage.getAccountInfo(1, 0L) // TODO use name
                        if (accountInfo == null) {
                            accountInfo = storage.generateNewAccount()
                        }
                        val pubkey = (accountInfo.keyPair.public as Ed25519PublicKeyParameters).encoded
                        val pubkeyHex = Hex.toHexString(pubkey)
                        Log.i(TAG, "Got account ${accountInfo.name} with pubkey $pubkeyHex")
                        val peerProvider = PeerProvider(this)
                        mimirServer = MimirServer(storage, peerProvider, accountInfo.clientId, accountInfo.keyPair, this, this, wakeLock)
                        mimirServer?.start()
                        val n = createServiceNotification(this, State.Offline)
                        startForeground(1, n)
                    }

                    return START_STICKY
                }
            }
            "refresh_peer" -> {
                mimirServer?.jumpPeer()
            }
            "connect" -> {
                val pubkey = intent.getByteArrayExtra("pubkey")
                pubkey?.let { mimirServer?.connectContact(it) }
            }
            "call" -> {
                val pubkey = intent.getByteArrayExtra("pubkey")
                pubkey?.let {
                    mimirServer?.call(it)
                    mimirServer?.connectContact(it)
                }
            }
            "call_answer" -> {
                Log.i(TAG, "Answering call")
                val pubkey = intent.getByteArrayExtra("pubkey")

                NotificationManagerCompat.from(this).cancel(INCOMING_CALL_NOTIFICATION_ID)
                if (pubkey != null) {
                    showCallNotification(this, applicationContext, true, pubkey)
                } else {
                    val contact = mimirServer?.getCallingContact()
                    if (contact != null) {
                        showCallNotification(this, applicationContext, true, contact)
                    }
                }
                mimirServer?.callAnswer()
            }
            "call_decline" -> {
                NotificationManagerCompat.from(this).cancel(INCOMING_CALL_NOTIFICATION_ID)
                NotificationManagerCompat.from(this).cancel(ONGOING_CALL_NOTIFICATION_ID)
                Log.i(TAG, "Declining call")
                onCallStatusChanged(CallStatus.Hangup, null)
                mimirServer?.callDecline()
            }
            "call_hangup" -> {
                Log.i(TAG, "Hanging-up call")
                NotificationManagerCompat.from(this).cancel(ONGOING_CALL_NOTIFICATION_ID)
                mimirServer?.callHangup()
            }
            "incoming_call" -> {
                val pubkey = intent.getByteArrayExtra("pubkey")
                pubkey?.let { showCallNotification(this, applicationContext, false, it) }
            }
            "call_mute" -> {
                val mute = intent.getBooleanExtra("mute", false)
                mimirServer?.callMute(mute)
            }
            "send" -> {
                val pubkey = intent.getByteArrayExtra("pubkey")
                val keyString = Hex.toHexString(pubkey)
                val message = intent.getStringExtra("message")
                val replyTo = intent.getLongExtra("replyTo", 0L)
                val type = intent.getIntExtra("type", 0)
                if (pubkey != null && message != null) {
                    val id = storage.addMessage(pubkey, 0, replyTo, false, false, getUtcTimeMs(), 0, type, message.toByteArray())
                    Log.i(TAG, "Message $id to $keyString")
                    Thread{
                        mimirServer?.sendMessages()
                    }.start()
                }
            }
            "online" -> {
                mimirServer?.setNetworkOnline(true)
                mimirServer?.reconnectPeers()
                Log.i(TAG, "Resending unsent messages")
                mimirServer?.sendMessages()
                if (updateAfter == 0L) {
                    handler.postDelayed(1000) {
                        updateTick()
                    }
                }
            }
            "offline" -> {
                mimirServer?.setNetworkOnline(false)
            }
            "peer_statuses" -> {
                Log.i(TAG, "Have statuses of $peerStatuses")
                val from = intent.getByteArrayExtra("contact")
                val contact = Hex.toHexString(from)
                if (contact != null && peerStatuses.contains(contact)) {
                    val status = peerStatuses.get(contact)
                    Log.i(TAG, "Sending peer status: $status")
                    val intent = Intent("ACTION_PEER_STATUS").apply {
                        putExtra("contact", contact)
                        putExtra("status", status)
                    }
                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                }
            }
            "update_dismissed" -> {
                val delay = 3600 * 1000L
                updateAfter = System.currentTimeMillis() + delay
                handler.postDelayed(delay) {
                    updateTick()
                }
            }
            "check_updates" -> {
                updateAfter = System.currentTimeMillis()
                handler.postDelayed(100) {
                    updateTick(true)
                }
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        mimirServer?.stopServer()
        updaterThread.quitSafely()
        super.onDestroy()
    }

    override fun onServerStateChanged(online: Boolean) {
        val state = when(online) {
            true -> State.Online
            false -> State.Offline
        }
        val n = createServiceNotification(this, state)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, n)
    }

    override fun onTrackerPing(online: Boolean) {
        if (online) {
            val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
            preferences.edit {
                putLong("trackerPingTime", getUtcTime())
                apply()
            }
        }
    }

    override fun onClientConnected(from: ByteArray, address: String, clientId: Int) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
        val ttl = preferences.getInt(IP_CACHE_TTL, IP_CACHE_DEFAULT_TTL)
        val expiration = getUtcTime() + ttl
        val storage = (application as App).storage
        storage.saveIp(from, address, 0, clientId, 0, expiration)
    }

    override fun onMessageReceived(from: ByteArray, guid: Long, replyTo: Long, sendTime: Long, editTime: Long, type: Int, message: ByteArray) {
        val storage = (application as App).storage
        if (type == 1) {
            //TODO fix multiple vulnerabilities
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
            storage.addMessage(from, guid, replyTo, true, true, sendTime, editTime, type, message)
        }
    }

    override fun onIncomingCall(from: ByteArray, inCall: Boolean): Boolean {
        showCallNotification(this, applicationContext, inCall, from)
        return true
    }

    override fun onCallStatusChanged(status: CallStatus, from: ByteArray?) {
        Log.i(TAG, "onCallStatusChanged: $status")
        if (status == CallStatus.InCall) {
            val intent = Intent("ACTION_IN_CALL_START")
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            showCallNotification(this, applicationContext, true, from!!)
        }
        if (status == CallStatus.Hangup) {
            NotificationManagerCompat.from(this).cancel(INCOMING_CALL_NOTIFICATION_ID)
            NotificationManagerCompat.from(this).cancel(ONGOING_CALL_NOTIFICATION_ID)
            val intent = Intent("ACTION_FINISH_CALL")
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
    }

    override fun onMessageDelivered(to: ByteArray, guid: Long, delivered: Boolean) {
        (application as App).storage.setMessageDelivered(to, guid, delivered)
    }

    override fun onPeerStatusChanged(from: ByteArray, status: PeerStatus) {
        val contact = Hex.toHexString(from)
        peerStatuses.put(contact, status)
        if (broadcastPeerStatuses) {
            val intent = Intent("ACTION_PEER_STATUS").apply {
                putExtra("contact", contact)
                putExtra("status", status)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
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

    override fun getContactUpdateTime(pubkey: ByteArray): Long {
        return (application as App).storage.getContactUpdateTime(pubkey)
    }

    override fun updateContactInfo(pubkey: ByteArray, info: InfoResponse) {
        val storage = (application as App).storage
        val id = storage.getContactId(pubkey)
        Log.i(TAG, "Renaming contact $id to ${info.nickname}")
        storage.renameContact(id, info.nickname, false)
    }

    override fun getFilesDirectory(): String {
        return filesDir.absolutePath + "/files"
    }

    private fun updateTick(forced: Boolean = false) {
        if (System.currentTimeMillis() > updateAfter) {

            val windowContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
                val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
                createWindowContext(display, TYPE_APPLICATION_OVERLAY, null)
            } else {
                this@ConnectionService
            }

            val delay = if (checkUpdates(windowContext, forced)) {
                3600 * 1000L
            } else {
                600 * 1000L
            }
            updateAfter = System.currentTimeMillis() + delay
            handler.postDelayed(delay) {
                updateTick()
            }
        }
    }
}

fun connect(context: Context, pubkey: ByteArray) {
    val intent = Intent(context, ConnectionService::class.java)
    intent.putExtra("command", "connect")
    intent.putExtra("pubkey", pubkey)
    context.startService(intent)
}

fun fetchStatus(context: Context, pubkey: ByteArray) {
    val intent = Intent(context, ConnectionService::class.java)
    intent.putExtra("command", "peer_statuses")
    intent.putExtra("contact", pubkey)
    context.startService(intent)
}