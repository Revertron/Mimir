package com.revertron.mimir.net

import com.revertron.mimir.yggmobile.Connection
import java.io.InputStream

class ConnectionInputStream(private val conn: Connection, bufferSize: Int = 16384) : InputStream() {

    private val buf = ByteArray(bufferSize)
    private var pos = 0
    private var count = 0          // valid bytes in buf

    override fun read(): Int {
        while (pos >= count) {
            if (!fillBuffer()) {
                return -1
            }
        }
        return buf[pos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var written = 0
        while (written < len) {
            if (pos >= count && !fillBuffer()) {
                return if (written == 0) -1 else written
            }
            val copy = minOf(len - written, count - pos)
            System.arraycopy(buf, pos, b, off + written, copy)
            pos += copy
            written += copy
        }
        return written
    }

    /* --------- available() --------- */
    override fun available(): Int {
        // If buffer empty, attempt one non-blocking read
        if (pos >= count) {
            try {
                val n = conn.readWithTimeout(buf, 10).toInt()
                if (n > 0) {
                    pos = 0
                    count = n
                }
            } catch (_: Exception) { /* ignore */ }
        }
        return count - pos
    }

    private fun fillBuffer(): Boolean {
        pos = 0
        count = 0
        while (true) {
            // Check if connection is dead before attempting to read
            if (!conn.isAlive) {
                return false
            }

            // Check if thread has been interrupted (from stopClient())
            if (Thread.currentThread().isInterrupted) {
                return false
            }

            val read = try {
                conn.readWithTimeout(buf, 0).toInt()
            } catch (e: Exception) {
                if (e.message?.contains("deadline exceeded") == true) {
                    continue    // no data yet; retry
                }
                throw e        // real error -> let caller fail
            }

            when {
                read > 0 -> {
                    count = read
                    return true
                }
                read == 0 -> continue          // defensive: keep waiting
                else -> return false           // negative => connection closed
            }
        }
    }
}