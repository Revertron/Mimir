package com.revertron.mimir.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.R
import io.getstream.avatarview.AvatarView

class ContactsAdapter(private var dataSet: List<Contact>, private val onclick: View.OnClickListener, private val onlongclick: View.OnLongClickListener): RecyclerView.Adapter<ContactsAdapter.ViewHolder>() {

    class ViewHolder(view: View): RecyclerView.ViewHolder(view) {
        val contactName: AppCompatTextView
        val lastMessage: AppCompatTextView
        val unreadCount: AppCompatTextView
        val avatar: AvatarView

        init {
            contactName = view.findViewById(R.id.contact_name)
            lastMessage = view.findViewById(R.id.last_message)
            unreadCount = view.findViewById(R.id.unread_count)
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
        holder.contactName.text = contact.name
        holder.lastMessage.text = contact.lastMessage.ifEmpty { contact.pubkey }
        if (contact.unread > 0) {
            holder.unreadCount.text = contact.unread.toString()
            holder.unreadCount.visibility = View.VISIBLE
        } else {
            holder.unreadCount.visibility = View.GONE
        }
        holder.itemView.tag = contact
        val initials = getInitials(contact)
        holder.avatar.avatarInitials = initials
    }

    override fun getItemCount(): Int {
        return dataSet.size
    }

    fun setContacts(contacts: List<Contact>) {
        dataSet = contacts
    }

    private fun getInitials(contact: Contact): String {
        val name = contact.name.trim()
        if (name.isEmpty() || name.length < 2) {
            return contact.pubkey.substring(0, 2)
        }

        if (name.length == 2) {
            return name
        }

        if (name.contains(" ")) {
            val pos = name.indexOf(" ") + 1
            return name.substring(0, 1) + name.substring(pos, pos + 1)
        }

        return name.substring(0, 2)
    }
}