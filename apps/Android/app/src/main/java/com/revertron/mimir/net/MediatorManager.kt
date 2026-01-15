package com.revertron.mimir.net

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.revertron.mimir.sec.GroupChatCrypto
import com.revertron.mimir.storage.SqlStorage
import com.revertron.mimir.yggmobile.Messenger
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
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
    private val reconnectionCallback: ReconnectionCallback? = null,
    private val context: Context
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

    // PeerManager for checking online state
    private var peerManager: PeerManager? = null

    // Handler for UI thread operations
    private val handler = Handler(Looper.getMainLooper())

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
            private const val MAX_DELAY_MS = 120000L // 120 seconds
            private const val MAX_ATTEMPTS = 300 // After 300 attempts, stop and wait for manual reconnect
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

    /**
     * Set the PeerManager instance.
     * Should be called after both MediatorManager and PeerManager are created.
     */
    fun setPeerManager(peerManager: PeerManager) {
        this.peerManager = peerManager
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
            val client = MediatorClient(context, connection, keyPair, createGlobalListener(pubkeyHex), storage)

            // Set PeerManager reference if available
            peerManager?.let { client.setPeerManager(it) }

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
        if (!(peerManager?.isOnline() ?: false)) {
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
                sleep(delay)

                // Check again if we're still online before attempting
                if (!(peerManager?.isOnline() ?: false)) {
                    // Schedule next attempt if we haven't exceeded max attempts
                    if (info.shouldAttemptReconnect()) {
                        scheduleReconnect(mediatorPubkey)
                    } else {
                        Log.w(TAG, "Max reconnection attempts reached for $pubkeyHex")
                    }
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
     * Refreshes the complete member list from the mediator to ensure local consistency.
     * Called after membership changes (user left, banned, etc.) to verify the complete member list.
     *
     * @param chatId The chat ID to refresh members for
     * @param mediatorAddress The mediator address (hex string)
     */
    private fun refreshMemberListFromMediator(chatId: Long, mediatorAddress: String) {
        thread(name = "RefreshMembers-$chatId") {
            try {
                // Get the client for this mediator
                val client = clients[mediatorAddress]
                if (client == null || !client.isRunning()) {
                    Log.w(TAG, "Cannot refresh member list for chat $chatId: mediator not connected")
                    return@thread
                }

                Log.i(TAG, "Refreshing member list from mediator for chat $chatId")

                // Fetch complete member list from mediator
                val members = client.getMembers(chatId)
                Log.i(TAG, "Fetched ${members.size} member(s) from mediator for chat $chatId")

                // Update database with fresh member list
                for (member in members) {
                    try {
                        storage.updateGroupMemberStatus(
                            chatId = chatId,
                            pubkey = member.pubkey,
                            permissions = member.permissions,
                            online = member.online
                        )

                        // Update last seen if available
                        if (member.lastSeen > 0) {
                            storage.updateGroupMemberOnlineStatus(
                                chatId = chatId,
                                pubkey = member.pubkey,
                                online = member.online,
                                lastSeen = member.lastSeen
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating member status for chat $chatId", e)
                    }
                }

                Log.i(TAG, "Successfully refreshed member list for chat $chatId")

            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing member list for chat $chatId", e)
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

                        // Refresh member list from mediator to ensure consistency
                        refreshMemberListFromMediator(chatId, address)
                    }
                    is SystemMessage.UserBanned -> {
                        // User banned - refresh member list to get updated permissions/status
                        Log.i(TAG, "User banned in chat $chatId, refreshing member list")
                        refreshMemberListFromMediator(chatId, address)
                    }
                    else -> {
                        // Other system messages don't require immediate action here
                        // (UserAdded is handled via member info sync)
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
                // Check if this is our own status change
                if (memberPubkey.contentEquals(getPublicKey())) {
                    // If mediator is reporting we're offline, we need to re-subscribe
                    if (!isOnline) {
                        Log.w(TAG, "Mediator reports we are offline in chat $chatId - re-subscribing to maintain connection")

                        // Get the client for this mediator
                        val client = clients[address]
                        if (client != null && client.isRunning()) {
                            // Re-subscribe in background thread to avoid blocking
                            thread(name = "ResubscribeChat-$chatId") {
                                try {
                                    val serverLastId = client.subscribe(chatId)
                                    Log.i(TAG, "Successfully re-subscribed to chat $chatId after offline notification, server last ID: $serverLastId")

                                    // Mark chat as subscribed
                                    markChatSubscribed(chatId)

                                    // Notify callback about successful reconnection
                                    reconnectionCallback?.onChatReconnected(chatId)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to re-subscribe to chat $chatId after offline notification", e)
                                    markChatUnsubscribed(chatId)
                                }
                            }
                        } else {
                            Log.w(TAG, "Cannot re-subscribe to chat $chatId: mediator client not available")
                            markChatUnsubscribed(chatId)
                        }
                    } else {
                        Log.d(TAG, "Ignoring own online status change event (we're already online)")
                    }
                    return
                }

                // Update database with new status and last_seen timestamp for other members
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
            if (peerManager?.isOnline() == true) {
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
     * Connects to all known mediators and subscribes to all saved group chats.
     * Called on service start to establish always-on connections.
     */
    fun connectAndSubscribeToAllChats(globalMessageListener: ChatMessageListener?, statusBroadcaster: (chatId: Long) -> Unit) {
        try {
            sleep(3000)
            Log.i(TAG, "Checking mediator connections...")

            // Get all unique known mediators from saved chats
            val knownMediators = storage.getKnownMediators().toMutableList()

            // Always include default mediator for listening to invites
            val defaultMediator = getDefaultMediatorPubkey()
            if (!knownMediators.any { it.contentEquals(defaultMediator) }) {
                knownMediators.add(defaultMediator)
                Log.i(TAG, "Added default mediator for invite listening")
            }

            Log.i(TAG, "Found ${knownMediators.size} mediators to connect to")

            // Filter mediators that need connection
            val mediatorsToConnect = knownMediators.filter { mediatorPubkey ->
                needsConnection(mediatorPubkey)
            }

            if (mediatorsToConnect.isEmpty()) {
                Log.i(TAG, "All mediators already connected, skipping")
                return
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
                        val client = getOrCreateClient(mediatorPubkey)

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
                                    registerMessageListener(chat.chatId, it)
                                }

                                // Mark chat as subscribed and broadcast status for badge update
                                markChatSubscribed(chat.chatId)
                                handler.post {
                                    statusBroadcaster(chat.chatId)
                                }

                                Thread {
                                    // Sync missed messages from server first
                                    client.syncMissedMessages(chat.chatId, storage, context, serverLastId)
                                    // Then re-send any undelivered messages
                                    resendUndeliveredMessages(chat.chatId)
                                }.start()
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to subscribe to chat ${chat.chatId} on mediator ${mediatorHex.take(8)}", e)
                                // Mark chat as unsubscribed on failure
                                markChatUnsubscribed(chat.chatId)
                                handler.post {
                                    statusBroadcaster(chat.chatId)
                                }
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

    /**
     * Subscribes to a single group chat on its mediator.
     * Handles subscription, message sync, resend, and listener registration.
     */
    fun subscribeToChat(
        chatId: Long,
        listener: ChatMessageListener,
        statusBroadcaster: (chatId: Long) -> Unit
    ): Boolean {
        return try {
            // Get chat info to find mediator
            val chatInfo = storage.getGroupChat(chatId)
            if (chatInfo == null) {
                Log.e(TAG, "Chat $chatId not found")
                return false
            }

            // Get or create client for this mediator
            val client = getOrCreateClient(chatInfo.mediatorPubkey)

            Thread {
                try {
                    // Subscribe to chat
                    val serverLastId = client.subscribe(chatId)
                    Log.i(TAG, "Subscribed to chat $chatId (server last ID: $serverLastId)")

                    // Register listener
                    registerMessageListener(chatId, listener)

                    // Mark as subscribed
                    markChatSubscribed(chatId)
                    handler.post {
                        statusBroadcaster(chatId)
                    }

                    // Sync missed messages
                    client.syncMissedMessages(chatId, storage, context, serverLastId)

                    // Resend undelivered messages
                    resendUndeliveredMessages(chatId)

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to subscribe to chat $chatId", e)
                    markChatUnsubscribed(chatId)
                    handler.post {
                        statusBroadcaster(chatId)
                    }
                    broadcastMediatorError("subscribe", e.message)
                }
            }.start()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error in subscribeToChat", e)
            false
        }
    }

    /**
     * Resends messages that failed to deliver for a specific chat.
     * Handles serialization, encryption, and transmission of undelivered messages.
     */
    fun resendUndeliveredMessages(chatId: Long) {
        Thread {
            try {
                val undelivered = storage.getUndeliveredGroupMessages(chatId)

                if (undelivered.isEmpty()) {
                    Log.d(TAG, "No undelivered messages for chat $chatId")
                    return@Thread
                }

                Log.i(TAG, "Retrying ${undelivered.size} undelivered message(s) for chat $chatId")

                // Get chat info for encryption and mediator lookup
                val chatInfo = storage.getGroupChat(chatId)
                if (chatInfo == null) {
                    Log.e(TAG, "Chat $chatId not found in storage, cannot resend messages")
                    return@Thread
                }

                // Get or create client for this chat's mediator
                val client = getOrCreateClient(chatInfo.mediatorPubkey)

                // Get files directory for attachment serialization
                val filesDir = context.filesDir
                val filePath = java.io.File(filesDir, "files").absolutePath

                for (msg in undelivered) {
                    try {
                        // Convert ByteArray data back to String for Message object
                        val messageData = String(msg.data ?: ByteArray(0))

                        Log.d(TAG, "Retrying message guid=${msg.guid}, type=${msg.type}")

                        // Serialize message for wire transmission
                        val baos = java.io.ByteArrayOutputStream()
                        val dos = java.io.DataOutputStream(baos)

                        val message = Message(
                            guid = msg.guid,
                            replyTo = msg.replyTo,
                            sendTime = msg.timestamp,
                            editTime = 0,
                            type = msg.type,
                            data = messageData.toByteArray()
                        )

                        // Serialize - for attachments, writeMessage needs file path to read file
                        val messageFilePath = if (msg.type == 1 || msg.type == 3) filePath else ""
                        com.revertron.mimir.net.writeMessage(dos, message, messageFilePath)

                        // Encrypt the serialized message with chat's shared key
                        val encryptedData = GroupChatCrypto.encryptMessage(baos.toByteArray(), chatInfo.sharedKey)

                        // Send to mediator
                        val (messageId, newGuid) = client.sendMessage(chatId, msg.guid, msg.timestamp, encryptedData)
                        Log.i(TAG, "Retried message sent with ID: $messageId, guid = ${msg.guid} ($newGuid)")

                        // Update server message ID for later sync
                        storage.updateGroupMessageServerId(chatId, msg.guid, messageId)

                        // Update GUID if server changed it
                        if (newGuid != 0L && newGuid != msg.guid) {
                            storage.changeGroupMessageGuid(chatId, msg.guid, newGuid)
                        }

                        // Mark message as delivered after successful send
                        storage.setGroupMessageDelivered(chatId, msg.guid, true)

                        // Broadcast success for UI update
                        val broadcastIntent = Intent("ACTION_MEDIATOR_MESSAGE_SENT").apply {
                            putExtra("chat_id", chatId)
                            putExtra("message_id", messageId)
                            putExtra("guid", msg.guid)
                        }
                        LocalBroadcastManager.getInstance(context).sendBroadcast(broadcastIntent)

                        // Small delay between retries to avoid overwhelming the mediator
                        sleep(100)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to retry message guid=${msg.guid}: ${e.message}", e)
                        // Continue with next message even if this one fails
                    }
                }

                Log.i(TAG, "Completed retrying undelivered messages for chat $chatId")
            } catch (e: Exception) {
                Log.e(TAG, "Error in resendUndeliveredMessages for chat $chatId", e)
            }
        }.start()
    }

    /**
     * Creates a new group chat on the mediator.
     */
    fun createChat(
        name: String,
        description: String,
        avatar: ByteArray?,
        mediatorPubkey: ByteArray,
        listener: ChatMessageListener,
        successBroadcaster: (chatId: Long) -> Unit,
        errorBroadcaster: (operation: String, message: String?) -> Unit
    ) {
        Thread {
            try {
                // Get or create client for this mediator
                val client = getOrCreateClient(mediatorPubkey)

                // Create chat on mediator (returns chat ID)
                val chatId = client.createChat(name, description, avatar)
                Log.i(TAG, "Chat created on mediator: $chatId")

                // Generate shared encryption key for this chat
                val sharedKey = GroupChatCrypto.generateSharedKey()

                // Get our public key
                val ourPubkey = getPublicKey()

                // Save chat to storage
                storage.saveGroupChat(
                    chatId,
                    name,
                    description,
                    null, // avatarPath - will be set separately if avatar is provided
                    mediatorPubkey,
                    ourPubkey,
                    sharedKey
                )

                // Save avatar if provided
                avatar?.let {
                    storage.updateGroupChatAvatar(chatId, it)
                }

                Log.i(TAG, "Chat $chatId saved to storage")

                // Subscribe to the new chat
                val serverLastId = client.subscribe(chatId)
                Log.i(TAG, "Subscribed to new chat $chatId (server last ID: $serverLastId)")

                // Register message listener
                registerMessageListener(chatId, listener)

                // Mark as subscribed
                markChatSubscribed(chatId)

                // Broadcast success
                handler.post {
                    successBroadcaster(chatId)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error creating chat", e)
                errorBroadcaster("create_chat", e.message ?: "Unknown error")
            }
        }.start()
    }

    /**
     * Deletes a group chat (owner only).
     */
    fun deleteGroupChat(chatId: Long): Boolean {
        return try {
            // Get chat info
            val chatInfo = storage.getGroupChat(chatId)
            if (chatInfo == null) {
                Log.e(TAG, "Chat $chatId not found")
                return false
            }

            // Get client for this mediator
            val client = getOrCreateClient(chatInfo.mediatorPubkey)

            // Delete chat on mediator first
            val success = client.deleteChat(chatId)

            if (success) {
                Log.i(TAG, "Chat $chatId deleted on mediator")
                // Delete from local storage
                storage.deleteGroupChat(chatId)
                Log.i(TAG, "Chat $chatId deleted from storage")
                true
            } else {
                Log.e(TAG, "Failed to delete chat $chatId on mediator")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting chat", e)
            false
        }
    }

    /**
     * Leaves a group chat.
     */
    fun leaveGroupChat(chatId: Long): Boolean {
        return try {
            // Get chat info
            val chatInfo = storage.getGroupChat(chatId)
            if (chatInfo == null) {
                Log.e(TAG, "Chat $chatId not found")
                return false
            }

            // Get client for this mediator
            val client = getOrCreateClient(chatInfo.mediatorPubkey)

            // Leave chat on mediator
            client.leaveChat(chatId)
            Log.i(TAG, "Left chat $chatId on mediator")

            // Delete from local storage
            storage.deleteGroupChat(chatId)
            Log.i(TAG, "Chat $chatId deleted from storage")

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error leaving chat", e)
            false
        }
    }

    /**
     * Sends a group chat invitation to a specific user.
     */
    fun sendInviteToGroupChat(chatId: Long, recipientPubkey: ByteArray): Boolean {
        return try {
            // Get chat info to retrieve shared key
            val chatInfo = storage.getGroupChat(chatId)
            if (chatInfo == null) {
                Log.e(TAG, "Chat $chatId not found")
                return false
            }

            // Encrypt shared key for recipient
            val encryptedKey = GroupChatCrypto.encryptSharedKey(chatInfo.sharedKey, recipientPubkey)

            // Send invite via mediator
            val client = getOrCreateClient(chatInfo.mediatorPubkey)
            client.sendInvite(chatId, recipientPubkey, encryptedKey)

            Log.i(TAG, "Invite sent for chat $chatId to ${Hex.toHexString(recipientPubkey).take(8)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending invite", e)
            false
        }
    }

    /**
     * Bans a user from a group chat.
     */
    fun banUserFromGroupChat(chatId: Long, userPubkey: ByteArray): Boolean {
        return try {
            // Get chat info
            val chatInfo = storage.getGroupChat(chatId)
            if (chatInfo == null) {
                Log.e(TAG, "Chat $chatId not found")
                return false
            }

            // Get client for this mediator
            val client = getOrCreateClient(chatInfo.mediatorPubkey)

            // Ban user (deleteUser command)
            client.deleteUser(chatId, userPubkey)
            Log.i(TAG, "User banned from chat $chatId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error banning user", e)
            false
        }
    }

    /**
     * Changes a member's permissions in a group chat.
     */
    fun changeMemberRole(chatId: Long, userPubkey: ByteArray, newPermissions: Byte): Boolean {
        return try {
            // Get chat info
            val chatInfo = storage.getGroupChat(chatId)
            if (chatInfo == null) {
                Log.e(TAG, "Chat $chatId not found")
                return false
            }

            // Get client for this mediator
            val client = getOrCreateClient(chatInfo.mediatorPubkey)

            // Change member status (convert Byte to Int as required by changeMemberStatus)
            client.changeMemberStatus(chatId, userPubkey, newPermissions.toInt() and 0xFF)
            Log.i(TAG, "Changed permissions for user in chat $chatId to $newPermissions")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error changing member role", e)
            false
        }
    }

    /**
     * Broadcasts mediator error to UI.
     */
    private fun broadcastMediatorError(operation: String, message: String?) {
        val intent = Intent("ACTION_MEDIATOR_ERROR").apply {
            putExtra("operation", operation)
            putExtra("message", message ?: "Unknown error")
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
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