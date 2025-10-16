package com.revertron.mimir.net

class WaitBox<T> {
    private val latch = java.util.concurrent.CountDownLatch(1)
    @Volatile private var result: T? = null
    @Volatile private var error: Exception? = null

    fun complete(value: T) {
        result = value
        latch.countDown()
    }

    fun completeExceptionally(e: Exception) {
        error = e
        latch.countDown()
    }

    fun await(timeoutMs: Long): T? {
        if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) return null
        error?.let { throw it }
        return result
    }
}
