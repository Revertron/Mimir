package com.revertron.mimir.net

import android.util.Log
import com.revertron.mimir.App
import com.revertron.mimir.sec.GroupChatCrypto
import com.revertron.mimir.storage.SqlStorage
import com.revertron.mimir.yggmobile.Messenger
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import org.json.JSONArray
import java.lang.Thread.sleep
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.thread
import kotlin.concurrent.write

/**
 * Manager for maintaining persistent MediatorClient connections.
 *
 * Features:
 * - Connection pooling: One connection per mediator public key
 * - Multiple chat subscriptions per connection
 * - Automatic reconnection on disconnect
 * - Thread-safe connection management
 * - Message listener registration per chat
 *
 * Usage:
 * ```
 * val manager = MediatorManager(messenger)
 * val client = manager.getOrCreateClient(mediatorPubkey, keyPair)
 * client.subscribe(chatId)
 * manager.registerMessageListener(chatId, listener)
 * ```
 */
class MediatorManager(
    private val messenger: Messenger,
    private val storage: SqlStorage,
    private val keyPair: AsymmetricCipherKeyPair,
    private val infoProvider: InfoProvider,
    private val reconnectionCallback: ReconnectionCallback? = null
) {

    companion object {
        private const val TAG = "MediatorManager"

        // TODO: Replace with actual default mediator public key
        const val DEFAULT_MEDIATOR_PUBKEY = "42a0b0da2d8b2fd9d8242c3ab3d316ebd4d3adedeeacf4b77d741d23fc9c6902"

        /**
         * Get the default mediator public key as a ByteArray.
         */
        fun getDefaultMediatorPubkey(): ByteArray {
            return Hex.decode(DEFAULT_MEDIATOR_PUBKEY)
        }
    }

    // Map: mediator pubkey (hex string) -> MediatorClient
    private val clients = ConcurrentHashMap<String, MediatorClient>()

    // Map: mediator address -> connection status
    private val connectionStatus = ConcurrentHashMap<String, ConnectionState>()

    // Map: mediator address -> reconnection info
    private val reconnectionInfo = ConcurrentHashMap<String, ReconnectionInfo>()

    // Map: chatId -> Set of message listeners
    private val chatListeners = ConcurrentHashMap<Long, MutableSet<ChatMessageListener>>()

    // Map: chatId -> subscription status (true = subscribed, false = unsubscribed)
    private val chatSubscriptions = ConcurrentHashMap<Long, Boolean>()

    // Global invite listeners
    private val inviteListeners = mutableSetOf<InviteListener>()

    private val lock = ReentrantReadWriteLock()

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        FAILED
    }

    /**
     * Status of a group chat's connection to its mediator.
     * Combines mediator connection state with chat subscription state.
     */
    enum class GroupChatStatus {
        DISCONNECTED,     // Mediator not connected
        CONNECTING,       // Mediator connecting OR connected but not yet subscribed
        SUBSCRIBED        // Mediator connected AND chat subscribed
    }

    /**
     * Tracks reconnection state for a mediator.
     * Uses exponential backoff: 2s, 4s, 8s, 16s, 32s, 60s (max)
     */
    private data class ReconnectionInfo(
        var attemptCount: Int = 0,
        var lastAttemptTime: Long = 0,
        var keyPair: AsymmetricCipherKeyPair? = null,
        var reconnectThread: Thread? = null
    ) {
        companion object {
            private const val BASE_DELAY_MS = 2000L // 2 seconds
            private const val MAX_DELAY_MS = 60000L // 60 seconds
            private const val MAX_ATTEMPTS = 30 // After 30 attempts, stop and wait for manual reconnect
        }

        fun getNextDelay(): Long {
            val delay = minOf(BASE_DELAY_MS * (1 shl attemptCount), MAX_DELAY_MS)
            return delay
        }

        fun shouldAttemptReconnect(): Boolean {
            return attemptCount < MAX_ATTEMPTS
        }

        fun incrementAttempt() {
            attemptCount++
            lastAttemptTime = System.currentTimeMillis()
        }

        fun reset() {
            attemptCount = 0
            lastAttemptTime = 0
        }

        fun cancelReconnect() {
            reconnectThread?.interrupt()
            reconnectThread = null
        }
    }

    fun getOrCreateClient(): MediatorClient {
        return getOrCreateClient(getDefaultMediatorPubkey())
    }

    /**
     * Get or create a MediatorClient for the given mediator public key.
     * Reuses existing connection if available.
     *
     * @param mediatorPubkey The mediator's public key as ByteArray
     */
    fun getOrCreateClient(mediatorPubkey: ByteArray): MediatorClient = lock.write {
        val pubkeyHex = Hex.toHexString(mediatorPubkey)

        clients[pubkeyHex]?.let { existingClient ->
            if (existingClient.isRunning()) {
                Log.i(TAG, "Reusing existing connection to mediator $pubkeyHex")
                return@write existingClient
            } else {
                clients.remove(pubkeyHex)
            }
        }

        Log.i(TAG, "Creating new connection to mediator $pubkeyHex")
        connectionStatus[pubkeyHex] = ConnectionState.CONNECTING

        // Store keypair for potential reconnection
        val info = reconnectionInfo.getOrPut(pubkeyHex) {
            ReconnectionInfo(keyPair = keyPair)
        }.apply {
            this.keyPair = keyPair
        }

        // If this is a reconnection attempt (attemptCount > 0), explicitly close any
        // cached connection in the Go library to ensure we get a fresh connection.
        // This is critical after device hibernation when QUIC connections become stale.
        if (info.attemptCount > 0) {
            Log.d(TAG, "Reconnection attempt detected, closing any cached connection to $pubkeyHex")
            try {
                messenger.closeConnection(mediatorPubkey)
            } catch (e: Exception) {
                Log.w(TAG, "Error closing cached connection: $e")
            }
        }

        try {
            val connection = messenger.connect(mediatorPubkey)
            val client = MediatorClient(connection, keyPair, createGlobalListener(pubkeyHex), storage)

            clients[pubkeyHex] = client
            client.start()

            Log.i(TAG, "Connection to mediator $pubkeyHex created successfully")
            client

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create connection to mediator $pubkeyHex", e)
            connectionStatus[pubkeyHex] = ConnectionState.FAILED
            throw e
        }
    }

    /**
     * Returns the public key from current keyPair
     */
    fun getPublicKey(): ByteArray {
        return (keyPair.public as Ed25519PublicKeyParameters).encoded
    }

    /**
     * Get existing client for address, or null if not connected.
     */
    fun getClient(address: String): MediatorClient? = lock.read {
        clients[address]
    }

    /**
     * Get connection state for address.
     */
    fun getConnectionState(address: String): ConnectionState {
        return connectionStatus[address] ?: ConnectionState.DISCONNECTED
    }

    /**
     * Check if a mediator needs connection/reconnection.
     * Returns true if the mediator is not connected or failed.
     */
    fun needsConnection(mediatorPubkey: ByteArray): Boolean {
        val pubkeyHex = Hex.toHexString(mediatorPubkey)
        val state = connectionStatus[pubkeyHex] ?: return true // Not in status map = needs connection

        // Check if client is actually running
        val client = clients[pubkeyHex]
        if (client == null || !client.isRunning()) {
            return true // No client or not running = needs connection
        }

        // Connected state means we're good
        return state != ConnectionState.CONNECTED
    }

    /**
     * Register a listener for messages from a specific chat.
     * Multiple listeners can be registered per chat.
     */
    fun registerMessageListener(chatId: Long, listener: ChatMessageListener) {
        // API 23-compatible alternative to compute()
        // Create a new set if needed
        val newSet = mutableSetOf<ChatMessageListener>()
        val existingSet = chatListeners.putIfAbsent(chatId, newSet)

        // Use whichever set is in the map (existing or new)
        val setToUse = existingSet ?: newSet

        // Synchronize on the set itself when modifying
        synchronized(setToUse) {
            setToUse.add(listener)
        }

        Log.i(TAG, "Registered listener for chat $chatId, total: ${chatListeners[chatId]?.size}")
    }

    /**
     * Unregister a message listener for a chat.
     */
    fun unregisterMessageListener(chatId: Long, listener: ChatMessageListener) {
        chatListeners[chatId]?.remove(listener)
        if (chatListeners[chatId]?.isEmpty() == true) {
            chatListeners.remove(chatId)
        }
        Log.i(TAG, "Unregistered listener for chat $chatId")
    }

    /**
     * Register a global listener for invite pushes.
     */
    fun registerInviteListener(listener: InviteListener) {
        synchronized(inviteListeners) {
            inviteListeners.add(listener)
            Log.i(TAG, "Registered invite listener, total: ${inviteListeners.size}")
        }
    }

    /**
     * Unregister an invite listener.
     */
    fun unregisterInviteListener(listener: InviteListener) {
        synchronized(inviteListeners) {
            inviteListeners.remove(listener)
            Log.i(TAG, "Unregistered invite listener")
        }
    }

    /**
     * Disconnect from a specific mediator.
     */
    fun disconnect(address: String) = lock.write {
        clients[address]?.let { client ->
            Log.i(TAG, "Disconnecting from $address")
            client.stopClient()
            clients.remove(address)
            connectionStatus[address] = ConnectionState.DISCONNECTED
        }
    }

    /**
     * Disconnect from all mediators.
     */
    fun disconnectAll() = lock.write {
        Log.i(TAG, "Disconnecting from all mediators (${clients.size} connections)")
        clients.values.forEach { it.stopClient() }
        clients.clear()
        connectionStatus.clear()
    }

    /**
     * Get all active connections.
     */
    fun getActiveConnections(): Map<String, ConnectionState> {
        return connectionStatus.toMap()
    }

    /**
     * Mark a chat as successfully subscribed to its mediator.
     * Should be called after successful subscription response.
     */
    fun markChatSubscribed(chatId: Long) {
        chatSubscriptions[chatId] = true
        Log.d(TAG, "Chat $chatId marked as subscribed")
    }

    /**
     * Mark a chat as unsubscribed from its mediator.
     * Should be called on subscription failure or mediator disconnect.
     */
    fun markChatUnsubscribed(chatId: Long) {
        chatSubscriptions[chatId] = false
        Log.d(TAG, "Chat $chatId marked as unsubscribed")
    }

    /**
     * Get the current status of a group chat combining mediator connection state
     * with chat subscription state.
     *
     * @param chatId The chat ID
     * @param mediatorPubkey The mediator's public key
     * @return The combined GroupChatStatus
     */
    fun getGroupChatStatus(chatId: Long, mediatorPubkey: ByteArray): GroupChatStatus {
        val mediatorAddress = Hex.toHexString(mediatorPubkey)
        val mediatorState = connectionStatus[mediatorAddress] ?: ConnectionState.DISCONNECTED
        val isSubscribed = chatSubscriptions[chatId] ?: false

        val status = when {
            mediatorState == ConnectionState.CONNECTED && isSubscribed -> GroupChatStatus.SUBSCRIBED
            mediatorState == ConnectionState.CONNECTING -> GroupChatStatus.CONNECTING
            mediatorState == ConnectionState.CONNECTED && !isSubscribed -> GroupChatStatus.CONNECTING
            else -> GroupChatStatus.DISCONNECTED
        }

        Log.d(TAG, "getGroupChatStatus(chatId=$chatId): mediatorState=$mediatorState, isSubscribed=$isSubscribed -> $status")
        return status
    }

    /**
     * Schedules a reconnection attempt for a mediator with exponential backoff.
     * Only attempts reconnection if we're online and haven't exceeded max attempts.
     *
     * @param mediatorPubkey The mediator public key to reconnect to
     */
    private fun scheduleReconnect(mediatorPubkey: ByteArray) {
        val pubkeyHex = Hex.toHexString(mediatorPubkey)

        // Check if we're online
        if (!App.app.online) {
            Log.i(TAG, "Not attempting reconnect to $pubkeyHex - we're offline")
            return
        }

        // Check if there's already an active connection
        clients[pubkeyHex]?.let { existingClient ->
            if (existingClient.isRunning()) {
                Log.i(TAG, "Not scheduling reconnect to $pubkeyHex - already connected")
                return
            }
        }

        // Get or create reconnection info
        val info = reconnectionInfo.getOrPut(pubkeyHex) {
            ReconnectionInfo(keyPair = keyPair)
        }

        // Check if there's already a reconnection thread running
        if (info.reconnectThread != null && info.reconnectThread!!.isAlive) {
            Log.i(TAG, "Not scheduling reconnect to $pubkeyHex - reconnection already in progress")
            return
        }

        // Store keypair for future reconnection attempts
        info.keyPair = keyPair

        // Check if we should attempt reconnect
        if (!info.shouldAttemptReconnect()) {
            Log.w(TAG, "Max reconnection attempts reached for $pubkeyHex, stopping automatic reconnection")
            return
        }

        // Cancel any existing reconnect thread (should be redundant now, but kept for safety)
        info.cancelReconnect()

        // Calculate delay with exponential backoff
        val delay = info.getNextDelay()
        Log.i(TAG, "Scheduling reconnect to $pubkeyHex in ${delay}ms (attempt ${info.attemptCount + 1})")

        // Schedule reconnection attempt
        info.reconnectThread = thread(name = "MediatorReconnect-$pubkeyHex") {
            try {
                Thread.sleep(delay)

                // Check again if we're still online before attempting
                if (!App.app.online) {
                    Log.i(TAG, "Aborting reconnect to $pubkeyHex - went offline during delay")
                    info.reset()
                    info.reconnectThread = null
                    return@thread
                }

                info.incrementAttempt()
                Log.i(TAG, "Attempting reconnect to $pubkeyHex (attempt ${info.attemptCount})")

                // Clear reconnectThread before attempting connection to allow future reconnects
                info.reconnectThread = null

                try {
                    // Attempt to reconnect
                    // Note: Resubscription happens in onConnected callback, not here,
                    // because this thread will be interrupted by onConnected before we can resubscribe
                    val client = getOrCreateClient(mediatorPubkey)

                    if (client.isAlive) {
                        Log.i(TAG, "Successfully reconnected to $pubkeyHex")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Reconnection attempt ${info.attemptCount} failed for $pubkeyHex", e)

                    // Schedule next attempt if we haven't exceeded max attempts
                    if (info.shouldAttemptReconnect()) {
                        scheduleReconnect(mediatorPubkey)
                    } else {
                        Log.w(TAG, "Max reconnection attempts reached for $pubkeyHex")
                    }
                }
            } catch (_: InterruptedException) {
                Log.i(TAG, "Reconnect thread for $pubkeyHex was interrupted")
                info.reconnectThread = null
            }
        }
    }

    /**
     * Resubscribes to all chats on a specific mediator after reconnection.
     * This ensures we continue receiving messages after a disconnection.
     * Also triggers retry of undelivered messages after successful resubscription.
     */
    private fun resubscribeToChatsOnMediator(mediatorPubkey: ByteArray, client: MediatorClient) {
        thread(name = "ResubscribeChats") {
            try {
                // Get all chats on this mediator
                val allChats = storage.getGroupChatList()
                val chatsOnMediator = allChats.filter { it.mediatorPubkey.contentEquals(mediatorPubkey) }

                Log.i(TAG, "Resubscribing to ${chatsOnMediator.size} chats after reconnection")

                for (chat in chatsOnMediator) {
                    try {
                        val serverLastId = client.subscribe(chat.chatId)
                        Log.i(TAG, "Resubscribed to chat ${chat.chatId} (${chat.name}), server last ID: $serverLastId")

                        // Mark chat as subscribed for status badge
                        markChatSubscribed(chat.chatId)

                        // Note: Message listeners are already registered from initial connection,
                        // so we don't need to re-register them

                        // Trigger retry of undelivered messages and status broadcast after successful resubscription
                        reconnectionCallback?.onChatReconnected(chat.chatId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to resubscribe to chat ${chat.chatId}", e)
                        // Mark as unsubscribed on failure
                        markChatUnsubscribed(chat.chatId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resubscribing to chats", e)
            }
        }
    }

    /**
     * Creates a global listener that routes messages to per-chat listeners.
     */
    private fun createGlobalListener(address: String) = object : MediatorClient.MediatorListener {
        override fun onConnected() {
            Log.i(TAG, "Connected to mediator: $address")
            connectionStatus[address] = ConnectionState.CONNECTED

            // Check if there are any chats on this mediator that need subscription
            // This handles both reconnection and initial connection after app restart
            sleep(2000)
            try {
                val mediatorPubkey = Hex.decode(address)
                val client = clients[address]

                if (client != null) {
                    // Get all chats on this mediator
                    val allChats = storage.getGroupChatList()
                    val chatsOnMediator = allChats.filter { it.mediatorPubkey.contentEquals(mediatorPubkey) }

                    if (chatsOnMediator.isNotEmpty()) {
                        Log.i(TAG, "Found ${chatsOnMediator.size} chats on mediator $address, subscribing...")
                        resubscribeToChatsOnMediator(mediatorPubkey, client)
                    } else {
                        Log.i(TAG, "No chats found on mediator $address")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to subscribe chats after connection to $address", e)
            }

            // Reset reconnection state on successful connection
            reconnectionInfo[address]?.let { info ->
                info.reset()
                info.cancelReconnect()
                Log.i(TAG, "Reset reconnection state for $address")
            }

            // Notify callback about mediator state change for status badge updates
            try {
                val mediatorPubkey = Hex.decode(address)
                reconnectionCallback?.onMediatorStateChanged(mediatorPubkey)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode mediator address for callback: $address", e)
            }
        }

        override fun onPushMessage(chatId: Long, messageId: Long, guid: Long, timestamp: Long, author: ByteArray, data: ByteArray) {
            Log.d(TAG, "Received message for chat $chatId (msgId=$messageId, guid=$guid)")

            // Dispatch to all registered listeners for this chat
            chatListeners[chatId]?.forEach { listener ->
                try {
                    listener.onChatMessage(chatId, messageId, guid, timestamp, author, data)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in chat listener for chat $chatId", e)
                }
            }
        }

        override fun onSystemMessage(chatId: Long, messageId: Long, guid: Long, timestamp: Long, body: ByteArray) {
            if (body.isEmpty()) {
                Log.w(TAG, "Got empty system message!")
                return
            }
            val systemMessage = body[0]
            Log.i(TAG, "Got system message: $systemMessage for chat $chatId")

            // Parse system message to handle member management
            val sysMsg = parseSystemMessage(body)

            // Handle message deletion - this is an invisible system message
            if (sysMsg is SystemMessage.MessageDeleted) {
                Log.i(TAG, "Deleting message with guid ${sysMsg.deletedGuid} from chat $chatId")
                storage.deleteGroupMessageByGuid(chatId, sysMsg.deletedGuid)
                // Don't save this system message to DB - it's invisible
                return
            }

            val lastMemberSync = storage.getGroupChatTimestamp(chatId)

            // Only process member-affecting system messages that occurred after the last member sync
            // This prevents race conditions where old "user left" messages arrive during sync
            // and incorrectly remove recently re-added members
            if (timestamp >= lastMemberSync) {
                when (sysMsg) {
                    is SystemMessage.UserLeft -> {
                        // User left - delete from members table
                        storage.deleteGroupMember(chatId, sysMsg.user)
                        Log.i(TAG, "Removed member from chat $chatId due to UserLeft system message")
                    }
                    else -> {
                        // Other system messages don't require immediate action here
                        // (UserAdded/UserBanned are handled via member info sync)
                    }
                }
            } else {
                Log.d(TAG, "Ignoring old system message (timestamp=$timestamp < lastMemberSync=$lastMemberSync)")
            }

            // System messages are always incoming (from mediator) with type 1000
            // Use mediator as the author
            storage.addGroupMessage(
                chatId = chatId,
                serverMsgId = messageId,
                guid = guid,
                author = getDefaultMediatorPubkey(),
                timestamp = timestamp,
                type = 1000, // System message type
                system = true,
                data = body
            )
        }

        override fun onPushInvite(
            inviteId: Long,
            chatId: Long,
            fromPubkey: ByteArray,
            timestamp: Long,
            chatName: String,
            chatDescription: String,
            chatAvatar: ByteArray?,
            encryptedData: ByteArray
        ) {
            Log.i(TAG, "Received invite for chat $chatId from ${Hex.toHexString(fromPubkey).take(8)}...")

            // Dispatch to all registered invite listeners
            synchronized(inviteListeners) {
                inviteListeners.forEach { listener ->
                    try {
                        listener.onInviteReceived(
                            inviteId,
                            chatId,
                            fromPubkey,
                            timestamp,
                            chatName,
                            chatDescription,
                            chatAvatar,
                            encryptedData
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in invite listener", e)
                    }
                }
            }
        }

        override fun onMemberInfoUpdate(chatId: Long, memberPubkey: ByteArray, encryptedInfo: ByteArray?, timestamp: Long) {
            Log.i(TAG, "Member info update for ${Hex.toHexString(memberPubkey).take(8)}... in chat $chatId, timestamp=$timestamp")

            try {
                // Get chat info to retrieve shared key
                val chatInfo = storage.getGroupChat(chatId)
                if (chatInfo == null) {
                    Log.e(TAG, "Cannot update member info: chat $chatId not found")
                    return
                }

                // If no encrypted info provided, skip update
                if (encryptedInfo == null) {
                    Log.d(TAG, "Member info update has no encrypted data, skipping")
                    return
                }

                // Decrypt member info blob using chat's shared key
                val plaintext = try {
                    GroupChatCrypto.decryptMessage(encryptedInfo, chatInfo.sharedKey)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decrypt member info for chat $chatId", e)
                    return
                }

                // Parse member info blob: [nicknameLen(u16)][nickname][infoLen(u16)][info][avatarLen(u32)][avatar]
                val buffer = java.nio.ByteBuffer.wrap(plaintext)

                // Read nickname
                val nicknameLen = buffer.getShort().toInt() and 0xFFFF
                val nicknameBytes = ByteArray(nicknameLen)
                buffer.get(nicknameBytes)
                val nickname = String(nicknameBytes, Charsets.UTF_8)

                // Read info text
                val infoLen = buffer.getShort().toInt() and 0xFFFF
                val infoBytes = ByteArray(infoLen)
                buffer.get(infoBytes)
                val info = String(infoBytes, Charsets.UTF_8)

                // Read avatar
                val avatarLen = buffer.getInt()
                val avatar = if (avatarLen > 0) {
                    ByteArray(avatarLen).also { buffer.get(it) }
                } else {
                    null
                }

                Log.i(TAG, "Parsed member info: nickname='$nickname', infoLen=${info.length}, avatarLen=$avatarLen")

                // Update storage
                storage.updateGroupMemberInfo(chatId, memberPubkey, nickname, info, avatar)
                Log.i(TAG, "Updated member info in storage for chat $chatId")

            } catch (e: Exception) {
                Log.e(TAG, "Error processing member info update for chat $chatId", e)
            }
        }

        override fun onMemberOnlineStatusChanged(
            chatId: Long,
            memberPubkey: ByteArray,
            isOnline: Boolean,
            timestamp: Long
        ) {
            val pubkeyHex = Hex.toHexString(memberPubkey).take(8)
            Log.i(TAG, "Member online status changed in chat $chatId: $pubkeyHex... -> ${if (isOnline) "online" else "offline"} @ $timestamp")

            try {
                // Don't track current user's own status
                if (memberPubkey.contentEquals(getPublicKey())) {
                    Log.d(TAG, "Ignoring own online status change event")
                    return
                }

                // Update database with new status and last_seen timestamp
                val lastSeenTimestamp = if (isOnline) 0L else timestamp
                storage.updateGroupMemberOnlineStatus(
                    chatId = chatId,
                    pubkey = memberPubkey,
                    online = isOnline,
                    lastSeen = lastSeenTimestamp
                )

                Log.d(TAG, "Updated member online status in storage for chat $chatId")
            } catch (e: Exception) {
                Log.e(TAG, "Error processing member online status change for chat $chatId", e)
            }
        }

        override fun onMemberInfoRequest(chatId: Long, lastUpdate: Long): MediatorClient.MemberInfoResponse? {
            val info = infoProvider.getMyInfo(lastUpdate) ?: return null
            val chatInfo = storage.getGroupChat(chatId) ?: return null

            Log.i(TAG, "onMemberInfoRequest: Returning member info for chat $chatId")
            return MediatorClient.MemberInfoResponse(info.nickname, info.info, info.avatar, chatInfo.sharedKey, info.time)
        }

        override fun onDisconnected(error: Exception) {
            Log.w(TAG, "Disconnected from mediator: $address", error)
            connectionStatus[address] = ConnectionState.FAILED

            lock.write {
                clients.remove(address)
            }

            // Mark all chats on this mediator as unsubscribed
            try {
                val mediatorPubkey = Hex.decode(address)
                val allChats = storage.getGroupChatList()
                val chatsOnMediator = allChats.filter { it.mediatorPubkey.contentEquals(mediatorPubkey) }

                Log.i(TAG, "Marking ${chatsOnMediator.size} chats as unsubscribed after disconnect")
                for (chat in chatsOnMediator) {
                    markChatUnsubscribed(chat.chatId)
                }

                // Notify callback about mediator state change for status badge updates
                reconnectionCallback?.onMediatorStateChanged(mediatorPubkey)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process disconnect for $address", e)
            }

            // Trigger automatic reconnection if we're online
            if (App.app.online) {
                Log.i(TAG, "We're online, scheduling reconnection to $address")

                // Get keypair from reconnection info or storage
                val keyPair = reconnectionInfo[address]?.keyPair
                if (keyPair != null) {
                    // Convert hex address back to byte array for reconnection
                    val mediatorPubkey = try {
                        Hex.decode(address)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode mediator pubkey: $address", e)
                        return
                    }

                    // Schedule reconnection with exponential backoff
                    scheduleReconnect(mediatorPubkey)
                } else {
                    Log.w(TAG, "No keypair found for $address, cannot schedule reconnection")
                }
            } else {
                Log.i(TAG, "We're offline, not scheduling reconnection to $address")
            }
        }
    }

    /**
     * Interface for listening to chat messages.
     */
    interface ChatMessageListener {
        fun onChatMessage(chatId: Long, messageId: Long, guid: Long, timestamp: Long, author: ByteArray, data: ByteArray)
    }

    /**
     * Interface for listening to invite pushes.
     */
    interface InviteListener {
        fun onInviteReceived(
            inviteId: Long,
            chatId: Long,
            fromPubkey: ByteArray,
            timestamp: Long,
            chatName: String,
            chatDescription: String,
            chatAvatar: ByteArray?,
            encryptedData: ByteArray
        )
    }

    /**
     * Callback interface for mediator reconnection events.
     * Used to trigger message retry after successful reconnection.
     */
    interface ReconnectionCallback {
        /**
         * Called when a chat has been successfully resubscribed after mediator reconnection.
         * This is the ideal time to retry sending any undelivered messages for this chat.
         *
         * @param chatId The ID of the chat that was resubscribed
         */
        fun onChatReconnected(chatId: Long)

        /**
         * Called when a mediator's connection state changes (connected/disconnected).
         * This triggers status updates for all group chats using this mediator.
         *
         * @param mediatorPubkey The public key of the mediator whose state changed
         */
        fun onMediatorStateChanged(mediatorPubkey: ByteArray)
    }
}