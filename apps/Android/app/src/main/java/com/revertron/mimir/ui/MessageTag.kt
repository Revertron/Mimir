package com.revertron.mimir.ui

/**
 * Data class to hold message metadata and touch coordinates for context menu display.
 *
 * @property messageId The database ID of the message
 * @property guid The global unique identifier of the message (used for replies)
 * @property messageType The type of the message (0=text, 1=image, 2=call, 3=file, etc.)
 * @property touchX The raw X coordinate where the user touched the message
 * @property touchY The raw Y coordinate where the user touched the message
 */
data class MessageTag(
    val messageId: Long,
    val guid: Long,
    val messageType: Int = 0,
    val touchX: Int = 0,
    val touchY: Int = 0
)