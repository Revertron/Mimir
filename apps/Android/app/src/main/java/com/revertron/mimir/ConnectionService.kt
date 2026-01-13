package com.revertron.mimir

import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
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
import androidx.core.content.edit
import androidx.core.os.postDelayed
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.revertron.mimir.NotificationHelper.Companion.showCallNotification
import com.revertron.mimir.NotificationHelper.Companion.showGroupInviteNotification
import com.revertron.mimir.NotificationHelper.Companion.cancelCallNotifications
import com.revertron.mimir.net.CallStatus
import com.revertron.mimir.net.EventListener
import com.revertron.mimir.net.InfoProvider
import com.revertron.mimir.net.InfoResponse
import com.revertron.mimir.net.MediatorClient
import com.revertron.mimir.net.MediatorManager
import com.revertron.mimir.net.Message
import com.revertron.mimir.net.MimirServer
import com.revertron.mimir.net.PeerStatus
import com.revertron.mimir.net.SystemMessage
import com.revertron.mimir.net.parseSystemMessage
import com.revertron.mimir.net.readHeader
import com.revertron.mimir.net.readMessage
import com.revertron.mimir.net.writeMessage
import com.revertron.mimir.sec.GroupChatCrypto
import com.revertron.mimir.storage.PeerProvider
import com.revertron.mimir.storage.SqlStorage
import com.revertron.mimir.ui.SettingsData
import com.revertron.mimir.yggmobile.Yggmobile
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.lang.Thread.sleep
import java.util.concurrent.atomic.AtomicBoolean

class ConnectionService : Service(), EventListener, InfoProvider {

    companion object {
        const val TAG = "ConnectionService"
    }

    var mimirServer: MimirServer? = null
    var mediatorManager: MediatorManager? = null
    var globalMessageListener: MediatorManager.ChatMessageListener? = null
    val peerStatuses = HashMap<String, PeerStatus>()
    var broadcastPeerStatuses = true
    var updateAfter = 0L
    lateinit var updaterThread: HandlerThread
    lateinit var handler: Handler

    // Flag to prevent multiple simultaneous mediator connection attempts
    private val mediatorConnecting = AtomicBoolean(false)

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

                        // Initialize Yggdrasil Messenger
                        val peerProvider = PeerProvider(this)
                        val peers = peerProvider.getPeers()
                        val initialPeer = peers.random()
                        Log.i(TAG, "Creating Messenger with initial peer: $initialPeer")
                        val messenger = Yggmobile.newMessenger(initialPeer)
                        for (peer in peers) {
                            if (peer.contentEquals(initialPeer)) continue
                            messenger.addPeer(peer)
                        }

                        // Create MimirServer with Messenger
                        mimirServer = MimirServer(applicationContext, storage, peerProvider, accountInfo.clientId, accountInfo.keyPair, messenger, this, this, wakeLock)
                        mimirServer?.start()

                        // Create MediatorManager with Messenger and reconnection callback
                        val reconnectionCallback = object : MediatorManager.ReconnectionCallback {
                            override fun onChatReconnected(chatId: Long) {
                                // Retry sending undelivered messages and update status badge after successful reconnection
                                Log.i(TAG, "Chat $chatId reconnected, retrying undelivered messages and updating status")
                                handler.post {
                                    resendUndeliveredMessages(chatId)
                                    broadcastGroupChatStatus(chatId, storage)
                                }
                            }

                            override fun onMediatorStateChanged(mediatorPubkey: ByteArray) {
                                // Broadcast status updates for all chats on this mediator
                                handler.post {
                                    try {
                                        val allChats = storage.getGroupChatList()
                                        val chatsOnMediator = allChats.filter { it.mediatorPubkey.contentEquals(mediatorPubkey) }
                                        Log.d(TAG, "Broadcasting status for ${chatsOnMediator.size} chats after mediator state change")
                                        for (chat in chatsOnMediator) {
                                            broadcastGroupChatStatus(chat.chatId, storage)
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to broadcast group chat status", e)
                                    }
                                }
                            }
                        }
                        mediatorManager = MediatorManager(messenger, storage, accountInfo.keyPair, this, reconnectionCallback, this)
                        App.app.mediatorManager = mediatorManager

                        // Set PeerManager reference in MediatorManager after it's initialized
                        // This will be done asynchronously since MimirServer initializes PeerManager in its run() method
                        Thread {
                            var attempts = 0
                            while (attempts < 50) { // Wait up to 5 seconds
                                mimirServer?.let { server ->
                                    if (server.isPeerManagerInitialized()) {
                                        mediatorManager?.setPeerManager(server.peerManager)
                                        Log.i(TAG, "PeerManager reference set in MediatorManager")
                                        return@Thread
                                    }
                                }
                                Thread.sleep(100)
                                attempts++
                            }
                            Log.w(TAG, "Timeout waiting for PeerManager initialization")
                        }.start()

                        // Register global invite listener
                        registerInvitesListener(storage)
                        startGlobalChatListener(storage)

                        // Connect to all known mediators and subscribe to saved chats
                        if (haveNetwork(this)) {
                            Thread {
                                connectAndSubscribeToAllChats(storage)
                            }.start()
                        }

                        val n = NotificationHelper.createForegroundServiceNotification(this, State.Offline)
                        startForeground(1, n)
                    }

                    return START_STICKY
                }
            }
            "refresh_peer" -> {
                mimirServer?.refreshPeerList()
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

                cancelCallNotifications(this, incoming = true, ongoing = false)
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
                cancelCallNotifications(this, incoming = true, ongoing = true)
                Log.i(TAG, "Declining call")
                onCallStatusChanged(CallStatus.Hangup, null)
                mimirServer?.callDecline()
            }
            "call_hangup" -> {
                Log.i(TAG, "Hanging-up call")
                cancelCallNotifications(this, incoming = false, ongoing = true)
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
                val message = intent.getStringExtra("message")
                val replyTo = intent.getLongExtra("replyTo", 0L)
                val type = intent.getIntExtra("type", 0)

                if (pubkey != null && message != null) {
                    // Check if this is a saved message (pubkey matches current account)
                    val accountInfo = storage.getAccountInfo(1, 0L)
                    val myPubkey = (accountInfo!!.keyPair.public as Ed25519PublicKeyParameters).encoded
                    val isSavedMessage = myPubkey != null && pubkey.contentEquals(myPubkey)

                    if (isSavedMessage) {
                        // Saved message - store locally only, mark as delivered immediately
                        val messageBytes = message.toByteArray()
                        val db = storage.writableDatabase
                        val values = ContentValues().apply {
                            put("contact", SqlStorage.SAVED_MESSAGES_CONTACT_ID)
                            put("guid", storage.generateGuid(getUtcTimeMs(), messageBytes))
                            put("replyTo", replyTo)
                            put("incoming", false)
                            put("delivered", true)  // Immediately delivered
                            put("time", getUtcTimeMs())
                            put("edit", 0)
                            put("type", type)
                            put("message", messageBytes)
                            put("read", true)
                        }
                        val id = db.insert("messages", null, values)
                        Log.i(TAG, "Saved message $id")
                        // Notify listeners
                        for (listener in storage.listeners) {
                            listener.onMessageSent(id, SqlStorage.SAVED_MESSAGES_CONTACT_ID)
                        }
                    } else {
                        // Normal message - existing code
                        val keyString = Hex.toHexString(pubkey)
                        val id = storage.addMessage(pubkey, 0, replyTo, false, false, getUtcTimeMs(), 0, type, message.toByteArray())
                        Log.i(TAG, "Message $id to $keyString")
                        Thread {
                            mimirServer?.sendMessages()
                        }.start()
                    }
                }
            }
            "online" -> {
                mimirServer?.reconnectPeers()
                mimirServer?.signalNetworkChange() // Wake up online state thread immediately
                Log.i(TAG, "Resending unsent messages")
                mimirServer?.sendMessages()

                if (mediatorManager != null) {
                    // Prevent multiple parallel connection attempts (race condition protection)
                    if (!mediatorConnecting.compareAndSet(false, true)) {
                        Log.i(TAG, "Mediator connection already in progress, skipping duplicate request")
                    } else {
                        Thread {
                            try {
                                // Wait for PeerManager to signal online before connecting to mediators
                                // This ensures Yggdrasil network is fully up before mediator connections
                                val maxWaitTime = 15000L // 15 seconds max
                                val startTime = System.currentTimeMillis()

                                // First wait for PeerManager to be initialized
                                while (mimirServer?.isPeerManagerInitialized() == false &&
                                       System.currentTimeMillis() - startTime < maxWaitTime) {
                                    sleep(100)
                                }

                                // Then wait for it to be online
                                while (!(mimirServer?.peerManager?.isOnline() ?: false) &&
                                       System.currentTimeMillis() - startTime < maxWaitTime) {
                                    sleep(1000)
                                }

                                if (mimirServer?.peerManager?.isOnline() == true) {
                                    Log.i(TAG, "Yggdrasil network is online, proceeding with mediator connections")
                                    if (getNetworkType(this@ConnectionService) == NetType.CELLULAR) {
                                        Log.d(TAG, "Wait a bit before connecting to mediator...")
                                        sleep(5000)
                                    }
                                    // Reconnect to mediators and resubscribe to chats when coming online
                                    connectAndSubscribeToAllChats(storage)
                                } else {
                                    Log.w(TAG, "Timeout waiting for Yggdrasil network, skipping mediator connection")
                                }
                            } finally {
                                // Always clear the flag when done (success or failure)
                                mediatorConnecting.set(false)
                            }
                        }.start()
                    }
                }

                if (updateAfter == 0L) {
                    handler.postDelayed(1000) {
                        updateTick()
                    }
                }
            }
            "offline" -> {

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
            // Mediator commands
            "mediator_create_chat" -> {
                val name = intent.getStringExtra("name")
                val description = intent.getStringExtra("description") ?: ""
                val avatar = intent.getByteArrayExtra("avatar")
                if (name != null) {
                    Thread {
                        createChat(name, description, avatar)
                    }.start()
                }
            }
            "mediator_subscribe" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                if (chatId != 0L) {
                    Thread {
                        subscribeToChat(chatId, storage)
                    }.start()
                }
            }
            "mediator_send" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                val guid = intent.getLongExtra("guid", System.currentTimeMillis())
                val replyTo = intent.getLongExtra("reply_to", 0)
                val sendTime = intent.getLongExtra("send_time", System.currentTimeMillis())
                val type = intent.getIntExtra("type", 0)
                val message = intent.getStringExtra("message")
                if (chatId != 0L && message != null) {
                    Thread {
                        sendGroupChatMessage(chatId, storage, guid, replyTo, sendTime, type, message)
                    }.start()
                }
            }
            "mediator_leave" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                if (chatId != 0L) {
                    Thread {
                        leaveGroupChat(chatId)
                    }.start()
                }
            }
            "mediator_delete" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                if (chatId != 0L) {
                    Thread {
                        deleteGroupChat(chatId)
                    }.start()
                }
            }
            "mediator_send_invite" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                val recipientPubkey = intent.getByteArrayExtra("recipient_pubkey")
                if (chatId != 0L && recipientPubkey != null) {
                    Thread {
                        sendInviteToGroupChat(chatId, recipientPubkey)
                    }.start()
                }
            }
            "mediator_ban_user" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                val userPubkey = intent.getByteArrayExtra("user_pubkey")
                if (chatId != 0L && userPubkey != null) {
                    Thread {
                        banUserFromGroupChat(chatId, userPubkey)
                    }.start()
                }
            }
            "mediator_change_role" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                val userPubkey = intent.getByteArrayExtra("user_pubkey")
                val permissions = intent.getIntExtra("permissions", 0)
                if (chatId != 0L && userPubkey != null) {
                    Thread {
                        changeMemberRole(chatId, userPubkey, permissions)
                    }.start()
                }
            }
            "mediator_register_listener" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                Log.i(TAG, "Registered listener for chat $chatId")
            }
            "group_chat_status" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                if (chatId != 0L) {
                    handler.post {
                        broadcastGroupChatStatus(chatId, storage)
                    }
                } else {
                    Log.w(TAG, "group_chat_status command received with invalid chatId")
                }
            }
        }

        return START_STICKY
    }

    private fun sendInviteToGroupChat(chatId: Long, recipientPubkey: ByteArray) {
        val success = mediatorManager?.sendInviteToGroupChat(chatId, recipientPubkey) ?: false
        if (success) {
            val broadcastIntent = Intent("ACTION_MEDIATOR_INVITE_SENT").apply {
                putExtra("chat_id", chatId)
                putExtra("recipient_pubkey", recipientPubkey)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
        } else {
            broadcastMediatorError("send_invite", "Failed to send invite")
        }
    }

    private fun leaveGroupChat(chatId: Long) {
        val success = mediatorManager?.leaveGroupChat(chatId) ?: false
        if (success) {
            val broadcastIntent = Intent("ACTION_MEDIATOR_LEFT_CHAT").apply {
                putExtra("chat_id", chatId)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
        }
    }

    private fun deleteGroupChat(chatId: Long) {
        val success = mediatorManager?.deleteGroupChat(chatId) ?: false
        if (success) {
            val broadcastIntent = Intent("ACTION_MEDIATOR_CHAT_DELETED").apply {
                putExtra("chat_id", chatId)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
        } else {
            broadcastMediatorError("delete", "Failed to delete chat")
        }
    }

    private fun banUserFromGroupChat(chatId: Long, userPubkey: ByteArray) {
        val success = mediatorManager?.banUserFromGroupChat(chatId, userPubkey) ?: false
        if (success) {
            val broadcastIntent = Intent("ACTION_MEDIATOR_USER_BANNED").apply {
                putExtra("chat_id", chatId)
                putExtra("user_pubkey", userPubkey)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
        } else {
            broadcastMediatorError("ban_user", "Failed to ban user")
        }
    }

    private fun changeMemberRole(chatId: Long, userPubkey: ByteArray, newPermissions: Int) {
        val success = mediatorManager?.changeMemberRole(chatId, userPubkey, newPermissions.toByte()) ?: false
        if (success) {
            val broadcastIntent = Intent("ACTION_MEDIATOR_ROLE_CHANGED").apply {
                putExtra("chat_id", chatId)
                putExtra("user_pubkey", userPubkey)
                putExtra("permissions", newPermissions)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
        } else {
            broadcastMediatorError("change_role", "Failed to change member role")
        }
    }

    private fun sendGroupChatMessage(chatId: Long, storage: SqlStorage, guid: Long, replyTo: Long, sendTime: Long, type: Int, messageData: String) {
        try {
            // Get chat info for encryption
            val chatInfo = storage.getGroupChat(chatId)
            if (chatInfo == null) {
                Log.e(TAG, "Chat $chatId not found")
                broadcastMediatorError("send", "Chat not found")
                return
            }

            // Serialize message for wire transmission
            val baos = java.io.ByteArrayOutputStream()
            val dos = java.io.DataOutputStream(baos)

            val message = Message(guid, replyTo, sendTime, 0, type, messageData.toByteArray())

            // For attachments, writeMessage() needs the file path to read the file/image
            val filePath = if (type == 1 || type == 3) File(filesDir, "files").absolutePath else ""
            writeMessage(dos, message, filePath)

            // Encrypt the serialized message
            val encryptedData = GroupChatCrypto.encryptMessage(baos.toByteArray(), chatInfo.sharedKey)

            // Send to mediator
            val client = mediatorManager!!.getOrCreateClient(MediatorManager.getDefaultMediatorPubkey())
            val (messageId, newGuid) = client.sendMessage(chatId, guid, sendTime, encryptedData)
            Log.i(TAG, "Message sent with ID: $messageId, guid = $guid ($newGuid), replyTo = $replyTo")

            // Update server message id for later sync
            storage.updateGroupMessageServerId(chatId, guid, messageId)

            if (newGuid != 0L && newGuid != guid) {
                storage.changeGroupMessageGuid(chatId, guid, newGuid)
            }

            // Mark message as delivered after successful send to mediator
            storage.setGroupMessageDelivered(chatId, guid, true)

            val broadcastIntent = Intent("ACTION_MEDIATOR_MESSAGE_SENT").apply {
                putExtra("chat_id", chatId)
                putExtra("message_id", messageId)
                putExtra("guid", guid)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            broadcastMediatorError("send", e.message ?: "Unknown error")
        }
    }

    private fun resendUndeliveredMessages(chatId: Long) {
        mediatorManager?.resendUndeliveredMessages(chatId)
    }

    private fun subscribeToChat(chatId: Long, storage: SqlStorage) {
        try {
            val client = mediatorManager!!.getOrCreateClient(MediatorManager.getDefaultMediatorPubkey())
            val serverLastId = client.subscribe(chatId)
            Log.i(TAG, "Subscribed to chat $chatId (server last message ID: $serverLastId)")

            // Mark chat as subscribed and broadcast status for badge update
            mediatorManager?.markChatSubscribed(chatId)
            broadcastGroupChatStatus(chatId, storage)

            val broadcastIntent = Intent("ACTION_MEDIATOR_SUBSCRIBED").apply {
                putExtra("chat_id", chatId)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)

            Thread {
                Thread.sleep(1000)
                // Sync missed messages BEFORE registering listener to prevent race condition
                // where push messages arrive and update localLastId before sync completes
                syncMissedMessages(chatId, client, storage, serverLastId)
                // Re-send any undelivered messages after successful subscription
                resendUndeliveredMessages(chatId)

                // NOW register message listener for future push messages
                globalMessageListener?.let {
                    mediatorManager?.registerMessageListener(chatId, it)
                    Log.i(TAG, "Registered message listener for chat $chatId after sync")
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error subscribing to chat", e)
            // Mark chat as unsubscribed and broadcast status for badge update
            mediatorManager?.markChatUnsubscribed(chatId)
            broadcastGroupChatStatus(chatId, storage)
            broadcastMediatorError("subscribe", e.message ?: "Unknown error")
        }
    }

    /**
     * Validates message data before saving to database.
     * For attachment messages (type 1 and 3), verifies the data is valid JSON.
     * Corrupted data is replaced with an error placeholder.
     *
     * @param data The message data to validate
     * @param type The message type (1=image, 3=file, 0=text, etc.)
     * @param guid The message GUID (for logging)
     * @return Validated data, or error placeholder if validation fails
     */
    private fun validateMessageData(data: ByteArray, type: Int, guid: Long): ByteArray {
        // Only validate JSON for attachment types
        if (type != 1 && type != 3) {
            return data
        }

        if (data.isEmpty()) {
            Log.e(TAG, "CORRUPTED MESSAGE: guid=$guid type=$type - data is empty")
            return """{"text":"<Message data corrupted>","name":""}""".toByteArray()
        }

        try {
            val dataStr = String(data, Charsets.UTF_8)

            // Check if it starts with valid JSON
            if (dataStr.isEmpty() || dataStr[0] != '{') {
                Log.e(TAG, "CORRUPTED MESSAGE: guid=$guid type=$type - data doesn't start with '{'. First 4 bytes: ${data.take(4).joinToString(" ") { "0x%02x".format(it) }}")
                return """{"text":"<Message data corrupted>","name":""}""".toByteArray()
            }

            // Verify it's parseable JSON
            JSONObject(dataStr)
            return data // Valid

        } catch (e: JSONException) {
            Log.e(TAG, "CORRUPTED MESSAGE: guid=$guid type=$type - JSON parsing failed: ${e.message}. Data length: ${data.size}")
            return """{"text":"<Message data corrupted>","name":""}""".toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "CORRUPTED MESSAGE: guid=$guid type=$type - validation error: ${e.message}")
            return """{"text":"<Message data corrupted>","name":""}""".toByteArray()
        }
    }

    /**
     * Parses and saves a group chat message.
     * Handles decryption, deserialization, media attachments, deduplication, and database storage.
     *
     * @param chatId The group chat ID
     * @param messageId The server message ID
     * @param guid The message GUID (for deduplication)
     * @param author The message author's public key
     * @param encryptedData The encrypted message data
     * @param storage The storage instance
     * @param broadcast Whether to broadcast the message to activities (true for real-time, false for sync)
     * @param fromSync Whether this message is being synced from server (true) or received real-time (false)
     * @return Local message ID if successful, 0 if skipped/failed
     */
    private fun parseAndSaveGroupMessage(
        chatId: Long,
        messageId: Long,
        guid: Long,
        timestamp: Long,
        author: ByteArray,
        encryptedData: ByteArray,
        storage: SqlStorage,
        broadcast: Boolean = true,
        fromSync: Boolean = false
    ): Long {
        try {
            // Check if this is a system message from the mediator (not encrypted)
            val mediatorPubkey = MediatorManager.getDefaultMediatorPubkey()
            if (author.contentEquals(mediatorPubkey)) {
                Log.d(TAG, "Processing system message $messageId for chat $chatId (not encrypted)")

                // Parse system message to handle member management
                val sysMsg = parseSystemMessage(encryptedData)
                // Handle message deletion - this is an invisible system message
                if (sysMsg is SystemMessage.MessageDeleted) {
                    Log.i(TAG, "Deleting message with guid ${sysMsg.deletedGuid} from chat $chatId")
                    storage.deleteGroupMessageByGuid(chatId, sysMsg.deletedGuid)
                    // Don't save this system message to DB - it's invisible
                    return 0
                }

                // System messages are not encrypted, save directly to database
                // Format: [event_code(1)][...event-specific data...]
                val localId = storage.addGroupMessage(
                    chatId,
                    messageId,
                    guid,
                    author,
                    timestamp, // Use current time for system messages
                    1000, // System messages are type 1000
                    true, // Mark as system message
                    encryptedData, // This is actually unencrypted system message data
                    fromSync = fromSync
                )

                Log.i(TAG, "System message saved with local ID: $localId")
                return localId
            }

            // Get chat info to retrieve shared key
            val chatInfo = storage.getGroupChat(chatId)
            if (chatInfo == null) {
                Log.e(TAG, "Chat $chatId not found in database")
                return 0
            }

            // Decrypt message using shared key
            val decryptedData = try {
                GroupChatCrypto.decryptMessage(encryptedData, chatInfo.sharedKey)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt message $messageId (${encryptedData.size} bytes)", e)

                // Save failed message to DB with error text
                val errorMessage = "<Can't decrypt the message>".toByteArray()
                val localId = storage.addGroupMessage(
                    chatId,
                    messageId,
                    guid,
                    author,
                    System.currentTimeMillis(), // Use current time since we can't decrypt the real timestamp
                    0, // Text message type
                    false, // Not a system message
                    errorMessage,
                    fromSync = fromSync
                )

                return localId
            }

            // Deserialize message using the standard readMessage function
            val bais = ByteArrayInputStream(decryptedData)
            val dis = DataInputStream(bais)

            // Read header and message
            val header = readHeader(dis)
            val message = readMessage(dis)

            if (message == null) {
                Log.e(TAG, "Failed to deserialize message for chat $chatId")
                return 0
            }

            Log.d(TAG, "Got message for chat $chatId: guid = ${message.guid}, replyTo = ${message.replyTo}")

            var m = ByteArray(0)
            // Handle different message types
            if (message.type == 1 || message.type == 3) {
                // Media attachment: extract file and get text from JSON
                // Type 1 = image, Type 2 = general file
                val typeLabel = if (message.type == 1) "image" else "file"
                Log.i(TAG, "Processing $typeLabel attachment for chat $chatId")

                try {
                    // Parse wire format: [jsonSize(u32)][JSON][fileBytes]
                    var offset = 0

                    // Read JSON length (first 4 bytes, big-endian)
                    val jsonSize = ((message.data[offset].toInt() and 0xFF) shl 24) or
                            ((message.data[offset + 1].toInt() and 0xFF) shl 16) or
                            ((message.data[offset + 2].toInt() and 0xFF) shl 8) or
                            (message.data[offset + 3].toInt() and 0xFF)
                    offset += 4

                    // Extract original JSON metadata
                    val originalJson = JSONObject(String(message.data, offset, jsonSize, Charsets.UTF_8))
                    offset += jsonSize

                    // Extract file bytes
                    val fileBytes = message.data.copyOfRange(offset, message.data.size)

                    // Generate new filename and save file bytes
                    val fileName = randomString(16)
                    val ext = if (message.type == 1) {
                        // For images, detect extension from image bytes
                        getImageExtensionOrNull(fileBytes)
                    } else {
                        // For files, use original extension from metadata
                        originalJson.optString("originalName", "file").substringAfterLast('.', "bin")
                    }
                    val fullName = "$fileName.$ext"

                    saveFileForMessage(this@ConnectionService, fullName, fileBytes)
                    Log.i(TAG, "Saved $typeLabel attachment: $fullName (${fileBytes.size} bytes)")

                    // Update JSON with new filename, keep all other fields (text, size, hash, originalName, mimeType)
                    originalJson.put("name", fullName)
                    m = originalJson.toString().toByteArray()

                } catch (e: Exception) {
                    Log.e(TAG, "Error processing attachment: ${e.message}", e)
                }
            } else {
                // Plain text message
                m = message.data
            }

            //Log.i(TAG, "Decrypted message from ${author.take(8)}: $message")

            // Check if message already exists (dedup by GUID)
            if (storage.checkGroupMessageExists(chatId, message.guid)) {
                Log.i(TAG, "Message with guid=${message.guid} already exists, doing nothing")
                return 0
            }

            // Validate message data before saving to prevent database corruption
            val validatedData = validateMessageData(m, message.type, message.guid)

            // Save to database
            val localId = storage.addGroupMessage(
                chatId,
                messageId,
                message.guid,
                author,
                message.sendTime,
                message.type,
                false, // not a system message
                validatedData,
                message.replyTo,
                fromSync = fromSync
            )

            Log.i(TAG, "Message saved with local ID: $localId")
            return localId
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message for chat $chatId", e)
            return 0
        }
    }

    /**
     * Syncs missed messages from mediator for a group chat.
     * Fetches all messages since the last confirmed server message ID.
     * Uses batch API for efficiency (up to 100 messages per request).
     *
     * @param serverLastId Optional server last message ID (if already known from subscribe response)
     */
    private fun syncMissedMessages(chatId: Long, client: MediatorClient, storage: SqlStorage, serverLastId: Long? = null) {
        client.syncMissedMessages(chatId, storage, this, serverLastId)
    }

    /**
     * Connects to all known mediators and subscribes to all saved group chats.
     * This is called on service start to establish always-on connections.
     * Only connects to mediators that need connection (not already connected).
     */
    private fun connectAndSubscribeToAllChats(storage: SqlStorage) {
        mediatorManager?.connectAndSubscribeToAllChats(globalMessageListener) { chatId ->
            broadcastGroupChatStatus(chatId, storage)
        }
    }

    private fun createChat(name: String, description: String, avatar: ByteArray?) {
        mediatorManager?.createChat(
            name,
            description,
            avatar,
            MediatorManager.getDefaultMediatorPubkey(),
            globalMessageListener!!,
            successBroadcaster = { chatId ->
                // Broadcast success
                val broadcastIntent = Intent("ACTION_MEDIATOR_CHAT_CREATED").apply {
                    putExtra("chat_id", chatId)
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
            },
            errorBroadcaster = { operation, message ->
                broadcastMediatorError(operation, message ?: "Unknown error")
            }
        )
    }

    private fun registerInvitesListener(storage: SqlStorage) {
        mediatorManager?.registerInviteListener(object : MediatorManager.InviteListener {
            override fun onInviteReceived(
                inviteId: Long,
                chatId: Long,
                fromPubkey: ByteArray,
                timestamp: Long,
                chatName: String,
                chatDescription: String,
                chatAvatar: ByteArray?,
                encryptedData: ByteArray
            ) {
                Log.i(TAG, "Invite received in service: chat=$chatName from=${Hex.toHexString(fromPubkey).take(8)}")

                // Save invite to database (returns invite ID and avatar path)
                val (inviteDbId, avatarPath) = storage.saveGroupInvite(
                    chatId,
                    inviteId.toLong(),
                    fromPubkey,
                    timestamp,
                    chatName,
                    chatDescription,
                    chatAvatar,
                    encryptedData
                )

                // Notify storage listeners
                for (listener in storage.listeners) {
                    listener.onGroupInviteReceived(inviteDbId, chatId, fromPubkey)
                }

                // Broadcast to app
                val intent = Intent("ACTION_GROUP_INVITE_RECEIVED").apply {
                    putExtra("invite_id", inviteDbId)
                    putExtra("chat_id", chatId)
                    putExtra("from_pubkey", fromPubkey)
                    putExtra("chat_name", chatName)
                    putExtra("chat_description", chatDescription)
                    putExtra("chat_avatar_path", avatarPath)
                }
                LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(intent)

                // Show notification
                showGroupInviteNotification(
                    this@ConnectionService,
                    inviteDbId,
                    chatId,
                    fromPubkey,
                    timestamp,
                    chatName,
                    chatDescription,
                    avatarPath,
                    encryptedData
                )
            }
        })
    }

    private fun startGlobalChatListener(storage: SqlStorage) {
        // Register global message listener for all chats
        // We use a single global listener that routes messages based on chatId
        globalMessageListener = object : MediatorManager.ChatMessageListener {
            override fun onChatMessage(chatId: Long, messageId: Long, guid: Long, timestamp: Long, author: ByteArray, data: ByteArray) {
                Log.i(TAG, "Message received for chat $chatId: msgId=$messageId, guid=$guid")

                Thread {
                    // Use shared parsing logic with broadcast enabled (real-time messages)
                    parseAndSaveGroupMessage(
                        chatId,
                        messageId,
                        guid,
                        timestamp,
                        author,
                        data,
                        storage
                    )
                }.start()
            }
        }

        // Note: We don't register the global listener here for all chats.
        // Instead, it will be registered per-chat when subscribing (see mediator_subscribe command)
        // But we keep the listener instance available for registration

        Log.i(TAG, "MediatorManager initialized with invite listener")
    }

    override fun onDestroy() {
        Log.i(TAG, "ConnectionService destroying - cleaning up resources")

        // 1. Disconnect all mediator connections
        mediatorManager?.disconnectAll()
        mediatorManager = null
        App.app.mediatorManager = null

        // 2. Stop P2P server
        mimirServer?.stopServer()
        mimirServer = null

        // 3. Stop update thread
        updaterThread.quitSafely()

        super.onDestroy()
    }

    override fun onServerStateChanged(online: Boolean) {
        val state = when(online) {
            true -> State.Online
            false -> State.Offline
        }
        val n = NotificationHelper.createForegroundServiceNotification(this, state)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, n)
    }

    override fun onTrackerPing(online: Boolean) {
        if (online) {
            if (!mediatorConnecting.getAndSet(true)) {
                mediatorManager?.connectAndSubscribeToAllChats(globalMessageListener) { chatId ->
                    broadcastGroupChatStatus(chatId, App.app.storage)
                }
                mediatorConnecting.set(false)
            }
            val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
            preferences.edit {
                putLong("trackerPingTime", getUtcTime())
                apply()
            }
        }
    }

    override fun onClientConnected(from: ByteArray, address: String, clientId: Int) {
        val expiration = getUtcTime() + IP_CACHE_DEFAULT_TTL
        val storage = (application as App).storage
        storage.saveIp(from, address, 0, clientId, 0, expiration)
    }

    override fun onMessageReceived(from: ByteArray, guid: Long, replyTo: Long, sendTime: Long, editTime: Long, type: Int, message: ByteArray) {
        val storage = (application as App).storage
        if (type == 1 || type == 3) {
            // Handle attachments: type 1 = image, type 3 = file
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
            cancelCallNotifications(this, incoming = true, ongoing = true)
            val intent = Intent("ACTION_FINISH_CALL")
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
    }

    override fun onMessageDelivered(to: ByteArray, guid: Long, delivered: Boolean) {
        (application as App).storage.setMessageDelivered(to, guid, delivered)
    }

    override fun onPeerStatusChanged(from: ByteArray, status: PeerStatus) {
        val contact = Hex.toHexString(from)
        peerStatuses[contact] = status
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
        Log.d(TAG, "getMyInfo called with ifUpdatedSince=$ifUpdatedSince")
        val info = (application as App).storage.getAccountInfo(1, ifUpdatedSince) ?: return null
        Log.d(TAG, "getMyInfo: Got account info - name=${info.name}, updated=${info.updated}")
        var avatar: ByteArray? = null
        if (info.avatar.isNotEmpty()) {
            val avatarsDir = File(filesDir, "avatars")
            val f = File(avatarsDir, info.avatar)
            if (f.exists()) {
                avatar = f.readBytes()
                Log.d(TAG, "getMyInfo: Loaded avatar from ${f.path}, size=${avatar.size}")
            } else {
                Log.w(TAG, "getMyInfo: Avatar file not found: ${f.path}")
            }
        }
        return InfoResponse(info.updated, info.name, info.info, avatar)
    }

    override fun getContactUpdateTime(pubkey: ByteArray): Long {
        return (application as App).storage.getContactUpdateTime(pubkey)
    }

    override fun updateContactInfo(pubkey: ByteArray, info: InfoResponse) {
        val storage = (application as App).storage
        val id = storage.getContactId(pubkey)
        Log.i(TAG, "Updating contact info $id to ${info.nickname}")
        storage.renameContact(id, info.nickname, false)
        storage.updateContactInfo(id, info.info)
        storage.updateContactAvatar(id, info.avatar)
    }

    override fun getFilesDirectory(): String {
        return filesDir.absolutePath + "/files"
    }

    private fun broadcastMediatorError(operation: String, error: String) {
        val intent = Intent("ACTION_MEDIATOR_ERROR").apply {
            putExtra("operation", operation)
            putExtra("error", error)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * Broadcast the connection status of a group chat to update UI badges.
     * Combines mediator connection state with chat subscription state.
     */
    private fun broadcastGroupChatStatus(chatId: Long, storage: SqlStorage) {
        val chatInfo = storage.getGroupChat(chatId) ?: return
        val status = mediatorManager?.getGroupChatStatus(chatId, chatInfo.mediatorPubkey)
            ?: MediatorManager.GroupChatStatus.DISCONNECTED

        val intent = Intent("ACTION_GROUP_CHAT_STATUS").apply {
            putExtra("chat_id", chatId)
            putExtra("status", status.name) // Use enum name string instead of enum
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun updateTick(forced: Boolean = false) {
        if (BuildConfig.DEBUG && !forced) {
            Log.i(TAG, "Skipping update check in debug build")
            return
        }
        if (System.currentTimeMillis() >= updateAfter) {

            val windowContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
                val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
                createWindowContext(display, TYPE_APPLICATION_OVERLAY, null)
            } else {
                this@ConnectionService
            }

            val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
            val updatesEnabled = preferences.getBoolean(SettingsData.KEY_AUTO_UPDATES, true)

            if (updatesEnabled || forced) {
                val delay = if (checkUpdates(windowContext, forced)) {
                    3600 * 1000L
                } else {
                    600 * 1000L
                }
                updateAfter = System.currentTimeMillis() + delay
                handler.postDelayed(delay) {
                    updateTick()
                }
            } else {
                updateAfter = System.currentTimeMillis() + 600 * 1000L
                handler.postDelayed(600 * 1000L) {
                    updateTick()
                }
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