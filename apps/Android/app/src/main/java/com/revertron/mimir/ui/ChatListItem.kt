package com.revertron.mimir.ui

import android.graphics.drawable.Drawable
import com.revertron.mimir.App
import com.revertron.mimir.storage.SqlStorage

/**
 * Sealed class representing items in the main chat list (contacts and group chats).
 */
sealed class ChatListItem {
    abstract val id: Long
    abstract val name: String
    abstract val avatar: Drawable?
    abstract val lastMessageText: String?
    abstract val lastMessageTime: Long
    abstract val unreadCount: Int
    abstract val draft: SqlStorage.Draft?

    /**
     * Individual contact chat item.
     */
    data class ContactItem(
        override val id: Long,
        val pubkey: ByteArray,
        override val name: String,
        val lastMessage: SqlStorage.Message?,
        override val unreadCount: Int,
        override val avatar: Drawable?,
        override val draft: SqlStorage.Draft? = null
    ) : ChatListItem() {
        override val lastMessageText: String?
            get() = lastMessage?.getText(App.app.applicationContext)

        override val lastMessageTime: Long
            get() = lastMessage?.time ?: 0
    }

    /**
     * Group chat item.
     */
    data class GroupChatItem(
        override val id: Long,  // Use chatId as Long for ID
        val chatId: Long,
        override val name: String,
        val description: String,
        val mediatorAddress: ByteArray,
        val memberCount: Int,
        val isOwner: Boolean,
        override val avatar: Drawable?,
        override val lastMessageText: String?,
        override val lastMessageTime: Long,
        override val unreadCount: Int,
        override val draft: SqlStorage.Draft? = null
    ) : ChatListItem()

    /**
     * Saved messages chat item (virtual contact for saving messages to self).
     */
    data class SavedMessagesItem(
        override val id: Long = SqlStorage.SAVED_MESSAGES_CONTACT_ID,
        override val name: String,
        override val avatar: Drawable?,
        override val lastMessageText: String?,
        override val lastMessageTime: Long,
        override val unreadCount: Int = 0,
        override val draft: SqlStorage.Draft? = null
    ) : ChatListItem()
}