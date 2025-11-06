package com.revertron.mimir.ui

import android.annotation.SuppressLint
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.*
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

class ContactsAdapter(
    private var dataSet: List<ChatListItem>,
    private val onClick: View.OnClickListener,
    private val onLongClick: View.OnLongClickListener?
): RecyclerView.Adapter<ContactsAdapter.ViewHolder>() {

    private val timeFormatter = SimpleDateFormat.getTimeInstance(DateFormat.SHORT)
    private val dateFormatter = SimpleDateFormat.getDateInstance(DateFormat.SHORT)

    class ViewHolder(view: View): RecyclerView.ViewHolder(view) {
        val contactName: AppCompatTextView = view.findViewById(R.id.contact_name)
        val lastMessage: AppCompatTextView = view.findViewById(R.id.last_message)
        val lastMessageTime: AppCompatTextView = view.findViewById(R.id.last_message_time)
        val unreadCount: AppCompatTextView = view.findViewById(R.id.unread_count)
        val deliveredIcon: AppCompatImageView = view.findViewById(R.id.delivered_icon)
        val avatar: AppCompatImageView = view.findViewById(R.id.avatar)
        val groupChatIcon: AppCompatImageView = view.findViewById(R.id.group_chat_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.contact_item, parent, false)
        view.setOnClickListener(onClick)
        onLongClick?.let {
            view.setOnLongClickListener(it)
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = dataSet[position]

        // Set common fields
        holder.contactName.text = item.name.ifEmpty {
            holder.itemView.context.getString(R.string.unknown_nickname)
        }

        // Set last message time
        if (item.lastMessageTime > 0) {
            holder.lastMessageTime.visibility = View.VISIBLE
            val date = Date(item.lastMessageTime)
            val diff = Date().time - date.time
            if (diff > 86400 * 1000) {
                holder.lastMessageTime.text = dateFormatter.format(date)
            } else {
                holder.lastMessageTime.text = timeFormatter.format(date)
            }
        } else {
            holder.lastMessageTime.visibility = View.GONE
        }

        // Set unread count
        if (item.unreadCount > 0) {
            holder.unreadCount.text = item.unreadCount.toString()
            holder.unreadCount.visibility = View.VISIBLE
            holder.deliveredIcon.visibility = View.GONE
        } else {
            holder.unreadCount.visibility = View.GONE
        }

        // Handle specific item types
        when (item) {
            is ChatListItem.ContactItem -> bindContactItem(holder, item)
            is ChatListItem.GroupChatItem -> bindGroupChatItem(holder, item)
        }

        holder.itemView.tag = item
    }

    private fun bindContactItem(holder: ViewHolder, contact: ChatListItem.ContactItem) {
        // Hide group chat icon for regular contacts
        holder.groupChatIcon.visibility = View.GONE

        // Set last message
        holder.lastMessage.text = contact.lastMessage?.getText(holder.avatar.context) ?: ""

        // Set delivered icon (only for contacts, not group chats)
        if (contact.unreadCount == 0 && contact.lastMessage?.delivered != null) {
            if (contact.lastMessage?.delivered == true) {
                holder.deliveredIcon.setImageResource(R.drawable.ic_message_delivered)
            } else {
                holder.deliveredIcon.setImageResource(R.drawable.ic_message_not_sent)
            }
            holder.deliveredIcon.visibility = View.VISIBLE
        } else {
            holder.deliveredIcon.visibility = View.GONE
        }

        // Set avatar
        if (contact.avatar != null) {
            holder.avatar.clearColorFilter()
            holder.avatar.setImageDrawable(contact.avatar)
        } else {
            holder.avatar.setImageResource(R.drawable.button_rounded_white)
            val avatarColor = getAvatarColor(contact.pubkey)
            holder.avatar.setColorFilter(avatarColor, PorterDuff.Mode.MULTIPLY)
        }
    }

    private fun bindGroupChatItem(holder: ViewHolder, groupChat: ChatListItem.GroupChatItem) {
        // Show group chat icon
        holder.groupChatIcon.visibility = View.VISIBLE

        // Set last message (for group chats, we might want to show sender name in the future)
        holder.lastMessage.text = groupChat.lastMessageText ?: groupChat.description

        // Group chats don't show delivered icon
        holder.deliveredIcon.visibility = View.GONE

        // Set avatar
        if (groupChat.avatar != null) {
            holder.avatar.clearColorFilter()
            holder.avatar.setImageDrawable(groupChat.avatar)
        } else {
            holder.avatar.setImageResource(R.drawable.button_rounded_white)
            // Use chat ID for color generation
            val avatarColor = getAvatarColor(groupChat.chatId.toString().toByteArray())
            holder.avatar.setColorFilter(avatarColor, PorterDuff.Mode.MULTIPLY)
        }
    }

    override fun getItemCount(): Int {
        return dataSet.size
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setContacts(items: List<ChatListItem>) {
        dataSet = items
        notifyDataSetChanged()
    }

    fun setMessageDelivered(id: Long) {
        for ((index, item) in dataSet.withIndex()) {
            if (item is ChatListItem.ContactItem && item.lastMessage != null) {
                if (item.lastMessage?.id == id) {
                    item.lastMessage?.delivered = true
                    notifyItemChanged(index)
                }
            }
        }
    }
}