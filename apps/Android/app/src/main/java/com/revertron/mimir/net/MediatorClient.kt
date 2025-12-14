package com.revertron.mimir.net

import android.util.Log
import com.revertron.mimir.App
import com.revertron.mimir.sec.GroupChatCrypto
import com.revertron.mimir.sec.Sign
import com.revertron.mimir.storage.SqlStorage
import com.revertron.mimir.yggmobile.Connection
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.byteArrayOf
import kotlin.concurrent.thread

/**
 * Thread-based mediator client maintaining a single connection to the server.
 * Verified against mediator.go server code (frame shapes, commands, payload orders).
 *
 * Important protocol notes:
 * - Send a single 0x00 byte immediately after connect (proto selector).
 * - Frame: [version:1][cmd:1][reqId:2][len:4][payload]
 * - Response: [status:1][reqId:2][len:4][payload]
 * - Push message: status=OK, reqId=0x34, payload=[msgId(u64)][guid(u64)][len(u32)][blob]
 *   (No chatId present in push; if subscribed to several chats, the server protocol does not disambiguate.)
 */
class MediatorClient(
    private val connection: Connection,
    private val keyPair: AsymmetricCipherKeyPair,
    private val listener: MediatorListener,
    private val storage: SqlStorage
) : Thread(TAG) {

    companion object {
        private const val TAG = "MediatorClient"

        // Protocol constants (verified in mediator.go)
        private const val VERSION: Byte = 1
        private const val PROTO_CLIENT: Byte = 0x00

        private const val STATUS_OK: Int = 0x00
        private const val STATUS_ERR: Int = 0x01

        // System event codes (mediator-generated system messages)
        // TODO implement reaction to these messages
        const val SYS_USER_ADDED: Byte = 0x01
        const val SYS_USER_ENTERED: Byte = 0x02  // reserved
        const val SYS_USER_LEFT: Byte = 0x03
        const val SYS_USER_BANNED: Byte = 0x04
        const val SYS_CHAT_DELETED: Byte = 0x05
        const val SYS_CHAT_INFO_CHANGE: Byte = 0x06
        const val SYS_PERMS_CHANGED: Byte = 0x07

        // Command codes (verified in mediator.go)
        private const val CMD_GET_NONCE: Int = 0x01
        private const val CMD_AUTH: Int = 0x02
        private const val CMD_PING: Int = 0x03
        private const val CMD_CREATE_CHAT: Int = 0x10
        private const val CMD_DELETE_CHAT: Int = 0x11
        private const val CMD_ADD_USER: Int = 0x20
        private const val CMD_DELETE_USER: Int = 0x21
        private const val CMD_LEAVE_CHAT: Int = 0x22
        private const val CMD_GET_USER_CHATS: Int = 0x23
        private const val CMD_SEND_MESSAGE: Int = 0x30
        private const val CMD_DELETE_MESSAGE: Int = 0x31
        private const val CMD_GOT_MESSAGE: Int = 0x32 // appears in response.reqId for pushes
        private const val CMD_GET_LAST_MESSAGE_ID: Int = 0x33
        private const val CMD_SUBSCRIBE: Int = 0x35
        private const val CMD_GET_MESSAGES_SINCE: Int = 0x36
        private const val CMD_SEND_INVITE: Int = 0x40
        private const val CMD_GOT_INVITE: Int = 0x41 // appears in response.reqId for invite pushes
        private const val CMD_INVITE_RESPONSE: Int = 0x42 // client accepts/rejects invite
        private const val CMD_UPDATE_MEMBER_INFO: Int = 0x50
        private const val CMD_REQUEST_MEMBER_INFO: Int = 0x51 // mediator asks for member info
        private const val CMD_GET_MEMBERS_INFO: Int = 0x52 // client requests all members info and membership
        private const val CMD_GET_MEMBERS: Int = 0x53 // client requests all member pubkeys with permissions and online status
        private const val CMD_GOT_MEMBER_INFO: Int = 0x54 // mediator push: member info updated

        // Timeouts
        private const val REQ_TIMEOUT_MS: Long = 10_000
        private const val PING_DEADLINE_MS: Long = 120_000 // Ping every 2 minutes, QUIC timeout is 5 minutes

        // TLV Tag constants (purpose-based)
        private const val TAG_PUBKEY: Byte = 0x01.toByte()
        private const val TAG_SIGNATURE: Byte = 0x02.toByte()
        private const val TAG_NONCE: Byte = 0x03.toByte()
        private const val TAG_COUNTER: Byte = 0x04.toByte()

        private const val TAG_CHAT_ID: Byte = 0x10.toByte()
        private const val TAG_MESSAGE_ID: Byte = 0x11.toByte()
        private const val TAG_MESSAGE_GUID: Byte = 0x12.toByte()
        private const val TAG_INVITE_ID: Byte = 0x13.toByte()
        private const val TAG_SINCE_ID: Byte = 0x14.toByte()
        private const val TAG_USER_PUBKEY: Byte = 0x15.toByte()

        private const val TAG_CHAT_NAME: Byte = 0x20.toByte()
        private const val TAG_CHAT_DESC: Byte = 0x21.toByte()
        private const val TAG_CHAT_AVATAR: Byte = 0x22.toByte()
        private const val TAG_MESSAGE_BLOB: Byte = 0x23.toByte()
        private const val TAG_MEMBER_INFO: Byte = 0x24.toByte()
        private const val TAG_INVITE_DATA: Byte = 0x25.toByte()

        private const val TAG_LIMIT: Byte = 0x30.toByte()
        private const val TAG_COUNT: Byte = 0x31.toByte()
        private const val TAG_TIMESTAMP: Byte = 0x32.toByte()
        private const val TAG_PERMS: Byte = 0x33.toByte()
        private const val TAG_ONLINE: Byte = 0x34.toByte()
        private const val TAG_ACCEPTED: Byte = 0x35.toByte()
        private const val TAG_LAST_UPDATE: Byte = 0x36.toByte()
    }

    private val rng = SecureRandom()
    private val pubkey: ByteArray =
        (keyPair.public as Ed25519PublicKeyParameters).encoded

    // Mediator's public key (extracted from connection)
    private val mediatorPubkey: ByteArray = connection.publicKey()

    @Volatile
    private var running = true

    // Request → future map
    private val pending = ConcurrentHashMap<Short, WaitBox<Response>>()

    // Single write lock to serialize frames
    private val writeLock = Any()

    // Pinging loop uses this
    private var lastActivityTime = System.currentTimeMillis()

    /**
     * Initialize connection and authenticate synchronously.
     * Blocks until authentication completes or fails.
     * After this returns, the client is ready for use.
     */
    override fun start() {
        try {
            // 1) Connection initialization: [version:1][protoType:1]
            try {
                connection.write(byteArrayOf(VERSION, PROTO_CLIENT))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write protocol selector to connection", e)
                throw MediatorException("Failed to write protocol selector", e)
            }

            super.start()

            // 2) Authenticate synchronously before returning
            val ok = authenticate()
            if (!ok) {
                running = false
                closeQuietly()
                throw MediatorException("Authentication failed")
            }

            Log.i(TAG, "MediatorClient authenticated successfully")
            listener.onConnected()

            // 3) Start background reader (this thread keeps the connection alive)
            thread(name = "$TAG-Pinger", isDaemon = true) {
                pingLoop()
            }

            // Connection is ready - reader thread will keep it alive
        } catch (e: Exception) {
            Log.e(TAG, "Client initialization error", e)
            running = false
            listener.onDisconnected(e)
            throw e
        }
    }

    override fun run() {
        try {
            readerLoop()
        } catch (e: Throwable) {
            Log.e(TAG, "Error reading from pipe: {$e}")
        }
    }

    // === Public API (thread/handler friendly, blocking with timeouts) ===

    /** Performs GET_NONCE + AUTH; called automatically on start(). */
    fun authenticate(): Boolean {
        // GET_NONCE(pubkey) -> nonce(32)
        val nonce = getNonce(pubkey)
        if (nonce == null) {
            Log.e(TAG, "Failed to get nonce from mediator (timeout or connection issue)")
            return false
        }

        // AUTH(pubkey, nonce, signature) - Build TLV payload
        val sig = Sign.sign(keyPair.private, nonce)
        if (sig == null) {
            Log.e(TAG, "Failed to sign nonce (cryptography error)")
            return false
        }

        val payload = ByteArrayOutputStream().apply {
            writeTLV(TAG_PUBKEY, pubkey)
            writeTLV(TAG_NONCE, nonce)
            writeTLV(TAG_SIGNATURE, sig)
        }.toByteArray()
        val resp = request(CMD_AUTH, payload)
        if (resp == null) {
            Log.e(TAG, "Authentication request timeout (no response from mediator)")
            return false
        }

        if (resp.status != STATUS_OK) {
            Log.e(TAG, "Authentication rejected by mediator: ${resp.errorString()}")
            return false
        }

        Log.i(TAG, "Authentication successful")
        return true
    }

    /** Creates a chat. Satisfies the server's signature filter: sig[0]==0 && sig[1]==0. */
    fun createChat(name: String, description: String, avatar: ByteArray? = null): Long {

        require(name.length <= 20) { "name > 20 bytes" }
        require(description.length <= 200) { "description > 200 bytes" }
        val avatarBytes = avatar ?: ByteArray(0)
        require(avatarBytes.size <= 200 * 1024) { "avatar > 200KB" }

        // Server expects nonce from DB: GET_NONCE(owner_pubkey)
        val nonce = getNonce(pubkey) ?: throw MediatorException("Failed to get nonce")

        // Proof-of-work: increment counter until signature(nonce||counter) has sig[0]==0 && sig[1]==0
        // Probability: 1/65536 per attempt, expected ~32k-64k attempts
        // Pre-allocate message buffer: nonce(32) + counter(4)
        val msg = ByteArray(36)
        System.arraycopy(nonce, 0, msg, 0, 32)

        var counter: UInt = 0u
        var sig: ByteArray
        val maxAttempts: UInt = UInt.MAX_VALUE // u32 max is more than enough

        do {
            // Check if client is still running every 10k attempts
            if (counter > 0u && counter % 10000u == 0u) {
                if (!running) {
                    throw MediatorException("Client disconnected during signature generation (after $counter attempts)")
                }
                Log.i(TAG, "createChat POW: $counter attempts so far...")
            }

            // Update counter bytes in-place (big-endian u32 at offset 32)
            msg.putU32BE(32, counter)
            sig = Sign.sign(keyPair.private, msg) ?: throw MediatorException("Sign failed")

            if (sig.size == 64 && sig[0].toInt() == 0 && sig[1].toInt() == 0) {
                break // Found valid signature
            }

            counter++
            if (counter == maxAttempts) {
                throw MediatorException("createChat POW exhausted all u32 values (extremely unlucky)")
            }
        } while (true)

        Log.i(TAG, "createChat POW completed after $counter attempts")

        // Extract final counter value for payload
        val counterBytes = ByteArray(4)
        System.arraycopy(msg, 32, counterBytes, 0, 4)

        // Build TLV payload
        val payload = ByteArrayOutputStream().apply {
            writeTLV(TAG_PUBKEY, pubkey)
            writeTLV(TAG_NONCE, nonce)
            writeTLV(TAG_COUNTER, counterBytes)
            writeTLV(TAG_SIGNATURE, sig)
            writeTLVString(TAG_CHAT_NAME, name)
            writeTLVString(TAG_CHAT_DESC, description)
            if (avatarBytes.isNotEmpty()) {
                writeTLV(TAG_CHAT_AVATAR, avatarBytes)
            }
        }.toByteArray()

        val resp = request(CMD_CREATE_CHAT, payload)
            ?: throw MediatorException("createChat timeout")
        if (resp.status != STATUS_OK) throw resp.asException("createChat failed: " + resp.errorString())

        // Parse TLV response
        val tlvs = resp.payload.parseTLVs()
        return tlvs.getTLVLong(TAG_CHAT_ID)
    }

    /** Deletes a chat. Handler reads only chat_id(u64) (verified). */
    fun deleteChat(chatId: Long): Boolean {
        // Build TLV payload
        val payload = ByteArrayOutputStream().apply {
            writeTLVLong(TAG_CHAT_ID, chatId)
        }.toByteArray()
        val resp = request(CMD_DELETE_CHAT, payload) ?: return false
        if (resp.status != STATUS_OK) throw resp.asException("deleteChat failed")
        // server returns 1 byte 1 on success; we tolerate OK w/empty as well
        return true
    }

    fun addUser(chatId: Long, userPubKey: ByteArray) {
        require(userPubKey.size == 32) { "userPubKey must be 32 bytes" }
        // Build TLV payload
        val payload = ByteArrayOutputStream().apply {
            writeTLVLong(TAG_CHAT_ID, chatId)
            writeTLV(TAG_USER_PUBKEY, userPubKey)
        }.toByteArray()
        val resp = request(CMD_ADD_USER, payload) ?: throw MediatorException("addUser timeout")
        if (resp.status != STATUS_OK) throw resp.asException("addUser failed")
    }

    fun deleteUser(chatId: Long, userPubKey: ByteArray) {
        require(userPubKey.size == 32) { "userPubKey must be 32 bytes" }
        // Build TLV payload
        val payload = ByteArrayOutputStream().apply {
            writeTLVLong(TAG_CHAT_ID, chatId)
            writeTLV(TAG_USER_PUBKEY, userPubKey)
        }.toByteArray()
        val resp = request(CMD_DELETE_USER, payload) ?: throw MediatorException("deleteUser timeout")
        if (resp.status != STATUS_OK) throw resp.asException("deleteUser failed")
    }

    fun leaveChat(chatId: Long) {
        // Build TLV payload
        val payload = ByteArrayOutputStream().apply {
            writeTLVLong(TAG_CHAT_ID, chatId)
        }.toByteArray()
        val resp = request(CMD_LEAVE_CHAT, payload) ?: throw MediatorException("leaveChat timeout")
        if (resp.status != STATUS_OK) throw resp.asException("leaveChat failed")
    }

    fun subscribe(chatId: Long): Long {
        // Build TLV payload
        val payload = ByteArrayOutputStream().apply {
            writeTLVLong(TAG_CHAT_ID, chatId)
        }.toByteArray()
        val resp = request(CMD_SUBSCRIBE, payload) ?: throw MediatorException("subscribe timeout")
        if (resp.status != STATUS_OK) throw resp.asException("subscribe failed")

        // Parse TLV response: TAG_MESSAGE_ID
        val tlvs = resp.payload.parseTLVs()
        val lastMessageId = tlvs.getTLVLong(TAG_MESSAGE_ID)

        // Fetch member list and member info after successful subscription
        thread(name = "FetchMembers-$chatId") {
            fetchAndSaveMembers(chatId)
        }

        return lastMessageId
    }

    /**
     * Fetches ALL members for a chat from mediator and saves to local storage.
     * Uses GET_MEMBERS_INFO which returns all members in one call:
     * - Always fetches with timestamp=0 to get fresh member info on subscription
     * - Members with info get full encrypted profiles
     * - Members without info get just pubkey with null profile
     * This provides a consistent snapshot of membership + all available member info.
     */
    private fun fetchAndSaveMembers(chatId: Long) {
        try {
            Log.i(TAG, "Fetching all members for chat $chatId")

            // Get chat info including shared key for decryption
            val chatInfo = storage.getGroupChat(chatId)
            if (chatInfo == null) {
                Log.e(TAG, "Chat $chatId not found in storage, cannot fetch members")
                return
            }

            val lastUpdated = storage.getLatestGroupMemberUpdateTime(chatId)
            val members = getMembersInfo(chatId, sinceTimestamp = lastUpdated)
            Log.i(TAG, "Received ${members.size} member(s) for chat $chatId")

            // Process each member
            for (member in members) {
                try {
                    // If member has encrypted info, decrypt and save full profile
                    if (member.encryptedInfo != null && member.encryptedInfo.isNotEmpty()) {
                        //Log.d(TAG, "Decrypting member info: pubkey=${Hex.toHexString(member.pubkey).take(8)}..., encrypted size=${member.encryptedInfo.size}")

                        val decryptedBlob = GroupChatCrypto.decryptMessage(
                            member.encryptedInfo,
                            chatInfo.sharedKey
                        )

                        //Log.d(TAG, "Decrypted blob size: ${decryptedBlob.size}, first 32 bytes: ${Hex.toHexString(decryptedBlob.copyOfRange(0, minOf(32, decryptedBlob.size)))}")

                        // Parse decrypted blob: [nicknameLen(u16)][nickname][infoLen(u16)][info][avatarLen(u32)][avatar?]
                        var offset = 0

                        // Validate minimum size
                        require(decryptedBlob.size >= 2) { "Decrypted blob too small for nicknameLen" }

                        // Read nickname
                        val nicknameLen = ((decryptedBlob[offset].toInt() and 0xFF) shl 8) or
                                         (decryptedBlob[offset + 1].toInt() and 0xFF)
                        offset += 2
                        val nickname = if (nicknameLen > 0) {
                            String(decryptedBlob, offset, nicknameLen, Charsets.UTF_8)
                        } else {
                            null
                        }
                        offset += nicknameLen

                        // Read info
                        val infoLen = ((decryptedBlob[offset].toInt() and 0xFF) shl 8) or
                                     (decryptedBlob[offset + 1].toInt() and 0xFF)
                        offset += 2
                        val info = if (infoLen > 0) {
                            String(decryptedBlob, offset, infoLen, Charsets.UTF_8)
                        } else {
                            null
                        }
                        offset += infoLen

                        // Read avatar
                        val avatarLen = ((decryptedBlob[offset].toInt() and 0xFF) shl 24) or
                                       ((decryptedBlob[offset + 1].toInt() and 0xFF) shl 16) or
                                       ((decryptedBlob[offset + 2].toInt() and 0xFF) shl 8) or
                                       (decryptedBlob[offset + 3].toInt() and 0xFF)
                        offset += 4
                        val avatar = if (avatarLen > 0) {
                            decryptedBlob.copyOfRange(offset, offset + avatarLen)
                        } else {
                            null
                        }

                        // Save full profile to storage
                        storage.updateGroupMemberInfo(chatId, member.pubkey, nickname, info, avatar)
                    } else {
                        // Member exists but no info update - save with null profile
                        storage.updateGroupMemberInfo(chatId, member.pubkey, null, null, null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing member ${Hex.toHexString(member.pubkey).take(8)}... for chat $chatId", e)
                }
            }

            // Update the chat's updated_at timestamp to track when members were last synced
            // This allows us to ignore old system messages (like "user left") that occurred before this sync
            storage.updateGroupChatTimestamp(chatId)

            //Log.i(TAG, "Finished saving ${members.size} member(s) for chat $chatId")
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching members for chat $chatId", e)
        }
    }

    fun getUserChats(): LongArray {
        // Request with empty TLV payload
        val resp = request(CMD_GET_USER_CHATS, ByteArray(0)) ?: throw MediatorException("getUserChats timeout")
        if (resp.status != STATUS_OK) throw resp.asException("getUserChats failed")

        // Parse TLV response: TAG_COUNT + repeated TAG_CHAT_ID
        val tlvs = resp.payload.parseTLVs()
        val count = tlvs.getTLVUInt(TAG_COUNT).toInt()

        // For repeated fields, we need to parse manually
        val chatIds = mutableListOf<Long>()
        var offset = 0
        while (offset < resp.payload.size) {
            val tag = resp.payload[offset++]
            val (length, consumed) = resp.payload.readVarint(offset)
            offset += consumed

            if (tag == TAG_CHAT_ID && length.toInt() == 8) {
                val chatId = resp.payload.readLong(offset)
                chatIds.add(chatId)
            }
            offset += length.toInt()
        }

        return chatIds.toLongArray()
    }

    /** Sends a message and returns server-assigned incremental message_id. */
    fun sendMessage(chatId: Long, guid: Long, blob: ByteArray): Long {
        // Build TLV payload
        val payload = ByteArrayOutputStream().apply {
            writeTLVLong(TAG_CHAT_ID, chatId)
            writeTLVLong(TAG_MESSAGE_GUID, guid)
            writeTLV(TAG_MESSAGE_BLOB, blob)
        }.toByteArray()
        val resp = request(CMD_SEND_MESSAGE, payload) ?: throw MediatorException("sendMessage timeout")
        if (resp.status != STATUS_OK) throw resp.asException("sendMessage failed")

        // Parse TLV response: TAG_MESSAGE_ID
        val tlvs = resp.payload.parseTLVs()
        return tlvs.getTLVLong(TAG_MESSAGE_ID)
    }

    fun deleteMessage(chatId: Long, guid: Long) {
        // Build TLV payload
        val payload = ByteArrayOutputStream().apply {
            writeTLVLong(TAG_CHAT_ID, chatId)
            writeTLVLong(TAG_MESSAGE_GUID, guid)
        }.toByteArray()
        val resp = request(CMD_DELETE_MESSAGE, payload) ?: throw MediatorException("deleteMessage timeout")
        if (resp.status != STATUS_OK) throw resp.asException("deleteMessage failed")
    }

    fun getLastMessageId(chatId: Long): Long {
        // Build TLV payload
        val payload = ByteArrayOutputStream().apply {
            writeTLVLong(TAG_CHAT_ID, chatId)
        }.toByteArray()
        val resp = request(CMD_GET_LAST_MESSAGE_ID, payload) ?: throw MediatorException("getLastMessageId timeout")
        if (resp.status != STATUS_OK) throw resp.asException("getLastMessageId failed")

        // Parse TLV response: TAG_MESSAGE_ID
        val tlvs = resp.payload.parseTLVs()
        return tlvs.getTLVLong(TAG_MESSAGE_ID)
    }

    /**
     * Batch-fetches messages since a given message ID.
     * More efficient than fetching one message at a time.
     *
     * Protocol: cmdGetMessagesSince (0x36)
     * Request payload: [chatId(u64)][sinceMessageId(u64)][limit(u32)]
     * Response payload: [count(u32)][[chatId(u64)][msgId(u64)][guid(u64)][author(32)][blobLen(u32)][blob]...]
     *
     * Note: Response format matches push message format for consistency:
     * [chatId][msgId][guid][author][blobLen][blob]
     *
     * @param chatId The group chat ID
     * @param sinceMessageId Fetch messages with ID > sinceMessageId (exclusive)
     * @param limit Maximum number of messages to fetch (default 100, max 500)
     * @return List of messages in ascending order by message ID
     */
    fun getMessagesSince(chatId: Long, sinceMessageId: Long, limit: Int = 100): List<MessagePayload> {
        require(limit in 1..500) { "limit must be between 1 and 500" }

        Log.i(TAG, "Requesting messages from chat $chatId since $sinceMessageId, limit $limit")

        // Build TLV payload
        val payload = ByteArrayOutputStream().apply {
            writeTLVLong(TAG_CHAT_ID, chatId)
            writeTLVLong(TAG_SINCE_ID, sinceMessageId)
            writeTLVUInt(TAG_LIMIT, limit.toUInt())
        }.toByteArray()

        val resp = request(CMD_GET_MESSAGES_SINCE, payload) ?: throw MediatorException("getMessagesSince timeout")
        if (resp.status != STATUS_OK) throw resp.asException("getMessagesSince failed")

        // Parse TLV response: TAG_COUNT + repeated messages (each message has multiple TLVs)
        val messages = mutableListOf<MessagePayload>()
        var offset = 0

        // First read count
        var count = 0
        var msgId = 0L
        var guid = 0L
        var author: ByteArray? = null
        var blob: ByteArray? = null
        var timestamp = System.currentTimeMillis()

        while (offset < resp.payload.size) {
            val tag = resp.payload[offset++]
            val (length, consumed) = resp.payload.readVarint(offset)
            offset += consumed
            val value = resp.payload.copyOfRange(offset, offset + length.toInt())
            offset += length.toInt()

            when (tag) {
                TAG_COUNT -> count = ByteArray(4).apply { value.copyInto(this) }.readU32(0).toInt()
                TAG_MESSAGE_ID -> msgId = value.readLong(0)
                TAG_MESSAGE_GUID -> guid = value.readLong(0)
                TAG_PUBKEY -> author = value
                TAG_TIMESTAMP -> timestamp = value.readLong(0)
                TAG_MESSAGE_BLOB -> {
                    blob = value
                    // Message complete - add to list
                    if (author != null) {
                        messages.add(MessagePayload(msgId, guid, timestamp, author, blob))
                        // Reset for next message
                        author = null
                        blob = null
                    }
                }
            }
        }

        Log.i(TAG, "getMessagesSince: fetched ${messages.size} message(s) for chat $chatId since $sinceMessageId")
        return messages
    }

    /**
     * Sends an invite to a user for a given chat.
     * Protocol: cmdSendInvite (0x40)
     * Payload: [chatId(u64)][toPubkey(32)][len(u32)][encryptedData]
     *
     * encryptedData should contain: chat metadata + encrypted shared key
     * (encryption is caller's responsibility)
     */
    fun sendInvite(chatId: Long, toPubkey: ByteArray, encryptedData: ByteArray) {
        require(toPubkey.size == 32) { "toPubkey must be 32 bytes" }
        require(encryptedData.isNotEmpty()) { "encryptedData cannot be empty" }

        // Build TLV payload
        val payload = ByteArrayOutputStream().apply {
            writeTLVLong(TAG_CHAT_ID, chatId)
            writeTLV(TAG_USER_PUBKEY, toPubkey)
            writeTLV(TAG_INVITE_DATA, encryptedData)
        }.toByteArray()

        val resp = request(CMD_SEND_INVITE, payload) ?: throw MediatorException("sendInvite timeout")
        if (resp.status != STATUS_OK) throw resp.asException("sendInvite failed")
    }

    /**
     * Responds to an invite (accept or reject).
     * Protocol: cmdInviteResponse (0x42)
     * Payload: [inviteId(u64)][accepted(u8)]
     *
     * Accepted values:
     * - 0: reject invite
     * - 1: accept invite
     *
     * @param inviteId The ID of the invite to respond to
     * @param accepted 1 to accept, 0 to reject
     */
    fun respondToInvite(inviteId: Long, accepted: Int) {
        require(accepted == 0 || accepted == 1) { "accepted must be 0 (reject) or 1 (accept)" }

        // Build TLV payload
        val payload = ByteArrayOutputStream().apply {
            writeTLVLong(TAG_INVITE_ID, inviteId)
            writeTLVByte(TAG_ACCEPTED, accepted.toByte())
        }.toByteArray()

        val resp = request(CMD_INVITE_RESPONSE, payload) ?: throw MediatorException("respondToInvite timeout")
        if (resp.status != STATUS_OK) throw resp.asException("respondToInvite failed")

        Log.i(TAG, "Invite $inviteId ${if (accepted == 1) "accepted" else "rejected"}")
    }

    /**
     * Updates member info on the mediator server for a group chat.
     * The member info (nickname, info field, avatar) is encrypted with the chat's shared key
     * so only chat members can decrypt it.
     *
     * Protocol: cmdUpdateMemberInfo (0x50)
     * Payload: [chatId(u64)][timestamp(u64)][len(u32)][encryptedBlob]
     *
     * The encryptedBlob contains (encrypted with chat's shared key):
     *   [nicknameLen(u16)][nickname][infoLen(u16)][info][avatarLen(u32)][avatar]
     *
     * @param chatId The group chat ID
     * @param timestamp Last update timestamp (for "if changed since" logic)
     * @param nickname User's display name
     * @param info User's info/status text
     * @param avatar User's avatar image data (null if no avatar)
     * @param sharedKey Chat's shared encryption key
     */
    fun updateMemberInfo(chatId: Long, timestamp: Long, nickname: String, info: String, avatar: ByteArray?, sharedKey: ByteArray) {
        require(sharedKey.size == 32) { "Shared key must be 32 bytes" }

        // Build plaintext member info blob
        val plaintext = buildMemberInfoBlob(nickname, info, avatar)

        // Encrypt with chat's shared key
        val encryptedBlob = try {
            GroupChatCrypto.encryptMessage(plaintext, sharedKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt member info", e)
            throw MediatorException("Failed to encrypt member info", e)
        }

        // Build TLV request payload
        val payload = ByteArrayOutputStream().apply {
            writeTLVLong(TAG_CHAT_ID, chatId)
            writeTLVLong(TAG_TIMESTAMP, timestamp.toULong().toLong())
            writeTLV(TAG_MEMBER_INFO, encryptedBlob)
        }.toByteArray()

        val resp = request(CMD_UPDATE_MEMBER_INFO, payload) ?: throw MediatorException("updateMemberInfo timeout")
        if (resp.status != STATUS_OK) throw resp.asException("updateMemberInfo failed")

        Log.i(TAG, "Member info updated for chat $chatId")
    }

    /**
     * Builds unencrypted member info blob.
     * Format: [nicknameLen(u16)][nickname][infoLen(u16)][info][avatarLen(u32)][avatar]
     */
    private fun buildMemberInfoBlob(nickname: String, info: String, avatar: ByteArray?): ByteArray {
        val nickBytes = nickname.toByteArray(Charsets.UTF_8)
        val infoBytes = info.toByteArray(Charsets.UTF_8)
        val avatarBytes = avatar ?: ByteArray(0)

        require(nickBytes.size <= 0xFFFF) { "Nickname too long (max 65535 bytes UTF-8)" }
        require(infoBytes.size <= 0xFFFF) { "Info too long (max 65535 bytes UTF-8)" }
        require(avatarBytes.size <= 500 * 1024) { "Avatar too large (max 500KB)" }

        return ByteArrayOutputStream().apply {
            // Nickname
            write(byteArrayOf(((nickBytes.size shr 8) and 0xFF).toByte(), (nickBytes.size and 0xFF).toByte()))
            write(nickBytes)

            // Info
            write(byteArrayOf(((infoBytes.size shr 8) and 0xFF).toByte(), (infoBytes.size and 0xFF).toByte()))
            write(infoBytes)

            // Avatar
            write(byteArrayOf(
                ((avatarBytes.size ushr 24) and 0xFF).toByte(),
                ((avatarBytes.size ushr 16) and 0xFF).toByte(),
                ((avatarBytes.size ushr 8) and 0xFF).toByte(),
                (avatarBytes.size and 0xFF).toByte()
            ))
            if (avatarBytes.isNotEmpty()) {
                write(avatarBytes)
            }
        }.toByteArray()
    }

    /**
     * Gets ALL members from the mediator for a specific chat.
     * Returns complete member list with selective info updates based on timestamp.
     *
     * Protocol: cmdGetMembersInfo (0x52)
     * Request payload: [chatId(u64)][timestamp(u64)]
     * Response payload: [count(u32)][[pubkey(32)][infoLen(u32)][encryptedInfo][timestamp(u64)]...]
     *
     * Behavior:
     * - Always returns ALL non-banned members (complete membership list)
     * - Members with info updated after sinceTimestamp get full encrypted info (infoLen > 0)
     * - Members with no updates since timestamp get pubkey only (infoLen = 0, encryptedInfo = null)
     *
     * This provides a consistent atomic snapshot of membership + selective profile updates.
     *
     * @param chatId The group chat ID
     * @param sinceTimestamp Filter info updates (0 = get all info, >0 = only info changed since timestamp)
     * @return List of ALL members with selective info updates
     */
    fun getMembersInfo(chatId: Long, sinceTimestamp: Long = 0): List<MemberInfo> {
        //Log.i(TAG, "getMembersInfo: chatId=$chatId, sinceTimestamp=$sinceTimestamp")

        // Build TLV payload
        val payload = ByteArrayOutputStream().apply {
            writeTLVLong(TAG_CHAT_ID, chatId)
            if (sinceTimestamp > 0) {
                writeTLVLong(TAG_LAST_UPDATE, sinceTimestamp.toULong().toLong())
            }
        }.toByteArray()

        val resp = request(CMD_GET_MEMBERS_INFO, payload) ?: throw MediatorException("getMembersInfo timeout")
        if (resp.status != STATUS_OK) throw resp.asException("getMembersInfo failed")

        //Log.d(TAG, "getMembersInfo response payload size: ${resp.payload.size} bytes")

        // Parse TLV response: TAG_COUNT + repeated members (each member has TAG_USER_PUBKEY, TAG_MEMBER_INFO, TAG_TIMESTAMP)
        val members = mutableListOf<MemberInfo>()
        var offset = 0
        var count = 0
        var pubkey: ByteArray? = null
        var encryptedInfo: ByteArray? = null
        var timestamp = 0L
        var memberIndex = 0

        while (offset < resp.payload.size) {
            val tag = resp.payload[offset++]
            val (length, consumed) = resp.payload.readVarint(offset)
            offset += consumed
            val value = resp.payload.copyOfRange(offset, offset + length.toInt())
            offset += length.toInt()

            when (tag) {
                TAG_COUNT -> {
                    count = ByteArray(4).apply { value.copyInto(this) }.readU32(0).toInt()
                }
                TAG_USER_PUBKEY -> {
                    pubkey = value
                }
                TAG_MEMBER_INFO -> {
                    encryptedInfo = if (value.isNotEmpty()) value else null
                }
                TAG_TIMESTAMP -> {
                    timestamp = value.readLong(0)
                    // Member record complete - add to list
                    if (pubkey != null) {
                        members.add(MemberInfo(pubkey, encryptedInfo, timestamp))
                        memberIndex++
                        // Reset for next member
                        pubkey = null
                        encryptedInfo = null
                        timestamp = 0L
                    }
                }
            }
        }
        //Log.i(TAG, "Retrieved ${members.size} member(s) info for chat $chatId, ${members.count { it.encryptedInfo != null }}")
        return members
    }

    /**
     * Gets all members from the mediator with their permissions and online status.
     * This is a lightweight call that returns pubkey, permissions, and online state.
     *
     * Protocol: cmdGetMembers (0x53)
     * Request payload: [chatId(u64)]
     * Response payload: [count(u32)][[pubkey(32)][perms(1)][online(1)] repeated]
     *
     * @param chatId The group chat ID
     * @return List of members with permissions and online status
     */
    fun getMembers(chatId: Long): List<Member> {
        // Build TLV payload
        val payload = ByteArrayOutputStream().apply {
            writeTLVLong(TAG_CHAT_ID, chatId)
        }.toByteArray()

        val resp = request(CMD_GET_MEMBERS, payload) ?: throw MediatorException("getMembers timeout")
        if (resp.status != STATUS_OK) throw resp.asException("getMembers failed")

        // Parse TLV response: TAG_COUNT + repeated members (each member has TAG_USER_PUBKEY, TAG_PERMS, TAG_ONLINE)
        val members = mutableListOf<Member>()
        var offset = 0
        var count = 0
        var pubkey: ByteArray? = null
        var perms = 0
        var online = false

        while (offset < resp.payload.size) {
            val tag = resp.payload[offset++]
            val (length, consumed) = resp.payload.readVarint(offset)
            offset += consumed
            val value = resp.payload.copyOfRange(offset, offset + length.toInt())
            offset += length.toInt()

            when (tag) {
                TAG_COUNT -> count = ByteArray(4).apply { value.copyInto(this) }.readU32(0).toInt()
                TAG_USER_PUBKEY -> pubkey = value
                TAG_PERMS -> perms = value[0].toInt() and 0xFF
                TAG_ONLINE -> {
                    online = value[0].toInt() == 1
                    // Member record complete - add to list
                    if (pubkey != null) {
                        members.add(Member(pubkey, perms, online))
                        // Reset for next member
                        pubkey = null
                        perms = 0
                        online = false
                    }
                }
            }
        }

        Log.i(TAG, "Retrieved ${members.size} member(s) for chat $chatId")
        return members
    }

    fun stopClient() {
        running = false
        closeQuietly()
        interrupt()
    }

    fun isRunning(): Boolean {
        return running
    }

    fun pingLoop() {
        while (running) {
            sleep(5000)
            if (!App.app.online || !running) {
                Log.i(TAG, "We are offline, stopping client")
                stopClient()
                listener.onDisconnected(MediatorException("Client went offline"))
                break
            }
            if (!connection.isAlive) {
                Log.d(TAG, "Connection is broken, stopping client")
                stopClient()
                listener.onDisconnected(MediatorException("Connection not alive"))
                break
            }
            if (System.currentTimeMillis() - lastActivityTime > PING_DEADLINE_MS) {
                try {
                    if (!sendPing()) {
                        Log.i(TAG, "Connection broken")
                        stopClient()
                        listener.onDisconnected(MediatorException("Ping failed"))
                    }
                } catch (e: MediatorException) {
                    Log.i(TAG, "Connection broken: $e")
                    stopClient()
                    listener.onDisconnected(e)
                }
            }
        }
    }

    // === Internal: reader & request plumbing ===

    private fun readerLoop() {
        val dis = DataInputStream(ConnectionInputStream(connection))
        try {
            while (running) {
                //Log.i(TAG, "Reading from socket...")
                // Response header: [status:1][reqId:2][len:4]
                val status = dis.readUnsignedByte()
                val reqId = dis.readShort()
                val len = dis.readInt()
                if (len < 0) throw MediatorException("negative payload length")
                val payload = ByteArray(len)
                if (len > 0) dis.readFully(payload)
                lastActivityTime = System.currentTimeMillis()

                // Push messages: status=OK, reqId=0x34
                if (status == STATUS_OK && (reqId.toInt() and 0xFFFF) == CMD_GOT_MESSAGE) {
                    // Parse TLV payload: TAG_CHAT_ID, TAG_MESSAGE_ID, TAG_MESSAGE_GUID, TAG_PUBKEY, TAG_MESSAGE_BLOB
                    val tlvs = payload.parseTLVs()
                    val chatId = tlvs.getTLVLong(TAG_CHAT_ID)
                    val msgId = tlvs.getTLVLong(TAG_MESSAGE_ID)
                    val guid = tlvs.getTLVLong(TAG_MESSAGE_GUID)
                    val timestamp = tlvs.getTLVLong(TAG_TIMESTAMP) * 1000 // Convert seconds to milliseconds
                    val author = tlvs.getTLVBytes(TAG_PUBKEY)
                    val data = tlvs.getTLVBytesOrNull(TAG_MESSAGE_BLOB) ?: ByteArray(0)

                    // Check if this is a system message (author == mediator pubkey)
                    if (author.contentEquals(mediatorPubkey)) {
                        // System message: unencrypted, format is [event_code(1)][...event data...]
                        Log.d(TAG, "Received system message for chat $chatId: event_code=${if (data.isNotEmpty()) data[0].toInt() and 0xFF else 0}")
                        listener.onSystemMessage(chatId, msgId, guid, timestamp, data)
                    } else {
                        // Regular user message
                        listener.onPushMessage(chatId, msgId, guid, timestamp, author, data)
                    }
                    continue
                }

                // Push invites: status=OK, reqId=0x41
                if (status == STATUS_OK && (reqId.toInt() and 0xFFFF) == CMD_GOT_INVITE) {
                    // Parse TLV payload: TAG_INVITE_ID, TAG_CHAT_ID, TAG_PUBKEY, TAG_TIMESTAMP,
                    //                    TAG_CHAT_NAME, TAG_CHAT_DESC, TAG_CHAT_AVATAR, TAG_INVITE_DATA
                    try {
                        val tlvs = payload.parseTLVs()
                        val inviteId = tlvs.getTLVLong(TAG_INVITE_ID)
                        val chatId = tlvs.getTLVLong(TAG_CHAT_ID)
                        val fromPubkey = tlvs.getTLVBytes(TAG_PUBKEY)
                        val timestamp = tlvs.getTLVLong(TAG_TIMESTAMP) * 1000 // Convert seconds to milliseconds
                        val name = tlvs.getTLVString(TAG_CHAT_NAME)
                        val desc = tlvs[TAG_CHAT_DESC]?.toString(Charsets.UTF_8) ?: ""
                        val avatar = tlvs.getTLVBytesOrNull(TAG_CHAT_AVATAR)
                        val encryptedData = tlvs.getTLVBytes(TAG_INVITE_DATA)

                        listener.onPushInvite(inviteId, chatId, fromPubkey, timestamp, name, desc, avatar, encryptedData)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing invite push", e)
                    }
                    continue
                }

                // Member info request: status=OK, reqId=0x51
                if (status == STATUS_OK && (reqId.toInt() and 0xFFFF) == CMD_REQUEST_MEMBER_INFO) {
                    // Parse TLV payload: TAG_CHAT_ID, TAG_LAST_UPDATE
                    try {
                        //Log.d(TAG, "Member info request payload size: ${payload.size}, first bytes: ${payload.take(20).joinToString(" ") { "0x%02X".format(it) }}")
                        val tlvs = payload.parseTLVs()
                        val chatId = tlvs.getTLVLong(TAG_CHAT_ID)
                        val lastUpdate = tlvs[TAG_LAST_UPDATE]?.readLong(0) ?: 0L

                        Log.i(TAG, "Mediator requests member info for chat $chatId (last update: $lastUpdate)")

                        // Ask listener for current member info
                        val memberInfo = listener.onMemberInfoRequest(chatId, lastUpdate)

                        if (memberInfo != null) {
                            // Send updated member info to mediator
                            Thread {
                                try {
                                    updateMemberInfo(
                                        chatId = chatId,
                                        timestamp = memberInfo.timestamp,
                                        nickname = memberInfo.nickname,
                                        info = memberInfo.info,
                                        avatar = memberInfo.avatar,
                                        sharedKey = memberInfo.sharedKey
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to send member info for chat $chatId", e)
                                }
                            }.start()
                        } else {
                            Log.d(TAG, "No our member info update needed for chat $chatId")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling member info request", e)
                    }
                    continue
                }

                // Member info push: status=OK, reqId=0x54
                if (status == STATUS_OK && (reqId.toInt() and 0xFFFF) == CMD_GOT_MEMBER_INFO) {
                    // Parse TLV payload: TAG_CHAT_ID, TAG_COUNT, TAG_USER_PUBKEY, TAG_MEMBER_INFO, TAG_TIMESTAMP
                    try {
                        Log.d(TAG, "Received member info broadcast, payload size: ${payload.size}")
                        val tlvs = payload.parseTLVs()
                        val chatId = tlvs.getTLVLong(TAG_CHAT_ID)
                        val count = tlvs.getTLVUInt(TAG_COUNT).toInt()
                        val memberPubkey = tlvs.getTLVBytes(TAG_USER_PUBKEY)
                        val encryptedInfo = tlvs.getTLVBytesOrNull(TAG_MEMBER_INFO)
                        val timestamp = tlvs.getTLVLong(TAG_TIMESTAMP)

                        Log.i(TAG, "Received member info for ${Hex.toHexString(memberPubkey).take(8)}... in chat $chatId, timestamp=$timestamp, hasInfo=${encryptedInfo != null}")

                        // Notify listener with chatId
                        listener.onMemberInfoUpdate(chatId, memberPubkey, encryptedInfo, timestamp)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling member info broadcast", e)
                    }
                    continue
                }

                // Normal response: match reqId
                val future = pending.remove(reqId)
                if (future != null) {
                    future.complete(Response(status, reqId, payload))
                } else {
                    Log.w(TAG, "Unmatched response reqId=$reqId status=$status len=$len (maybe late or already timed out)")
                }
            }
        } catch (e: Throwable) {
            if (running) {
                Log.e(TAG, "Reader error", e)
                listener.onDisconnected(Exception("Reader error: ${e.message}", e))
            }
        } finally {
            running = false
            // Fail all pending requests
            pending.values.forEach { it.completeExceptionally(MediatorException("connection closed")) }
            pending.clear()
        }
    }

    private fun request(cmd: Int, payload: ByteArray): Response? {
        if (!running) throw MediatorException("Client not running")
        val reqId = (rng.nextInt(0xFFFF) + 1).toShort() // 1..65535; 0 is reserved by us
        val box = WaitBox<Response>()
        pending[reqId] = box

        // Build request frame: [cmd][reqId][len][payload]  (version sent once at connection init)
        val header = ByteArrayOutputStream(1 + 2 + 4 + payload.size)
        header.write(byteArrayOf(cmd.toByte()))
        header.write(reqId.toBytesBE())
        header.write(payload.size.toBytesBE())
        if (payload.isNotEmpty()) {
            header.write(payload)
        }

        try {
            synchronized(writeLock) {
                connection.write(header.toByteArray())
            }
        } catch (e: Exception) {
            pending.remove(reqId)
            Log.e(TAG, "Write failed, stopping client", e)
            stopClient()
            listener.onDisconnected(MediatorException("Write failed", e))
            throw MediatorException("Write failed", e)
        }

        return try {
            box.await(REQ_TIMEOUT_MS)
        } catch (e: MediatorException) {
            pending.remove(reqId)
            // If connection was closed while waiting, return null instead of crashing
            if (e.message?.contains("connection closed") == true) {
                Log.w(TAG, "Request failed due to connection closure")
                null
            } else {
                throw e
            }
        } catch (e: Exception) {
            pending.remove(reqId)
            throw e
        }
    }

    private fun sendPing(): Boolean {
        val resp = request(CMD_PING, ByteArray(0)) ?: return false
        if (resp.status != STATUS_OK) {
            Log.w(TAG, "CMD_PING error: ${resp.errorString()}")
            return false
        }
        return true
    }

    private fun getNonce(pubkey: ByteArray): ByteArray? {
        // Build TLV request payload
        val payload = ByteArrayOutputStream().apply {
            writeTLV(TAG_PUBKEY, pubkey)
        }.toByteArray()

        val resp = request(CMD_GET_NONCE, payload)
        if (resp == null) {
            Log.e(TAG, "getNonce: Request timeout (no response)")
            return null
        }

        if (resp.status != STATUS_OK) {
            Log.e(TAG, "getNonce: Server error: ${resp.errorString()}")
            return null
        }

        // Parse TLV response
        val tlvs = resp.payload.parseTLVs()
        val nonce = tlvs.getTLVBytes(TAG_NONCE)
        return nonce
    }

    private fun closeQuietly() {
        try { connection.close() } catch (_: Exception) {}
    }

    // === Helpers ===

    data class Response(val status: Int, val reqId: Short, val payload: ByteArray) {
        fun errorString(): String {
            if (status != STATUS_ERR || payload.size < 2) return ""
            val len = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
            val end = 2 + len
            return try {
                String(payload, 2, len, Charsets.UTF_8)
            } catch (_: Exception) { "" }
        }
        fun asException(prefix: String): MediatorException =
            MediatorException("$prefix: ${errorString()}")
    }

    @Suppress("ArrayInDataClass")
    data class MessagePayload(
        val messageId: Long,
        val guid: Long,
        val timestamp: Long,
        val author: ByteArray,
        val data: ByteArray
    )

    interface MediatorListener {
        /** Called once the connection is authenticated and ready. */
        fun onConnected()

        /**
         * Server push with new message.
         */
        fun onPushMessage(chatId: Long, messageId: Long, guid: Long, timestamp: Long, author: ByteArray, data: ByteArray)

        /**
         * Server push with system message (mediator-generated events).
         * System messages are NOT encrypted and have the mediator's public key as author.
         *
         * Format: [event_code(1)][...event-specific data...]
         *
         * Event codes:
         * - SYS_USER_ADDED (0x01): [target_user(32)][actor(32)][random(32)]
         * - SYS_USER_LEFT (0x03): [user(32)][random(32)]
         * - SYS_USER_BANNED (0x04): [target_user(32)][actor(32)][random(32)]
         * - SYS_CHAT_DELETED (0x05): [actor(32)][random(32)]
         * - SYS_CHAT_INFO_CHANGE (0x06): [actor(32)][random(32)]
         * - SYS_PERMS_CHANGED (0x07): [target_user(32)][actor(32)][random(32)]
         *
         * @param chatId The group chat ID
         * @param messageId Server-assigned message ID
         * @param guid Message GUID
         * @param timestamp The time of message
         * @param body Unencrypted system message body
         */
        fun onSystemMessage(chatId: Long, messageId: Long, guid: Long, timestamp: Long, body: ByteArray)

        /**
         * Server push with new invite.
         */
        fun onPushInvite(
            inviteId: Long,
            chatId: Long,
            fromPubkey: ByteArray,
            timestamp: Long,
            chatName: String,
            chatDescription: String,
            chatAvatar: ByteArray?,
            encryptedData: ByteArray
        )

        /**
         * Mediator requests member info for a chat.
         * Return MemberInfoResponse with user's current info, or null if not a member.
         *
         * @param chatId The chat ID
         * @param lastUpdate Timestamp of last known update on mediator
         * @return Member info response, or null to skip update
         */
        fun onMemberInfoRequest(chatId: Long, lastUpdate: Long): MemberInfoResponse?

        /**
         * Mediator broadcasts that a member's info has been updated.
         *
         * @param chatId The chat ID where this member exists
         * @param memberPubkey The public key of the member whose info was updated
         * @param encryptedInfo The encrypted member info blob, or null if no info
         * @param timestamp The update timestamp
         */
        fun onMemberInfoUpdate(chatId: Long, memberPubkey: ByteArray, encryptedInfo: ByteArray?, timestamp: Long)

        /** Connection ended or failed. */
        fun onDisconnected(error: Exception)
    }

    /**
     * Response for member info request containing user's profile info and chat's shared key.
     */
    data class MemberInfoResponse(
        val nickname: String,
        val info: String,
        val avatar: ByteArray?,
        val sharedKey: ByteArray,
        val timestamp: Long
    )

    /**
     * Member info retrieved from mediator.
     * Contains encrypted info blob that needs to be decrypted with chat's shared key.
     */
    @Suppress("ArrayInDataClass")
    data class MemberInfo(
        val pubkey: ByteArray,          // 32-byte public key
        val encryptedInfo: ByteArray?,  // Encrypted blob (null if no info set)
        val timestamp: Long             // Last update timestamp
    )

    /**
     * Member data retrieved from mediator with permissions and online status.
     * This is a lightweight representation used for membership lists.
     */
    @Suppress("ArrayInDataClass")
    data class Member(
        val pubkey: ByteArray,          // 32-byte public key
        val permissions: Int,           // Permission flags (owner, admin, mod, user, read-only, banned)
        val online: Boolean             // Online status (true if subscribed to chat)
    )

    class MediatorException(message: String, cause: Throwable? = null) : Exception(message, cause)

    // Byte helpers

    private fun ByteArray.readLong(offset: Int): Long {
        var i = offset
        val v = ((this[i++].toLong() and 0xFF) shl 56) or
                ((this[i++].toLong() and 0xFF) shl 48) or
                ((this[i++].toLong() and 0xFF) shl 40) or
                ((this[i++].toLong() and 0xFF) shl 32) or
                ((this[i++].toLong() and 0xFF) shl 24) or
                ((this[i++].toLong() and 0xFF) shl 16) or
                ((this[i++].toLong() and 0xFF) shl 8) or
                (this[i].toLong() and 0xFF)
        return v
    }

    private fun ByteArray.readU32(offset: Int): UInt {
        var i = offset
        val v = ((this[i++].toInt() and 0xFF) shl 24) or
                ((this[i++].toInt() and 0xFF) shl 16) or
                ((this[i++].toInt() and 0xFF) shl 8) or
                (this[i].toInt() and 0xFF)
        return v.toUInt()
    }

    private fun ByteArray.readU16(offset: Int): Int {
        var i = offset
        return ((this[i++].toInt() and 0xFF) shl 8) or (this[i].toInt() and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLong(v: Long) {
        write(byteArrayOf(
            ((v ushr 56) and 0xFF).toByte(),
            ((v ushr 48) and 0xFF).toByte(),
            ((v ushr 40) and 0xFF).toByte(),
            ((v ushr 32) and 0xFF).toByte(),
            ((v ushr 24) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            (v and 0xFF).toByte()
        ))
    }

    private fun ByteArrayOutputStream.writeString(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        require(b.size <= 0xFFFF) { "string too long" }
        write(byteArrayOf(((b.size shr 8) and 0xFF).toByte(), (b.size and 0xFF).toByte()))
        write(b)
    }

    private fun ByteArrayOutputStream.writeBlob(b: ByteArray) {
        val len = b.size
        write(byteArrayOf(
            ((len ushr 24) and 0xFF).toByte(),
            ((len ushr 16) and 0xFF).toByte(),
            ((len ushr 8) and 0xFF).toByte(),
            (len and 0xFF).toByte()
        ))
        if (len > 0) write(b)
    }

    private fun Short.toBytesBE(): ByteArray {
        val bb = ByteBuffer.allocate(2)
        bb.put(((toInt() ushr 8) and 0xFF).toByte())
        bb.put((toInt() and 0xFF).toByte())
        return bb.array()
    }

    private fun Int.toBytesBE(): ByteArray {
        val bb = ByteBuffer.allocate(4)
        bb.put(((this ushr 24) and 0xFF).toByte())
        bb.put(((this ushr 16) and 0xFF).toByte())
        bb.put(((this ushr 8) and 0xFF).toByte())
        bb.put((this and 0xFF).toByte())
        return bb.array()
    }

    private fun ByteArray.putU32BE(offset: Int, v: UInt) {
        var i = offset
        this[i++] = ((v shr 24) and 0xFFu).toByte()
        this[i++] = ((v shr 16) and 0xFFu).toByte()
        this[i++] = ((v shr 8) and 0xFFu).toByte()
        this[i]   = ((v shr 0) and 0xFFu).toByte()
    }

    private fun ByteArray.putU64BE(offset: Int, v: Long) {
        var i = offset
        this[i++] = ((v shr 56) and 0xFF).toByte()
        this[i++] = ((v shr 48) and 0xFF).toByte()
        this[i++] = ((v shr 40) and 0xFF).toByte()
        this[i++] = ((v shr 32) and 0xFF).toByte()
        this[i++] = ((v shr 24) and 0xFF).toByte()
        this[i++] = ((v shr 16) and 0xFF).toByte()
        this[i++] = ((v shr 8) and 0xFF).toByte()
        this[i]   = ((v shr 0) and 0xFF).toByte()
    }

    // TLV and Varint helpers

    /**
     * Write a variable-length unsigned integer (varint) using Protocol Buffers encoding.
     * Uses 7 bits per byte for data, with MSB as continuation flag.
     * Max 4 bytes for up to 268MB values.
     */
    private fun ByteArrayOutputStream.writeVarint(value: UInt) {
        var v = value
        while (v >= 0x80u) {
            write(((v and 0x7Fu) or 0x80u).toByte().toInt())
            v = v shr 7
        }
        write((v and 0x7Fu).toByte().toInt())
    }

    /**
     * Read a variable-length unsigned integer from byte array.
     * Returns pair of (value, bytesConsumed).
     */
    private fun ByteArray.readVarint(offset: Int): Pair<UInt, Int> {
        var result = 0u
        var shift = 0
        var consumed = 0
        var i = offset

        for (byteIdx in 0 until 4) {
            if (i >= size) throw IOException("Varint: unexpected end of data")
            val b = this[i++].toInt() and 0xFF
            consumed++
            result = result or ((b and 0x7F).toUInt() shl shift)
            if ((b and 0x80) == 0) {
                return Pair(result, consumed)
            }
            shift += 7
        }
        throw IOException("Varint overflow: more than 4 bytes")
    }

    /**
     * Write a single TLV field to the output stream.
     */
    private fun ByteArrayOutputStream.writeTLV(tag: Byte, value: ByteArray) {
        write(tag.toInt())
        writeVarint(value.size.toUInt())
        if (value.isNotEmpty()) write(value)
    }

    /**
     * Write a TLV field with a long value (8 bytes big-endian).
     */
    private fun ByteArrayOutputStream.writeTLVLong(tag: Byte, value: Long) {
        val bytes = ByteArray(8)
        bytes.putU64BE(0, value)
        writeTLV(tag, bytes)
    }

    /**
     * Write a TLV field with a string value (UTF-8).
     */
    private fun ByteArrayOutputStream.writeTLVString(tag: Byte, value: String) {
        writeTLV(tag, value.toByteArray(Charsets.UTF_8))
    }

    /**
     * Write a TLV field with a UInt value (4 bytes big-endian).
     */
    private fun ByteArrayOutputStream.writeTLVUInt(tag: Byte, value: UInt) {
        val bytes = ByteArray(4)
        bytes.putU32BE(0, value)
        writeTLV(tag, bytes)
    }

    /**
     * Write a TLV field with a single byte value.
     */
    private fun ByteArrayOutputStream.writeTLVByte(tag: Byte, value: Byte) {
        writeTLV(tag, byteArrayOf(value))
    }

    /**
     * Parse all TLVs from a byte array into a map.
     * Returns map of tag -> value.
     */
    private fun ByteArray.parseTLVs(): Map<Byte, ByteArray> {
        val result = mutableMapOf<Byte, ByteArray>()
        var offset = 0

        while (offset < size) {
            // Read tag
            if (offset >= size) break
            val tag = this[offset++]

            // Read length (varint)
            val (length, consumed) = readVarint(offset)
            offset += consumed

            // Read value
            if (offset + length.toInt() > size) {
                throw IOException("TLV tag 0x${tag.toString(16)} length $length exceeds payload bounds (offset=$offset, size=$size, needed=${offset + length.toInt()})")
            }
            val value = copyOfRange(offset, offset + length.toInt())
            offset += length.toInt()

            // Store in map
            result[tag] = value
        }

        return result
    }

    /**
     * Get a required TLV value as ByteArray.
     */
    private fun Map<Byte, ByteArray>.getTLVBytes(tag: Byte): ByteArray {
        return this[tag] ?: throw IOException("Missing required TLV tag 0x${tag.toString(16)}")
    }

    /**
     * Get an optional TLV value as ByteArray, or null if missing.
     */
    private fun Map<Byte, ByteArray>.getTLVBytesOrNull(tag: Byte): ByteArray? {
        return this[tag]
    }

    /**
     * Get a TLV value as Long (8 bytes big-endian).
     */
    private fun Map<Byte, ByteArray>.getTLVLong(tag: Byte): Long {
        val bytes = getTLVBytes(tag)
        if (bytes.size != 8) throw IOException("TLV tag 0x${tag.toString(16)}: expected 8 bytes, got ${bytes.size}")
        return bytes.readLong(0)
    }

    /**
     * Get a TLV value as UInt (4 bytes big-endian).
     */
    private fun Map<Byte, ByteArray>.getTLVUInt(tag: Byte): UInt {
        val bytes = getTLVBytes(tag)
        if (bytes.size != 4) throw IOException("TLV tag 0x${tag.toString(16)}: expected 4 bytes, got ${bytes.size}")
        return bytes.readU32(0)
    }

    /**
     * Get a TLV value as String (UTF-8).
     */
    private fun Map<Byte, ByteArray>.getTLVString(tag: Byte): String {
        return getTLVBytes(tag).toString(Charsets.UTF_8)
    }

    /**
     * Get a TLV value as single byte.
     */
    private fun Map<Byte, ByteArray>.getTLVByte(tag: Byte): Byte {
        val bytes = getTLVBytes(tag)
        if (bytes.size != 1) throw IOException("TLV tag 0x${tag.toString(16)}: expected 1 byte, got ${bytes.size}")
        return bytes[0]
    }
}
