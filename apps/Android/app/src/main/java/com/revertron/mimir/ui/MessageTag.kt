package com.revertron.mimir.ui

/**
 * Data class to hold message metadata and touch coordinates for context menu display.
 *
 * @property messageId The database ID of the message
 * @property guid The global unique identifier of the message (used for replies)
 * @property touchX The raw X coordinate where the user touched the message
 * @property touchY The raw Y coordinate where the user touched the message
 */
data class MessageTag(
    val messageId: Long,
    val guid: Long,
    val touchX: Int = 0,
    val touchY: Int = 0
)