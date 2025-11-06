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
import com.revertron.mimir.net.MediatorManager
import com.revertron.mimir.net.MimirServer
import com.revertron.mimir.net.PeerStatus
import com.revertron.mimir.net.readHeader
import com.revertron.mimir.net.readMessage
import com.revertron.mimir.sec.GroupChatCrypto
import com.revertron.mimir.storage.PeerProvider
import com.revertron.mimir.storage.SqlStorage
import com.revertron.mimir.ui.SettingsData
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File

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
                        val messenger = com.revertron.mimir.yggmobile.Yggmobile.newMessenger(initialPeer)
                        for (peer in peers) {
                            if (peer.contentEquals(initialPeer)) continue
                            messenger.addPeer(peer)
                        }

                        // Create MimirServer with Messenger
                        mimirServer = MimirServer(applicationContext, storage, peerProvider, accountInfo.clientId, accountInfo.keyPair, messenger, this, this, wakeLock)
                        mimirServer?.start()

                        // Create MediatorManager with Messenger
                        mediatorManager = MediatorManager(messenger, storage, this)
                        App.app.mediatorManager = mediatorManager

                        // Register global invite listener
                        registerInvitesListener(storage)
                        startGlobalChatListener(storage)

                        // Connect to all known mediators and subscribe to saved chats
                        connectAndSubscribeToAllChats(storage)

                        val n = NotificationHelper.createForegroundServiceNotification(this, State.Offline)
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
                mimirServer?.reconnectPeers()
                Log.i(TAG, "Resending unsent messages")
                mimirServer?.sendMessages()

                // Reconnect to mediators and resubscribe to chats when coming online
                connectAndSubscribeToAllChats(storage)

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
                        createChat(storage, name, description, avatar)
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
                val message = intent.getByteArrayExtra("message")
                if (chatId != null && message != null) {
                    Thread {
                        sendGroupChatMessage(chatId, storage, guid, message)
                    }.start()
                }
            }
            "mediator_leave" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                if (chatId != 0L) {
                    Thread {
                        leaveGroupChat(chatId, storage)
                    }.start()
                }
            }
            "mediator_send_invite" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                val recipientPubkey = intent.getByteArrayExtra("recipient_pubkey")
                if (chatId != 0L && recipientPubkey != null) {
                    Thread {
                        sendInviteToGroupChat(chatId, storage, recipientPubkey)
                    }.start()
                }
            }
            "mediator_register_listener" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                Log.i(TAG, "Registered listener for chat $chatId")
            }
        }

        return START_STICKY
    }

    private fun sendInviteToGroupChat(chatId: Long, storage: SqlStorage, recipientPubkey: ByteArray) {
        try {
            val accountInfo = storage.getAccountInfo(1, 0L)
            if (accountInfo == null) {
                Log.e(TAG, "No account found")
                broadcastMediatorError("send_invite", "No account found")
                return
            }

            // Get chat info to retrieve shared key
            val chatInfo = storage.getGroupChat(chatId)
            if (chatInfo == null) {
                Log.e(TAG, "Chat $chatId not found")
                broadcastMediatorError("send_invite", "Chat not found")
                return
            }

            // Encrypt shared key for recipient
            val encryptedKey = GroupChatCrypto.encryptSharedKey(
                chatInfo.sharedKey,
                recipientPubkey
            )

            // Send invite via mediator
            val keyPair = accountInfo.keyPair
            val client = mediatorManager!!.getOrCreateClient(
                chatInfo.mediatorPubkey,
                keyPair
            )
            client.sendInvite(chatId, recipientPubkey, encryptedKey)

            Log.i(TAG, "Invite sent for chat $chatId")
            val broadcastIntent = Intent("ACTION_MEDIATOR_INVITE_SENT").apply {
                putExtra("chat_id", chatId)
                putExtra("recipient_pubkey", recipientPubkey)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending invite", e)
            broadcastMediatorError("send_invite", e.message ?: "Unknown error")
        }
    }

    private fun leaveGroupChat(chatId: Long, storage: SqlStorage) {
        try {
            val accountInfo = storage.getAccountInfo(1, 0L)
            if (accountInfo == null) {
                Log.e(TAG, "No account found")
                broadcastMediatorError("leave", "No account found")
                return
            }
            val keyPair = accountInfo.keyPair
            val client = mediatorManager!!.getOrCreateClient(
                MediatorManager.getDefaultMediatorPubkey(),
                keyPair
            )
            client.leaveChat(chatId)
            Log.i(TAG, "Left chat $chatId")
            val broadcastIntent = Intent("ACTION_MEDIATOR_LEFT_CHAT").apply {
                putExtra("chat_id", chatId)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
            storage.deleteGroupChat(chatId)
        } catch (e: Exception) {
            Log.e(TAG, "Error leaving chat", e)
            broadcastMediatorError("leave", e.message ?: "Unknown error")
        }
    }

    private fun sendGroupChatMessage(chatId: Long, storage: SqlStorage, guid: Long, message: ByteArray) {
        try {
            val accountInfo = storage.getAccountInfo(1, 0L)
            if (accountInfo == null) {
                Log.e(TAG, "No account found")
                broadcastMediatorError("send", "No account found")
                return
            }
            val keyPair = accountInfo.keyPair
            val client = mediatorManager!!.getOrCreateClient(
                MediatorManager.getDefaultMediatorPubkey(),
                keyPair
            )
            val messageId = client.sendMessage(chatId, guid, message)
            Log.i(TAG, "Message sent with ID: $messageId")
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

    private fun subscribeToChat(chatId: Long, storage: SqlStorage) {
        try {
            val accountInfo = storage.getAccountInfo(1, 0L)
            if (accountInfo == null) {
                Log.e(TAG, "No account found")
                broadcastMediatorError("subscribe", "No account found")
                return
            }
            val keyPair = accountInfo.keyPair
            val client = mediatorManager!!.getOrCreateClient(
                MediatorManager.getDefaultMediatorPubkey(),
                keyPair
            )
            client.subscribe(chatId)
            Log.i(TAG, "Subscribed to chat $chatId")

            // Register message listener for this chat
            globalMessageListener?.let {
                mediatorManager?.registerMessageListener(chatId, it)
                Log.i(TAG, "Registered message listener for chat $chatId")
            }

            val broadcastIntent = Intent("ACTION_MEDIATOR_SUBSCRIBED").apply {
                putExtra("chat_id", chatId)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error subscribing to chat", e)
            broadcastMediatorError("subscribe", e.message ?: "Unknown error")
        }
    }

    /**
     * Connects to all known mediators and subscribes to all saved group chats.
     * This is called on service start to establish always-on connections.
     * Only connects to mediators that need connection (not already connected).
     */
    private fun connectAndSubscribeToAllChats(storage: SqlStorage) {
        handler.post {
            try {
                Log.i(TAG, "Checking mediator connections...")

                // Get account info for authentication
                val accountInfo = storage.getAccountInfo(1, 0L)
                if (accountInfo == null) {
                    Log.e(TAG, "No account found for auto-connection")
                    return@post
                }
                val keyPair = accountInfo.keyPair

                // Get all unique known mediators from saved chats
                val knownMediators = storage.getKnownMediators().toMutableList()

                // Always include default mediator for listening to invites
                val defaultMediator = MediatorManager.getDefaultMediatorPubkey()
                if (!knownMediators.any { it.contentEquals(defaultMediator) }) {
                    knownMediators.add(defaultMediator)
                    Log.i(TAG, "Added default mediator for invite listening")
                }

                Log.i(TAG, "Found ${knownMediators.size} mediators to connect to")

                // Filter mediators that need connection
                val mediatorsToConnect = knownMediators.filter { mediatorPubkey ->
                    mediatorManager?.needsConnection(mediatorPubkey) ?: true
                }

                if (mediatorsToConnect.isEmpty()) {
                    Log.i(TAG, "All mediators already connected, skipping")
                    return@post
                }

                Log.i(TAG, "Need to connect to ${mediatorsToConnect.size} mediators")

                // Get all group chats for subscription
                val allChats = storage.getGroupChatList()

                // Connect to each mediator that needs connection
                for (mediatorPubkey in mediatorsToConnect) {
                    Thread {
                        try {
                            val mediatorHex = Hex.toHexString(mediatorPubkey)
                            Log.i(TAG, "Connecting to mediator ${mediatorHex.take(8)}...")

                            // Get or create connection to this mediator
                            val client = mediatorManager!!.getOrCreateClient(mediatorPubkey, keyPair)

                            // Find all chats on this mediator
                            val chatsOnThisMediator = allChats.filter {
                                it.mediatorPubkey.contentEquals(mediatorPubkey)
                            }

                            Log.i(TAG, "Subscribing to ${chatsOnThisMediator.size} chats on mediator ${mediatorHex.take(8)}")

                            // Subscribe to all chats on this mediator
                            for (chat in chatsOnThisMediator) {
                                try {
                                    client.subscribe(chat.chatId)
                                    Log.i(TAG, "Subscribed to chat ${chat.chatId} (${chat.name}) on mediator ${mediatorHex.take(8)}")

                                    // Register message listener for this chat
                                    globalMessageListener?.let {
                                        mediatorManager?.registerMessageListener(chat.chatId, it)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to subscribe to chat ${chat.chatId} on mediator ${mediatorHex.take(8)}", e)
                                }
                            }

                            Log.i(TAG, "Successfully connected to mediator ${mediatorHex.take(8)}")

                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to connect to mediator ${Hex.toHexString(mediatorPubkey).take(8)}", e)
                        }
                    }.start()
                }

                Log.i(TAG, "Auto-connection to ${mediatorsToConnect.size} mediators initiated")

            } catch (e: Exception) {
                Log.e(TAG, "Error in connectAndSubscribeToAllChats", e)
            }
        }
    }

    private fun createChat(storage: SqlStorage, name: String, description: String, avatar: ByteArray?) {
        try {
            val accountInfo = storage.getAccountInfo(1, 0L)
            if (accountInfo == null) {
                Log.e(TAG, "No account found")
                broadcastMediatorError("create_chat", "No account found")
                return
            }
            val keyPair = accountInfo.keyPair
            val mediatorPubkey = MediatorManager.getDefaultMediatorPubkey()
            val client = mediatorManager!!.getOrCreateClient(mediatorPubkey, keyPair)

            // Create chat on mediator server
            val chatId = client.createChat(name, description, avatar)
            Log.i(TAG, "Chat created with ID: $chatId")

            // Generate shared encryption key
            val sharedKey = GroupChatCrypto.generateSharedKey()

            // Get owner pubkey (current user)
            val ownerPubkey = (keyPair.public as Ed25519PublicKeyParameters).encoded

            // Save chat to local database
            val saved = storage.saveGroupChat(
                chatId,
                name,
                description,
                null, // Avatar path will be set below
                mediatorPubkey,
                ownerPubkey,
                sharedKey
            )

            // Save avatar locally if provided
            if (avatar != null && avatar.isNotEmpty()) {
                storage.updateGroupChatAvatar(chatId, avatar)
            }

            if (!saved) {
                Log.e(TAG, "Failed to save chat to database")
                broadcastMediatorError("create_chat", "Failed to save chat to database")
                return
            }

            // Subscribe to the chat on mediator
            client.subscribe(chatId)
            Log.i(TAG, "Subscribed to chat $chatId")

            // Register message listener for this chat
            globalMessageListener?.let {
                mediatorManager?.registerMessageListener(chatId, it)
                Log.i(TAG, "Registered message listener for chat $chatId")
            }

            val broadcastIntent = Intent("ACTION_MEDIATOR_CHAT_CREATED").apply {
                putExtra("chat_id", chatId)
                putExtra("name", name)
                putExtra("description", description)
                putExtra("mediator_address", Hex.toHexString(mediatorPubkey))
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating chat", e)
            broadcastMediatorError("create_chat", e.message ?: "Unknown error")
        }
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
                    chatAvatar,
                    encryptedData
                )
            }
        })
    }

    private fun startGlobalChatListener(storage: SqlStorage) {
        // Register global message listener for all chats
        // We use a single global listener that routes messages based on chatId
        globalMessageListener = object : MediatorManager.ChatMessageListener {
            override fun onChatMessage(chatId: Long, messageId: Long, guid: Long, author: ByteArray, data: ByteArray) {
                Log.i(TAG, "Message received for chat $chatId: msgId=$messageId, guid=$guid")

                Thread {
                    try {
                        // Get chat info to retrieve shared key
                        val chatInfo = storage.getGroupChat(chatId)
                        if (chatInfo == null) {
                            Log.e(TAG, "Chat $chatId not found in database")
                            return@Thread
                        }

                        // Decrypt message using shared key
                        val decryptedData = GroupChatCrypto.decryptMessage(data, chatInfo.sharedKey)

                        // Deserialize message using the standard readMessage function
                        val bais = ByteArrayInputStream(decryptedData)
                        val dis = DataInputStream(bais)

                        // Skip the header (16 bytes: stream(4) + type(4) + size(8)) that was written by writeMessage()
                        // readMessage() expects to start at the JSON size field, not the header
                        val header = readHeader(dis)

                        val message = readMessage(dis)

                        if (message == null) {
                            Log.e(TAG, "Failed to deserialize message for chat $chatId")
                            return@Thread
                        }

                        var m = ByteArray(0)
                        // Handle different message types
                        if (message.type == 1) {
                            // Media attachment: extract file and get text from JSON
                            Log.i(TAG, "Processing media attachment for chat $chatId")

                            try {
                                val fileName = randomString(16)
                                val ext = getImageExtensionOrNull(message.data)
                                val fullName = "$fileName.$ext"

                                // Extract image file from remaining bytes after JSON
                                try {
                                    saveFileForMessage(this@ConnectionService, fullName, message.data)
                                    Log.i(TAG, "Saved attachment file: $fullName (${message.data.size} bytes)")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error saving attachment file: ${e.message}", e)
                                }
                                // Reconstruct meta
                                val json = JSONObject().apply {
                                    put("guid", message.guid)
                                    put("replyTo", message.replyTo)
                                    put("sendTime", message.sendTime)
                                    put("editTime", message.editTime)
                                    put("type", message.type)
                                }
                                m = json.toString().toByteArray()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing attachment: ${e.message}", e)
                            }
                        } else {
                            // Plain text message
                            m = message.data
                        }

                        Log.i(TAG, "Decrypted message from ${author.take(8)}: $message")

                        // Save to database
                        val localId = storage.addGroupMessage(
                            chatId,
                            messageId,
                            message.guid,
                            author,
                            message.sendTime,
                            message.type,
                            false, // not a system message
                            m
                        )

                        // Broadcast to activities
                        val intent = Intent("ACTION_GROUP_MESSAGE_RECEIVED").apply {
                            putExtra("chat_id", chatId)
                            putExtra("message_id", messageId)
                            putExtra("local_id", localId)
                            putExtra("sender", author)
                        }
                        LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(intent)

                        Log.i(TAG, "Message saved with local ID: $localId")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing message for chat $chatId", e)
                    }
                }.start()
            }
        }

        // Note: We don't register the global listener here for all chats.
        // Instead, it will be registered per-chat when subscribing (see mediator_subscribe command)
        // But we keep the listener instance available for registration

        Log.i(TAG, "MediatorManager initialized with invite listener")
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
        val n = NotificationHelper.createForegroundServiceNotification(this, state)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
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
        val expiration = getUtcTime() + IP_CACHE_DEFAULT_TTL
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
            val avatarsDir = File(filesDir, "avatars")
            val f = File(avatarsDir, info.avatar)
            avatar = f.readBytes()
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