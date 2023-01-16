package com.revertron.mimir.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.*
import io.getstream.avatarview.AvatarView
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

class ContactsAdapter(private var dataSet: List<Contact>, private val onclick: View.OnClickListener, private val onlongclick: View.OnLongClickListener): RecyclerView.Adapter<ContactsAdapter.ViewHolder>() {

    private val timeFormatter = SimpleDateFormat.getTimeInstance(DateFormat.SHORT)
    private val dateFormatter = SimpleDateFormat.getDateInstance(DateFormat.SHORT)

    class ViewHolder(view: View): RecyclerView.ViewHolder(view) {
        val contactName: AppCompatTextView
        val lastMessage: AppCompatTextView
        val lastMessageTime: AppCompatTextView
        val unreadCount: AppCompatTextView
        val deliveredIcon: AppCompatImageView
        val avatar: AvatarView

        init {
            contactName = view.findViewById(R.id.contact_name)
            lastMessage = view.findViewById(R.id.last_message)
            lastMessageTime = view.findViewById(R.id.last_message_time)
            unreadCount = view.findViewById(R.id.unread_count)
            deliveredIcon = view.findViewById(R.id.delivered_icon)
            avatar = view.findViewById(R.id.avatar)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.contact_item, parent, false)
        view.setOnClickListener(onclick)
        view.setOnLongClickListener(onlongclick)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = dataSet[position]
        holder.contactName.text = contact.name.ifEmpty { holder.itemView.context.getString(R.string.unknown_nickname) }
        holder.lastMessage.text = contact.lastMessage.ifEmpty { "" }
        if (contact.lastMessageTime > 0) {
            holder.lastMessageTime.visibility = View.VISIBLE
            val date = Date(contact.lastMessageTime)
            val diff = Date().time - date.time
            if (diff > 86400 * 1000) {
                holder.lastMessageTime.text = dateFormatter.format(date)
            } else {
                holder.lastMessageTime.text = timeFormatter.format(date)
            }
        } else {
            holder.lastMessageTime.visibility = View.GONE
        }
        if (contact.unread > 0) {
            holder.unreadCount.text = contact.unread.toString()
            holder.unreadCount.visibility = View.VISIBLE
            holder.deliveredIcon.visibility = View.GONE
        } else {
            holder.unreadCount.visibility = View.GONE
            if (contact.lastMessageDelivered != null) {
                if (contact.lastMessageDelivered!!) {
                    holder.deliveredIcon.setImageResource(R.drawable.ic_message_delivered)
                } else {
                    holder.deliveredIcon.setImageResource(R.drawable.ic_message_not_sent)
                }
                holder.deliveredIcon.visibility = View.VISIBLE
            } else {
                holder.deliveredIcon.visibility = View.GONE
            }
        }
        holder.itemView.tag = contact
        val initials = getInitials(contact)
        holder.avatar.avatarInitials = initials
        val avatarColor = getAvatarColor(contact.pubkey)
        holder.avatar.avatarInitialsBackgroundColor = avatarColor
        if (isColorDark(avatarColor)) {
            holder.avatar.avatarInitialsTextColor = 0xFFFFFFFF.toInt()
        } else {
            holder.avatar.avatarInitialsTextColor = 0xFF000000.toInt()
        }
    }

    override fun getItemCount(): Int {
        return dataSet.size
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setContacts(contacts: List<Contact>) {
        dataSet = contacts
        notifyDataSetChanged()
    }
}