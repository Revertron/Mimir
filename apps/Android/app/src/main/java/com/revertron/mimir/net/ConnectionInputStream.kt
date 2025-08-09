package com.revertron.mimir.net

import com.revertron.mimir.yggmobile.Connection
import java.io.InputStream

class ConnectionInputStream(
    private val conn: Connection,
    bufferSize: Int = 4096
) : InputStream() {

    private val buf = ByteArray(bufferSize)
    private var pos = 0
    private var count = 0          // valid bytes in buf

    /* --------- single-byte read --------- */
    override fun read(): Int {
        if (pos >= count) refill()
        return if (count == 0) -1 else buf[pos++].toInt() and 0xFF
    }

    /* --------- bulk read --------- */
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var remaining = len
        var dstOff = off
        while (remaining > 0) {
            if (pos >= count) refill()
            if (count == 0) break           // EOF
            val n = minOf(remaining, count - pos)
            System.arraycopy(buf, pos, b, dstOff, n)
            pos += n; dstOff += n; remaining -= n
        }
        return if (dstOff == off && count == 0) -1 else len - remaining
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

    /* --------- refill helper --------- */
    private fun refill() {
        if (pos >= count) {
            try {
                count = conn.readWithTimeout(buf, 200).toInt()
                pos = 0
            } catch (_: Exception) {
                count = 0
            }
        }
    }
}