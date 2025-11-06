package com.revertron.mimir.net

import android.util.Log
import com.revertron.mimir.storage.SqlStorage
import com.revertron.mimir.yggmobile.Messenger
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
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
class MediatorManager(private val messenger: Messenger, private val storage: SqlStorage, private val infoProvider: InfoProvider) {

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

    // Map: chatId -> Set of message listeners
    private val chatListeners = ConcurrentHashMap<Long, MutableSet<ChatMessageListener>>()

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
     * Get or create a MediatorClient for the given mediator public key.
     * Reuses existing connection if available.
     *
     * @param mediatorPubkey The mediator's public key as ByteArray
     * @param keyPair User's key pair for authentication
     */
    fun getOrCreateClient(mediatorPubkey: ByteArray, keyPair: AsymmetricCipherKeyPair): MediatorClient = lock.write {
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
        chatListeners.compute(chatId) { _, existing ->
            (existing ?: mutableSetOf()).apply { add(listener) }
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
     * Creates a global listener that routes messages to per-chat listeners.
     */
    private fun createGlobalListener(address: String) = object : MediatorClient.MediatorListener {
        override fun onConnected() {
            Log.i(TAG, "Connected to mediator: $address")
            connectionStatus[address] = ConnectionState.CONNECTED
        }

        override fun onPushMessage(chatId: Long, messageId: Long, guid: Long, author: ByteArray, data: ByteArray) {
            Log.d(TAG, "Received message for chat $chatId (msgId=$messageId, guid=$guid)")

            // Dispatch to all registered listeners for this chat
            chatListeners[chatId]?.forEach { listener ->
                try {
                    listener.onChatMessage(chatId, messageId, guid, author, data)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in chat listener for chat $chatId", e)
                }
            }
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

        override fun onMemberInfoRequest(chatId: Long, lastUpdate: Long): MediatorClient.MemberInfoResponse? {
            val info = infoProvider.getMyInfo(lastUpdate) ?: return null
            val chatInfo = storage.getGroupChat(chatId) ?: return null
            return MediatorClient.MemberInfoResponse(info.nickname, info.info, info.avatar, chatInfo.sharedKey, info.time)
        }

        override fun onDisconnected(error: Exception) {
            Log.w(TAG, "Disconnected from mediator: $address", error)
            connectionStatus[address] = ConnectionState.FAILED

            lock.write {
                clients.remove(address)
            }

            // TODO: Implement reconnection logic
            // For now, listeners need to handle reconnection themselves
        }
    }

    /**
     * Interface for listening to chat messages.
     */
    interface ChatMessageListener {
        fun onChatMessage(chatId: Long, messageId: Long, guid: Long, author: ByteArray, data: ByteArray)
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
}