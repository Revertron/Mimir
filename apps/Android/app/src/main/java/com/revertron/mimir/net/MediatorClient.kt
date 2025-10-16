package com.revertron.mimir.net

import android.util.Log
import com.revertron.mimir.sec.Sign
import com.revertron.mimir.yggmobile.Connection
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
    private val listener: MediatorListener
) : Thread(TAG) {

    companion object {
        private const val TAG = "MediatorClient"

        // Protocol constants (verified in mediator.go)
        private const val VERSION: Byte = 1
        private const val PROTO_CLIENT: Byte = 0x00

        private const val STATUS_OK: Int = 0x00
        private const val STATUS_ERR: Int = 0x01

        // Command codes (verified in mediator.go)
        private const val CMD_GET_NONCE: Int = 0x01
        private const val CMD_AUTH: Int = 0x02
        private const val CMD_CREATE_CHAT: Int = 0x10
        private const val CMD_DELETE_CHAT: Int = 0x11
        private const val CMD_ADD_USER: Int = 0x20
        private const val CMD_DELETE_USER: Int = 0x21
        private const val CMD_LEAVE_CHAT: Int = 0x22
        private const val CMD_GET_USER_CHATS: Int = 0x23
        private const val CMD_SEND_MESSAGE: Int = 0x30
        private const val CMD_DELETE_MESSAGE: Int = 0x31
        private const val CMD_GET_MESSAGE: Int = 0x32
        private const val CMD_GET_LAST_MESSAGE_ID: Int = 0x33
        private const val CMD_GOT_MESSAGE: Int = 0x34 // appears in response.reqId for pushes
        private const val CMD_SUBSCRIBE: Int = 0x35

        // Timeouts
        private const val REQ_TIMEOUT_MS: Long = 10_000
        private const val READ_DEADLINE_MS: Long = 600_000 // server sets read deadline to 10 minutes per frame
    }

    private val rng = SecureRandom()
    private val pubkey: ByteArray =
        (keyPair.public as Ed25519PublicKeyParameters).encoded

    @Volatile
    private var running = true

    // Request → future map
    private val pending = ConcurrentHashMap<Short, WaitBox<Response>>()

    // Reader thread (uses DataInputStream-like access)
    private lateinit var reader: Thread

    // Single write lock to serialize frames
    private val writeLock = Any()

    override fun run() {
        try {
            // 1) Protocol selector byte (must be 0x00)
            connection.write(byteArrayOf(PROTO_CLIENT))

            // 2) Start background reader
            reader = thread(name = "$TAG-Reader", isDaemon = true) {
                readerLoop()
            }

            // 3) Authenticate before exposing the connection
            val ok = authenticate()
            if (!ok) {
                throw MediatorException("Authentication failed")
            }
            listener.onConnected()

            // 4) Liveness loop until closed
            while (running) {
                sleep(200L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client loop error", e)
            listener.onDisconnected(e)
        } finally {
            closeQuietly()
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
    fun createChat(
        name: String,
        description: String,
        avatar: ByteArray? = null
    ): ULong {
        require(name.toByteArray(Charsets.UTF_8).size <= 20) { "name > 20 bytes" }
        require(description.toByteArray(Charsets.UTF_8).size <= 200) { "description > 200 bytes" }
        val avatarBytes = avatar ?: ByteArray(0)
        require(avatarBytes.size <= 200 * 1024) { "avatar > 200KB" }

        // Server expects nonce from DB: GET_NONCE(owner_pubkey)
        val nonce = getNonce(pubkey) ?: throw MediatorException("Failed to get nonce")

        // We must satisfy signature filter by picking a random 32-byte key and signing (nonce||key)
        // until sig[0]==0 && sig[1]==0 (verified in handleCreateChat).
        // Warning: probabilistic loop — usually quick, but keep a sane iteration cap.
        var chatKey: ByteArray
        var sig: ByteArray
        var attempts = 0
        do {
            chatKey = ByteArray(32).also { rng.nextBytes(it) }
            val msg = nonce + chatKey
            sig = Sign.sign(keyPair.private, msg) ?: throw MediatorException("Sign failed")
            attempts++
            if (attempts % 1000 == 0) {
                Log.w(TAG, "createChat signature filter still not satisfied after $attempts tries")
            }
        } while (!(sig.size == 64 && sig[0].toInt() == 0 && sig[1].toInt() == 0))

        val payload = ByteArrayOutputStream().apply {
            write(pubkey)
            write(nonce)
            write(chatKey)
            write(sig)
            writeString(name)
            writeString(description)
            writeBlob(avatarBytes)
        }.toByteArray()

        val resp = request(CMD_CREATE_CHAT, payload)
            ?: throw MediatorException("createChat timeout")
        if (resp.status != STATUS_OK) throw resp.asException("createChat failed")
        return resp.payload.readU64(0)
    }

    /** Deletes a chat. Handler reads only chat_id(u64) (verified). */
    fun deleteChat(chatId: ULong): Boolean {
        val payload = ByteArray(8).apply { putU64BE(0, chatId) }
        val resp = request(CMD_DELETE_CHAT, payload) ?: return false
        if (resp.status != STATUS_OK) throw resp.asException("deleteChat failed")
        // server returns 1 byte 1 on success; we tolerate OK w/empty as well
        return true
    }

    fun addUser(chatId: ULong, userPubKey: ByteArray) {
        require(userPubKey.size == 32) { "userPubKey must be 32 bytes" }
        val payload = ByteArrayOutputStream().apply {
            writeU64(chatId)
            write(userPubKey)
        }.toByteArray()
        val resp = request(CMD_ADD_USER, payload) ?: throw MediatorException("addUser timeout")
        if (resp.status != STATUS_OK) throw resp.asException("addUser failed")
    }

    fun deleteUser(chatId: ULong, userPubKey: ByteArray) {
        require(userPubKey.size == 32) { "userPubKey must be 32 bytes" }
        val payload = ByteArrayOutputStream().apply {
            writeU64(chatId)
            write(userPubKey)
        }.toByteArray()
        val resp = request(CMD_DELETE_USER, payload) ?: throw MediatorException("deleteUser timeout")
        if (resp.status != STATUS_OK) throw resp.asException("deleteUser failed")
    }

    fun leaveChat(chatId: ULong) {
        val payload = ByteArray(8).apply { putU64BE(0, chatId) }
        val resp = request(CMD_LEAVE_CHAT, payload) ?: throw MediatorException("leaveChat timeout")
        if (resp.status != STATUS_OK) throw resp.asException("leaveChat failed")
    }

    fun subscribe(chatId: ULong) {
        val payload = ByteArray(8).apply { putU64BE(0, chatId) }
        val resp = request(CMD_SUBSCRIBE, payload) ?: throw MediatorException("subscribe timeout")
        if (resp.status != STATUS_OK) throw resp.asException("subscribe failed")
    }

    fun getUserChats(): ULongArray {
        val resp = request(CMD_GET_USER_CHATS, ByteArray(0)) ?: throw MediatorException("getUserChats timeout")
        if (resp.status != STATUS_OK) throw resp.asException("getUserChats failed")
        val cnt = resp.payload.readU32(0).toInt()
        val out = ULongArray(cnt)
        var off = 4
        repeat(cnt) { i ->
            out[i] = resp.payload.readU64(off)
            off += 8
        }
        return out
    }

    /** Sends a message and returns server-assigned incremental message_id. */
    fun sendMessage(chatId: ULong, guid: ULong, blob: ByteArray): ULong {
        val payload = ByteArrayOutputStream().apply {
            writeU64(chatId)
            writeU64(guid)
            writeBlob(blob)
        }.toByteArray()
        val resp = request(CMD_SEND_MESSAGE, payload) ?: throw MediatorException("sendMessage timeout")
        if (resp.status != STATUS_OK) throw resp.asException("sendMessage failed")
        return resp.payload.readU64(0)
    }

    /** Fetch a message blob by message_id for a given chat. */
    fun getMessage(chatId: ULong, messageId: ULong): MessagePayload {
        val payload = ByteArrayOutputStream().apply {
            writeU64(chatId)
            writeU64(messageId)
        }.toByteArray()
        val resp = request(CMD_GET_MESSAGE, payload) ?: throw MediatorException("getMessage timeout")
        if (resp.status != STATUS_OK) throw resp.asException("getMessage failed")

        var off = 0
        val id = resp.payload.readU64(off); off += 8
        val guid = resp.payload.readU64(off); off += 8
        val sz = resp.payload.readU32(off).toInt(); off += 4
        val data = resp.payload.copyOfRange(off, off + sz)
        return MessagePayload(id, guid, data)
    }

    fun getLastMessageId(chatId: ULong): ULong {
        val payload = ByteArray(8).apply { putU64BE(0, chatId) }
        val resp = request(CMD_GET_LAST_MESSAGE_ID, payload) ?: throw MediatorException("getLastMessageId timeout")
        if (resp.status != STATUS_OK) throw resp.asException("getLastMessageId failed")
        return resp.payload.readU64(0)
    }

    fun stopClient() {
        running = false
        closeQuietly()
        interrupt()
    }

    // === Internal: reader & request plumbing ===

    private fun readerLoop() {
        val dis = DataInputStream(ConnectionInputStream(connection)) // mirrors your ConnectionHandler I/O
        try {
            while (running) {
                // Response header: [status:1][reqId:2][len:4]
                val status = dis.readUnsignedByte()
                val reqId = dis.readShort()
                val len = dis.readInt()
                if (len < 0) throw MediatorException("negative payload length")
                val payload = ByteArray(len)
                if (len > 0) dis.readFully(payload)

                // Push messages: status=OK, reqId=0x34
                if (status == STATUS_OK && (reqId.toInt() and 0xFFFF) == CMD_GOT_MESSAGE) {
                    // payload: [chat_id(u64)][message_id(u64)][guid(u64)][len(u32)][blob]
                    var off = 0
                    val chatId = payload.readU64(off); off += 8
                    val msgId = payload.readU64(off); off += 8
                    val guid = payload.readU64(off); off += 8
                    val sz = payload.readU32(off).toInt(); off += 4
                    val data = if (sz > 0) payload.copyOfRange(off, off + sz) else ByteArray(0)

                    listener.onPushMessage(chatId, msgId, guid, data)
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
        header.write(payload)

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
        } catch (e: Exception) {
            pending.remove(reqId)
            throw e
        }
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
        val messageId: ULong,
        val guid: ULong,
        val data: ByteArray
    )

    interface MediatorListener {
        /** Called once the connection is authenticated and ready. */
        fun onConnected()

        /**
         * Server push with new message.
         */
        fun onPushMessage(chatId: ULong, messageId: ULong, guid: ULong, data: ByteArray)

        /** Connection ended or failed. */
        fun onDisconnected(error: Exception)
    }

    class MediatorException(message: String, cause: Throwable? = null) : Exception(message, cause)

    // Byte helpers

    private fun ByteArray.readU64(offset: Int): ULong {
        var i = offset
        val v = ((this[i++].toLong() and 0xFF) shl 56) or
                ((this[i++].toLong() and 0xFF) shl 48) or
                ((this[i++].toLong() and 0xFF) shl 40) or
                ((this[i++].toLong() and 0xFF) shl 32) or
                ((this[i++].toLong() and 0xFF) shl 24) or
                ((this[i++].toLong() and 0xFF) shl 16) or
                ((this[i++].toLong() and 0xFF) shl 8) or
                (this[i].toLong() and 0xFF)
        return v.toULong()
    }

    private fun ByteArray.readU32(offset: Int): UInt {
        var i = offset
        val v = ((this[i++].toInt() and 0xFF) shl 24) or
                ((this[i++].toInt() and 0xFF) shl 16) or
                ((this[i++].toInt() and 0xFF) shl 8) or
                (this[i].toInt() and 0xFF)
        return v.toUInt()
    }

    private fun ByteArrayOutputStream.writeU64(v: ULong) {
        write(byteArrayOf(
            ((v shr 56) and 0xFFu).toByte(),
            ((v shr 48) and 0xFFu).toByte(),
            ((v shr 40) and 0xFFu).toByte(),
            ((v shr 32) and 0xFFu).toByte(),
            ((v shr 24) and 0xFFu).toByte(),
            ((v shr 16) and 0xFFu).toByte(),
            ((v shr 8) and 0xFFu).toByte(),
            ((v shr 0) and 0xFFu).toByte()
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

    private fun ByteArray.putU64BE(offset: Int, v: ULong) {
        var i = offset
        this[i++] = ((v shr 56) and 0xFFu).toByte()
        this[i++] = ((v shr 48) and 0xFFu).toByte()
        this[i++] = ((v shr 40) and 0xFFu).toByte()
        this[i++] = ((v shr 32) and 0xFFu).toByte()
        this[i++] = ((v shr 24) and 0xFFu).toByte()
        this[i++] = ((v shr 16) and 0xFFu).toByte()
        this[i++] = ((v shr 8) and 0xFFu).toByte()
        this[i]   = ((v shr 0) and 0xFFu).toByte()
    }
}
