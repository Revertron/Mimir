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

        // Timeouts
        private const val REQ_TIMEOUT_MS: Long = 10_000
        private const val PING_DEADLINE_MS: Long = 240_000 // Ping every 4 minutes, QUIC timeout is 5 minutes
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
            // 1) Protocol selector byte (must be 0x00)
            connection.write(byteArrayOf(PROTO_CLIENT))

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
        } catch (e: IOException) {
            Log.e(TAG, "Error reading from pipe: {$e}")
        }
    }

    // === Public API (thread/handler friendly, blocking with timeouts) ===

    /** Performs GET_NONCE + AUTH; called automatically on start(). */
    fun authenticate(): Boolean {
        // GET_NONCE(pubkey) -> nonce(32)
        val nonce = getNonce(pubkey) ?: return false

        // AUTH(pubkey, nonce, signature)
        val sig = Sign.sign(keyPair.private, nonce) ?: return false
        val payload = ByteArrayOutputStream().apply {
            write(pubkey)
            write(nonce)
            write(sig)
        }.toByteArray()
        val resp = request(CMD_AUTH, payload) ?: return false
        if (resp.status != STATUS_OK) {
            Log.w(TAG, "AUTH error: ${resp.errorString()}")
            return false
        }
        return true
    }

    /** Creates a chat. Satisfies the server's signature filter: sig[0]==0 && sig[1]==0. */
    fun createChat(name: String, description: String, avatar: ByteArray? = null): Long {

        require(name.toByteArray(Charsets.UTF_8).size <= 20) { "name > 20 bytes" }
        require(description.toByteArray(Charsets.UTF_8).size <= 200) { "description > 200 bytes" }
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

        val payload = ByteArrayOutputStream().apply {
            write(pubkey)
            write(nonce)
            write(counterBytes) // Or the msg
            write(sig)
            writeString(name)
            writeString(description)
            writeBlob(avatarBytes)
        }.toByteArray()

        val resp = request(CMD_CREATE_CHAT, payload)
            ?: throw MediatorException("createChat timeout")
        if (resp.status != STATUS_OK) throw resp.asException("createChat failed: " + resp.errorString())
        return resp.payload.readLong(0)
    }

    /** Deletes a chat. Handler reads only chat_id(u64) (verified). */
    fun deleteChat(chatId: Long): Boolean {
        val payload = ByteArray(8).apply { putU64BE(0, chatId) }
        val resp = request(CMD_DELETE_CHAT, payload) ?: return false
        if (resp.status != STATUS_OK) throw resp.asException("deleteChat failed")
        // server returns 1 byte 1 on success; we tolerate OK w/empty as well
        return true
    }

    fun addUser(chatId: Long, userPubKey: ByteArray) {
        require(userPubKey.size == 32) { "userPubKey must be 32 bytes" }
        val payload = ByteArrayOutputStream().apply {
            writeLong(chatId)
            write(userPubKey)
        }.toByteArray()
        val resp = request(CMD_ADD_USER, payload) ?: throw MediatorException("addUser timeout")
        if (resp.status != STATUS_OK) throw resp.asException("addUser failed")
    }

    fun deleteUser(chatId: Long, userPubKey: ByteArray) {
        require(userPubKey.size == 32) { "userPubKey must be 32 bytes" }
        val payload = ByteArrayOutputStream().apply {
            writeLong(chatId)
            write(userPubKey)
        }.toByteArray()
        val resp = request(CMD_DELETE_USER, payload) ?: throw MediatorException("deleteUser timeout")
        if (resp.status != STATUS_OK) throw resp.asException("deleteUser failed")
    }

    fun leaveChat(chatId: Long) {
        val payload = ByteArray(8).apply { putU64BE(0, chatId) }
        val resp = request(CMD_LEAVE_CHAT, payload) ?: throw MediatorException("leaveChat timeout")
        if (resp.status != STATUS_OK) throw resp.asException("leaveChat failed")
    }

    fun subscribe(chatId: Long): Long {
        val payload = ByteArray(8).apply { putU64BE(0, chatId) }
        val resp = request(CMD_SUBSCRIBE, payload) ?: throw MediatorException("subscribe timeout")
        if (resp.status != STATUS_OK) throw resp.asException("subscribe failed")

        // Parse last_message_id from response payload (u64, 8 bytes)
        if (resp.payload.size < 8) throw MediatorException("subscribe response missing last_message_id")
        val lastMessageId = resp.payload.readLong(0)

        // Fetch member list and member info after successful subscription
        thread(name = "FetchMembers-$chatId") {
            fetchAndSaveMembers(chatId)
        }

        return lastMessageId
    }

    /**
     * Fetches ALL members for a chat from mediator and saves to local storage.
     * Uses GET_MEMBERS_INFO which returns all members in one call:
     * - Members with updated info get full encrypted profiles
     * - Members without updates get just pubkey with null info (infoLen=0)
     * This provides a consistent snapshot of membership + selective info updates.
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
            // GET_MEMBERS_INFO now returns ALL members (timestamp filters info, not members)
            val members = getMembersInfo(chatId, lastUpdated)
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
                        Log.d(TAG, "Parsed nicknameLen=$nicknameLen, offset after=$offset, remaining=${decryptedBlob.size - offset}")
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
                        Log.d(TAG, "Saved member info for ${Hex.toHexString(member.pubkey).take(8)}... in chat $chatId")
                    } else {
                        // Member exists but no info update - save with null profile
                        storage.updateGroupMemberInfo(chatId, member.pubkey, null, null, null)
                        Log.d(TAG, "Saved member ${Hex.toHexString(member.pubkey).take(8)}... (no info) in chat $chatId")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing member ${Hex.toHexString(member.pubkey).take(8)}... for chat $chatId", e)
                }
            }

            Log.i(TAG, "Finished saving ${members.size} member(s) for chat $chatId")
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching members for chat $chatId", e)
        }
    }

    fun getUserChats(): LongArray {
        val resp = request(CMD_GET_USER_CHATS, ByteArray(0)) ?: throw MediatorException("getUserChats timeout")
        if (resp.status != STATUS_OK) throw resp.asException("getUserChats failed")
        val cnt = resp.payload.readU32(0).toInt()
        val out = LongArray(cnt)
        var off = 4
        repeat(cnt) { i ->
            out[i] = resp.payload.readLong(off).toLong()
            off += 8
        }
        return out
    }

    /** Sends a message and returns server-assigned incremental message_id. */
    fun sendMessage(chatId: Long, guid: Long, blob: ByteArray): Long {
        val payload = ByteArrayOutputStream().apply {
            writeLong(chatId)
            writeLong(guid)
            writeBlob(blob)
        }.toByteArray()
        val resp = request(CMD_SEND_MESSAGE, payload) ?: throw MediatorException("sendMessage timeout")
        if (resp.status != STATUS_OK) throw resp.asException("sendMessage failed")
        return resp.payload.readLong(0).toLong()
    }

    fun getLastMessageId(chatId: Long): Long {
        val payload = ByteArray(8).apply { putU64BE(0, chatId) }
        val resp = request(CMD_GET_LAST_MESSAGE_ID, payload) ?: throw MediatorException("getLastMessageId timeout")
        if (resp.status != STATUS_OK) throw resp.asException("getLastMessageId failed")
        return resp.payload.readLong(0)
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

        val payload = ByteArrayOutputStream().apply {
            writeLong(chatId)
            writeLong(sinceMessageId)
            write(byteArrayOf(
                ((limit ushr 24) and 0xFF).toByte(),
                ((limit ushr 16) and 0xFF).toByte(),
                ((limit ushr 8) and 0xFF).toByte(),
                (limit and 0xFF).toByte()
            ))
        }.toByteArray()

        val resp = request(CMD_GET_MESSAGES_SINCE, payload) ?: throw MediatorException("getMessagesSince timeout")
        if (resp.status != STATUS_OK) throw resp.asException("getMessagesSince failed")

        // Parse response: [count(u32)][[chatId(u64)][msgId(u64)][guid(u64)][author(32)][blobLen(u32)][blob]...]
        var off = 0
        val count = resp.payload.readU32(off).toInt(); off += 4

        val messages = mutableListOf<MessagePayload>()
        repeat(count) {
            val responseChatId = resp.payload.readLong(off); off += 8  // For validation
            val msgId = resp.payload.readLong(off); off += 8
            val guid = resp.payload.readLong(off); off += 8
            val author = resp.payload.copyOfRange(off, off + 32); off += 32
            val blobLen = resp.payload.readU32(off).toInt(); off += 4
            val data = if (blobLen > 0) resp.payload.copyOfRange(off, off + blobLen) else ByteArray(0); off += blobLen

            // Validate chat ID matches
            if (responseChatId != chatId) {
                Log.w(TAG, "getMessagesSince: chatId mismatch (expected $chatId, got $responseChatId)")
            }

            messages.add(MessagePayload(msgId, guid, author, data))
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

        val payload = ByteArrayOutputStream().apply {
            writeLong(chatId)
            write(toPubkey)
            writeBlob(encryptedData)
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

        val payload = ByteArrayOutputStream().apply {
            this.writeLong(inviteId)
            write(byteArrayOf(accepted.toByte()))
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

        // Build request payload
        val payload = ByteArrayOutputStream().apply {
            writeLong(chatId)
            this.writeLong(timestamp)
            writeBlob(encryptedBlob)
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
        val payload = ByteArrayOutputStream().apply {
            writeLong(chatId)
            this.writeLong(sinceTimestamp)
        }.toByteArray()

        val resp = request(CMD_GET_MEMBERS_INFO, payload) ?: throw MediatorException("getMembersInfo timeout")
        if (resp.status != STATUS_OK) throw resp.asException("getMembersInfo failed")

        // Parse response: [count(u32)][[pubkey(32)][infoLen(u32)][encryptedInfo][timestamp(u64)]...]
        var off = 0
        val count = resp.payload.readU32(off).toInt(); off += 4

        val members = mutableListOf<MemberInfo>()
        repeat(count) {
            val pubkey = resp.payload.copyOfRange(off, off + 32); off += 32
            val infoLen = resp.payload.readU32(off).toInt(); off += 4
            val encryptedInfo = if (infoLen > 0) resp.payload.copyOfRange(off, off + infoLen) else null; off += infoLen
            val timestamp = resp.payload.readLong(off).toLong(); off += 8

            members.add(MemberInfo(pubkey, encryptedInfo, timestamp))
        }

        Log.i(TAG, "Retrieved ${members.size} member(s) info for chat $chatId")
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
        val payload = ByteArray(8).apply { putU64BE(0, chatId) }

        val resp = request(CMD_GET_MEMBERS, payload) ?: throw MediatorException("getMembers timeout")
        if (resp.status != STATUS_OK) throw resp.asException("getMembers failed")

        // Parse response: [count(u32)][[pubkey(32)][perms(1)][online(1)] repeated]
        var off = 0
        val count = resp.payload.readU32(off).toInt(); off += 4

        val members = mutableListOf<Member>()
        repeat(count) {
            val pubkey = resp.payload.copyOfRange(off, off + 32); off += 32
            val perms = resp.payload[off].toInt() and 0xFF; off += 1
            val online = resp.payload[off].toInt() and 0xFF; off += 1

            members.add(Member(pubkey, perms, online == 1))
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
            sleep(100)
            if (!App.app.online || !running) {
                Log.i(TAG, "We are offline, stopping client")
                stopClient()
                break
            }
            if (System.currentTimeMillis() - lastActivityTime > PING_DEADLINE_MS) {
                Log.i(TAG, "Sending ping")
                try {
                    if (!sendPing()) {
                        Log.i(TAG, "Connection broken")
                        stopClient()
                    }
                } catch (e: MediatorException) {
                    Log.i(TAG, "Connection broken: $e")
                    stopClient()
                }
            }
        }
    }

    // === Internal: reader & request plumbing ===

    private fun readerLoop() {
        val dis = DataInputStream(ConnectionInputStream(connection))
        try {
            while (running) {
                Log.i(TAG, "Reading from socket...")
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
                    // payload: [chat_id(u64)][message_id(u64)][guid(u64)][pubkey(32)][len(u32)][blob]
                    var off = 0
                    val chatId = payload.readLong(off); off += 8
                    val msgId = payload.readLong(off); off += 8
                    val guid = payload.readLong(off); off += 8
                    val author = payload.copyOfRange(off, off + 32); off += 32
                    val sz = payload.readU32(off).toInt(); off += 4
                    val data = if (sz > 0) payload.copyOfRange(off, off + sz) else ByteArray(0)

                    // Check if this is a system message (author == mediator pubkey)
                    if (author.contentEquals(mediatorPubkey)) {
                        // System message: unencrypted, format is [event_code(1)][...event data...]
                        Log.d(TAG, "Received system message for chat $chatId: event_code=${if (data.isNotEmpty()) data[0].toInt() and 0xFF else 0}")
                        listener.onSystemMessage(chatId, msgId, guid, data)
                    } else {
                        // Regular user message
                        listener.onPushMessage(chatId, msgId, guid, author, data)
                    }
                    continue
                }

                // Push invites: status=OK, reqId=0x41
                if (status == STATUS_OK && (reqId.toInt() and 0xFFFF) == CMD_GOT_INVITE) {
                    // payload: [inviteId(u64)][chatId(u64)][fromPubkey(32)][timestamp(u64)]
                    //          [nameLen(u16)][name][descLen(u16)][desc][avatarLen(u32)][avatar]
                    //          [dataLen(u32)][encryptedData]
                    try {
                        var off = 0
                        val inviteId = payload.readLong(off); off += 8
                        val chatId = payload.readLong(off); off += 8
                        val fromPubkey = payload.copyOfRange(off, off + 32); off += 32
                        val timestamp = payload.readLong(off); off += 8

                        val nameLen = payload.readU16(off); off += 2
                        val name = String(payload, off, nameLen, Charsets.UTF_8); off += nameLen

                        val descLen = payload.readU16(off); off += 2
                        val desc = if (descLen > 0) String(payload, off, descLen, Charsets.UTF_8) else ""; off += descLen

                        val avatarLen = payload.readU32(off).toInt(); off += 4
                        val avatar = if (avatarLen > 0) payload.copyOfRange(off, off + avatarLen) else null; off += avatarLen

                        val dataLen = payload.readU32(off).toInt(); off += 4
                        val encryptedData = payload.copyOfRange(off, off + dataLen)

                        listener.onPushInvite(inviteId, chatId, fromPubkey, timestamp.toLong(), name, desc, avatar, encryptedData)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing invite push", e)
                    }
                    continue
                }

                // Member info request: status=OK, reqId=0x51
                if (status == STATUS_OK && (reqId.toInt() and 0xFFFF) == CMD_REQUEST_MEMBER_INFO) {
                    // payload: [chatId(u64)][lastUpdate(u64)]
                    try {
                        var off = 0
                        val chatId = payload.readLong(off); off += 8
                        val lastUpdate = payload.readLong(off).toLong()

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
                            Log.d(TAG, "No member info update needed for chat $chatId")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling member info request", e)
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
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "Reader error", e)
                listener.onDisconnected(e)
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

        // Build request frame: [ver][cmd][reqId][len][payload]
        val header = ByteArrayOutputStream(1 + 1 + 2 + 4 + payload.size)
        header.write(byteArrayOf(VERSION))
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
        val resp = request(CMD_GET_NONCE, pubkey) ?: return null
        if (resp.status != STATUS_OK) {
            Log.w(TAG, "GET_NONCE error: ${resp.errorString()}")
            return null
        }
        if (resp.payload.size != 32) {
            Log.w(TAG, "GET_NONCE bad size: ${resp.payload.size}")
            return null
        }
        return resp.payload
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
        val author: ByteArray,
        val data: ByteArray
    )

    interface MediatorListener {
        /** Called once the connection is authenticated and ready. */
        fun onConnected()

        /**
         * Server push with new message.
         */
        fun onPushMessage(chatId: Long, messageId: Long, guid: Long, author: ByteArray, data: ByteArray)

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
         * @param body Unencrypted system message body
         */
        fun onSystemMessage(chatId: Long, messageId: Long, guid: Long, body: ByteArray)

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
}
