package com.revertron.mimir.ui

import android.graphics.drawable.Drawable

/**
 * Represents a group chat with mediator server support.
 *
 * @param chatId Server-assigned unique chat ID (Long from mediator)
 * @param name Group chat name (max 20 bytes UTF-8)
 * @param description Group chat description (max 200 bytes UTF-8)
 * @param avatar Group avatar image
 * @param lastMessageText Last message preview text
 * @param lastMessageTime Timestamp of last message
 * @param unreadCount Number of unread messages
 * @param memberCount Number of members in the group
 * @param isOwner Whether current user is the owner/creator
 */
data class GroupChat(
    val chatId: Long,
    val name: String,
    val description: String,
    var avatar: Drawable?,
    var lastMessageText: String?,
    var lastMessageTime: Long,
    var unreadCount: Int,
    var memberCount: Int,
    val isOwner: Boolean
)