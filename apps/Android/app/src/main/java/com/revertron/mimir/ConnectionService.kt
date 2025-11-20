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
import com.revertron.mimir.net.MediatorClient
import com.revertron.mimir.net.MediatorManager
import com.revertron.mimir.net.Message
import com.revertron.mimir.net.MimirServer
import com.revertron.mimir.net.PeerStatus
import com.revertron.mimir.net.readHeader
import com.revertron.mimir.net.readMessage
import com.revertron.mimir.net.writeMessage
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
                        mediatorManager = MediatorManager(messenger, storage, accountInfo.keyPair, this)
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
            "ACTION_SEND_REACTION" -> {
                val messageGuid = intent.getLongExtra("messageGuid", 0L)
                val emoji = intent.getStringExtra("emoji")
                val add = intent.getBooleanExtra("add", true)
                val chatId = if (intent.hasExtra("chatId")) intent.getLongExtra("chatId", 0L) else null
                val contactPubkey = intent.getByteArrayExtra("contactPubkey")

                if (messageGuid != 0L && emoji != null) {
                    Log.i(TAG, "Sending reaction: emoji=$emoji, messageGuid=$messageGuid, add=$add, chatId=$chatId")

                    // For personal chats, send directly to contact
                    if (contactPubkey != null) {
                        val sent = mimirServer?.sendReaction(contactPubkey, messageGuid, emoji, add, chatId) ?: false
                        if (!sent) {
                            // No active connection - queue for later
                            Log.i(TAG, "No connection available, queueing reaction for later")
                            storage.addPendingReaction(messageGuid, chatId, contactPubkey, emoji, add)
                        }
                    }
                    // For group chats, send via mediator (TODO: implement)
                    else if (chatId != null) {
                        // Group chat reactions would be sent via mediator
                        Log.w(TAG, "Group chat reactions not yet implemented")
                    }
                }
            }
            "online" -> {
                mimirServer?.reconnectPeers()
                Log.i(TAG, "Resending unsent messages")
                mimirServer?.sendMessages()

                Thread {
                    Thread.sleep(2000)
                    // Reconnect to mediators and resubscribe to chats when coming online
                    connectAndSubscribeToAllChats(storage)
                }.start()

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
                        leaveGroupChat(chatId, storage)
                    }.start()
                }
            }
            "mediator_delete" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                if (chatId != 0L) {
                    Thread {
                        deleteGroupChat(chatId, storage)
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
            // Get chat info to retrieve shared key
            val chatInfo = storage.getGroupChat(chatId)
            if (chatInfo == null) {
                Log.e(TAG, "Chat $chatId not found")
                broadcastMediatorError("send_invite", "Chat not found")
                return
            }

            // Encrypt shared key for recipient
            val encryptedKey = GroupChatCrypto.encryptSharedKey(chatInfo.sharedKey, recipientPubkey)

            // Send invite via mediator
            val client = mediatorManager!!.getOrCreateClient(chatInfo.mediatorPubkey)
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
            val client = mediatorManager!!.getOrCreateClient(MediatorManager.getDefaultMediatorPubkey())
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

    private fun deleteGroupChat(chatId: Long, storage: SqlStorage) {
        try {
            val client = mediatorManager!!.getOrCreateClient(MediatorManager.getDefaultMediatorPubkey())
            // Delete chat on mediator first
            val success = client.deleteChat(chatId)
            if (success) {
                Log.i(TAG, "Deleted chat $chatId on mediator")
                // Only delete from storage after successful deletion on mediator
                storage.deleteGroupChat(chatId)
                Log.i(TAG, "Deleted chat $chatId from local storage")

                // Broadcast success using same intent as leave (both result in chat removal)
                val broadcastIntent = Intent("ACTION_MEDIATOR_LEFT_CHAT").apply {
                    putExtra("chat_id", chatId)
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
            } else {
                Log.e(TAG, "Failed to delete chat $chatId on mediator")
                broadcastMediatorError("delete", "Failed to delete chat on mediator")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting chat", e)
            broadcastMediatorError("delete", e.message ?: "Unknown error")
        }
    }

    private fun sendGroupChatMessage(
        chatId: Long,
        storage: SqlStorage,
        guid: Long,
        replyTo: Long,
        sendTime: Long,
        type: Int,
        messageData: String
    ) {
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

            val message = Message(
                guid = guid,
                replyTo = replyTo,
                sendTime = sendTime,
                editTime = 0,
                type = type,
                data = messageData.toByteArray()
            )

            // For attachments, writeMessage() needs the file path to read the image
            val filePath = if (type == 1) File(filesDir, "files").absolutePath else ""
            writeMessage(dos, message, filePath)

            // Encrypt the serialized message
            val encryptedData = GroupChatCrypto.encryptMessage(baos.toByteArray(), chatInfo.sharedKey)

            // Send to mediator
            val client = mediatorManager!!.getOrCreateClient(MediatorManager.getDefaultMediatorPubkey())
            val messageId = client.sendMessage(chatId, guid, encryptedData)
            Log.i(TAG, "Message sent with ID: $messageId, guid = $guid, replyTo = $replyTo")

            // Update server message id for later sync
            storage.updateGroupMessageServerId(chatId, guid, messageId)

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
            val client = mediatorManager!!.getOrCreateClient(MediatorManager.getDefaultMediatorPubkey())
            val serverLastId = client.subscribe(chatId)
            Log.i(TAG, "Subscribed to chat $chatId (server last message ID: $serverLastId)")

            // Register message listener for this chat
            globalMessageListener?.let {
                mediatorManager?.registerMessageListener(chatId, it)
                Log.i(TAG, "Registered message listener for chat $chatId")
            }

            val broadcastIntent = Intent("ACTION_MEDIATOR_SUBSCRIBED").apply {
                putExtra("chat_id", chatId)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)

            // Sync missed messages after successful subscription (pass server last ID from subscribe response)
            Thread {
                Thread.sleep(1000)
                syncMissedMessages(chatId, client, storage, serverLastId)
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error subscribing to chat", e)
            broadcastMediatorError("subscribe", e.message ?: "Unknown error")
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
     * @return Local message ID if successful, 0 if skipped/failed
     */
    private fun parseAndSaveGroupMessage(
        chatId: Long,
        messageId: Long,
        guid: Long,
        author: ByteArray,
        encryptedData: ByteArray,
        storage: SqlStorage,
        broadcast: Boolean = true
    ): Long {
        try {
            // Check if this is a system message from the mediator (not encrypted)
            val mediatorPubkey = MediatorManager.getDefaultMediatorPubkey()
            if (author.contentEquals(mediatorPubkey)) {
                Log.d(TAG, "Processing system message $messageId for chat $chatId (not encrypted)")

                // System messages are not encrypted, save directly to database
                // Format: [event_code(1)][...event-specific data...]
                val localId = storage.addGroupMessage(
                    chatId,
                    messageId,
                    guid,
                    author,
                    System.currentTimeMillis(), // Use current time for system messages
                    1000, // System messages are type 1000
                    true, // Mark as system message
                    encryptedData // This is actually unencrypted system message data
                )

                // Broadcast to activities if requested
                if (broadcast && localId > 0) {
                    val intent = Intent("ACTION_GROUP_MESSAGE_RECEIVED").apply {
                        putExtra("chat_id", chatId)
                        putExtra("message_id", messageId)
                        putExtra("local_id", localId)
                        putExtra("sender", author)
                    }
                    LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(intent)
                }

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
                    errorMessage
                )

                // Broadcast to activities if requested
                if (broadcast && localId > 0) {
                    val intent = Intent("ACTION_GROUP_MESSAGE_RECEIVED").apply {
                        putExtra("chat_id", chatId)
                        putExtra("message_id", messageId)
                        putExtra("local_id", localId)
                        putExtra("sender", author)
                    }
                    LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(intent)
                }

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
            if (message.type == 1) {
                // Media attachment: extract file and get text from JSON
                Log.i(TAG, "Processing media attachment for chat $chatId")

                try {
                    // Parse wire format: [jsonSize(u32)][JSON][imageBytes]
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

                    // Extract image bytes
                    val imageBytes = message.data.copyOfRange(offset, message.data.size)

                    // Generate new filename and save ONLY image bytes
                    val fileName = randomString(16)
                    val ext = getImageExtensionOrNull(imageBytes)
                    val fullName = "$fileName.$ext"

                    saveFileForMessage(this@ConnectionService, fullName, imageBytes)
                    Log.i(TAG, "Saved attachment file: $fullName (${imageBytes.size} bytes)")

                    // Update JSON with new filename, keep all other fields (text, size, hash)
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

            // Save to database
            val localId = storage.addGroupMessage(
                chatId,
                messageId,
                message.guid,
                author,
                message.sendTime,
                message.type,
                false, // not a system message
                m,
                message.replyTo
            )

            // Broadcast to activities if requested
            if (broadcast && localId > 0) {
                val intent = Intent("ACTION_GROUP_MESSAGE_RECEIVED").apply {
                    putExtra("chat_id", chatId)
                    putExtra("message_id", messageId)
                    putExtra("local_id", localId)
                    putExtra("sender", author)
                }
                LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(intent)
            }

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
        try {
            Log.i(TAG, "Starting message sync for chat $chatId")

            // Get last synced message ID from local database
            val localLastId = storage.getMaxServerMessageId(chatId)
            Log.d(TAG, "Local last message ID: $localLastId for chat $chatId")

            // Get last message ID from server (use provided value or fetch from server)
            val serverLastIdValue = serverLastId ?: try {
                client.getLastMessageId(chatId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get last message ID from server", e)
                broadcastMediatorError("sync", "Failed to get server state: ${e.message}")
                return
            }

            Log.i(TAG, "Server last message ID: $serverLastIdValue for chat $chatId")

            if (serverLastIdValue <= localLastId) {
                Log.i(TAG, "No missed messages for chat $chatId (local=$localLastId, server=$serverLastIdValue)")
                return
            }

            val missedCount = serverLastIdValue - localLastId
            Log.i(TAG, "Fetching $missedCount missed message(s) for chat $chatId")

            // Get chat info for decryption
            val chatInfo = storage.getGroupChat(chatId)
            if (chatInfo == null) {
                Log.e(TAG, "Chat $chatId not found in storage")
                return
            }

            // Fetch messages in batches (max 100 per request)
            var currentId = localLastId
            val batchSize = 100

            while (currentId < serverLastIdValue) {
                try {
                    val messages = client.getMessagesSince(chatId, currentId, batchSize)
                    if (messages.isEmpty()) {
                        Log.w(TAG, "No messages returned from server, stopping sync")
                        break
                    }

                    Log.d(TAG, "Fetched ${messages.size} message(s) for chat $chatId")

                    // Process each message
                    for (msgPayload in messages) {
                        try {
                            // Use shared parsing logic with broadcast disabled (sync messages)
                            val localId = parseAndSaveGroupMessage(
                                chatId,
                                msgPayload.messageId,
                                msgPayload.guid,
                                msgPayload.author,
                                msgPayload.data,
                                storage,
                                broadcast = false
                            )

                            if (localId > 0) {
                                Log.d(TAG, "Synced message ${msgPayload.messageId} (guid=${msgPayload.guid}) to local ID $localId")
                            } else {
                                Log.d(TAG, "Skipped message ${msgPayload.messageId} (may already exist or failed)")
                            }

                            currentId = msgPayload.messageId

                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing synced message ${msgPayload.messageId}", e)
                            currentId = msgPayload.messageId
                        }
                    }

                    // Update currentId to highest processed message
                    val lastProcessedId = messages.maxOfOrNull { it.messageId } ?: currentId
                    currentId = lastProcessedId

                    Log.d(TAG, "Batch sync progress: $currentId / $serverLastId for chat $chatId")

                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching message batch for chat $chatId", e)
                    broadcastMediatorError("sync", "Failed to fetch messages: ${e.message}")
                    break
                }
            }

            Log.i(TAG, "Message sync complete for chat $chatId (synced up to ID $currentId)")

            // Broadcast sync completion
            val syncIntent = Intent("ACTION_MESSAGES_SYNCED").apply {
                putExtra("chat_id", chatId)
                putExtra("last_synced_id", currentId)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(syncIntent)

        } catch (e: Exception) {
            Log.e(TAG, "Error syncing messages for chat $chatId", e)
            broadcastMediatorError("sync", "Message sync failed: ${e.message}")
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
                            val client = mediatorManager!!.getOrCreateClient(mediatorPubkey)

                            // Find all chats on this mediator
                            val chatsOnThisMediator = allChats.filter {
                                it.mediatorPubkey.contentEquals(mediatorPubkey)
                            }

                            Log.i(TAG, "Subscribing to ${chatsOnThisMediator.size} chats on mediator ${mediatorHex.take(8)}")

                            // Subscribe to all chats on this mediator
                            for (chat in chatsOnThisMediator) {
                                try {
                                    val serverLastId = client.subscribe(chat.chatId)
                                    Log.i(TAG, "Subscribed to chat ${chat.chatId} (${chat.name}) on mediator ${mediatorHex.take(8)} (server last ID: $serverLastId)")

                                    // Register message listener for this chat
                                    globalMessageListener?.let {
                                        mediatorManager?.registerMessageListener(chat.chatId, it)
                                    }
                                    Thread {
                                        syncMissedMessages(chat.chatId, client, storage, serverLastId)
                                    }.start()
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
            val mediatorPubkey = MediatorManager.getDefaultMediatorPubkey()
            val client = mediatorManager!!.getOrCreateClient(mediatorPubkey)

            // Create chat on mediator server
            val chatId = client.createChat(name, description, avatar)
            Log.i(TAG, "Chat created with ID: $chatId")

            // Generate shared encryption key
            val sharedKey = GroupChatCrypto.generateSharedKey()

            // Get owner pubkey (current user)
            val ownerPubkey = mediatorManager!!.getPublicKey()

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
            override fun onChatMessage(chatId: Long, messageId: Long, guid: Long, author: ByteArray, data: ByteArray) {
                Log.i(TAG, "Message received for chat $chatId: msgId=$messageId, guid=$guid")

                Thread {
                    // Use shared parsing logic with broadcast enabled (real-time messages)
                    parseAndSaveGroupMessage(
                        chatId,
                        messageId,
                        guid,
                        author,
                        data,
                        storage,
                        broadcast = true
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

        // Send any pending reactions for this contact
        Thread {
            sendPendingReactionsForContact(from, storage)
        }.start()
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

    override fun onReactionReceived(from: ByteArray, messageGuid: Long, emoji: String, add: Boolean, chatId: Long?) {
        val storage = (application as App).storage
        Log.i(TAG, "onReactionReceived: emoji=$emoji, messageGuid=$messageGuid, add=$add, chatId=$chatId")

        if (add) {
            storage.addReaction(messageGuid, chatId, from, emoji)
        } else {
            storage.removeReaction(messageGuid, chatId, from, emoji)
        }

        // Broadcast reaction update to active activities
        val intent = Intent("ACTION_REACTION_UPDATED").apply {
            putExtra("messageGuid", messageGuid)
            putExtra("emoji", emoji)
            putExtra("add", add)
            if (chatId != null) {
                putExtra("chatId", chatId)
            }
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
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

    private fun sendPendingReactionsForContact(contactPubkey: ByteArray, storage: SqlStorage) {
        val pendingReactions = storage.getPendingReactionsForContact(contactPubkey)
        if (pendingReactions.isEmpty()) {
            return
        }

        Log.i(TAG, "Sending ${pendingReactions.size} pending reactions for contact ${Hex.toHexString(contactPubkey).take(16)}...")

        for (pending in pendingReactions) {
            val sent = mimirServer?.sendReaction(
                contactPubkey,
                pending.messageGuid,
                pending.emoji,
                pending.add,
                pending.chatId
            ) ?: false

            if (sent) {
                // Remove from pending queue
                storage.removePendingReaction(pending.id)
                Log.i(TAG, "Sent pending reaction: emoji=${pending.emoji}, messageGuid=${pending.messageGuid}")
            } else {
                // Connection lost again, stop trying
                Log.w(TAG, "Failed to send pending reaction, connection lost")
                break
            }

            // Small delay between reactions to avoid flooding
            Thread.sleep(100)
        }
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