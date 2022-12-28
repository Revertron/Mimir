package com.revertron.mimir.ui

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.R
import com.revertron.mimir.isColorDark
import io.getstream.avatarview.AvatarView
import org.bouncycastle.util.encoders.Hex
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class ContactsAdapter(private var dataSet: List<Contact>, private val onclick: View.OnClickListener, private val onlongclick: View.OnLongClickListener): RecyclerView.Adapter<ContactsAdapter.ViewHolder>() {

    private val timeFormatter = SimpleDateFormat.getTimeInstance()
    private val dateFormatter = SimpleDateFormat.getDateInstance()

    class ViewHolder(view: View): RecyclerView.ViewHolder(view) {
        val contactName: AppCompatTextView
        val lastMessage: AppCompatTextView
        val lastMessageTime: AppCompatTextView
        val unreadCount: AppCompatTextView
        val avatar: AvatarView

        init {
            contactName = view.findViewById(R.id.contact_name)
            lastMessage = view.findViewById(R.id.last_message)
            lastMessageTime = view.findViewById(R.id.last_message_time)
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
        holder.contactName.text = contact.name.ifEmpty { holder.itemView.context.getString(R.string.unknown_nickname) }
        holder.lastMessage.text = contact.lastMessage.ifEmpty { contact.pubkey }
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
        } else {
            holder.unreadCount.visibility = View.GONE
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

    private fun getAvatarColor(pubkey: String): Int {
        val bytes = Hex.decode(pubkey)
        val hashCode = bytes.toList().hashCode()
        return darkColors[abs(hashCode) % darkColors.size].toInt()
    }

    private val darkColors = arrayOf(
        0xFF2F4F4F, // Dark slate gray
        0xFF4682B4, // Steel blue
        0xFF556B2F, // Dark olive green
        0xFFBDB76B, // Dark khaki
        0xFF8FBC8F, // Dark sea green
        0xFF66CDAA, // Medium aquamarine
        0xFF0000CD, // Medium blue
        0xFF9370DB, // Medium purple
        0xFF3CB371, // Medium sea green
        0xFF7B68EE, // Medium slate blue
        0xFF00FA9A, // Medium spring green
        0xFF48D1CC, // Medium turquoise
        0xFF6B8E23, // Olive drab
        0xFF98FB98, // Pale green
        0xFFAFEEEE, // Pale turquoise
        0xFFB8860B, // Dark goldenrod
        0xFF006400, // Dark green
        0xFFA9A9A9, // Dark grey
        0xFFFF8C00, // Dark orange
        0xFF9932CC, // Dark orchid
        0xFFE9967A, // Dark salmon
        0xFF00CED1, // Dark turquoise
        0xFF9400D3, // Dark violet
        0xFF00BFFF, // Deep sky blue
        0xFF696969, // Dim gray
        0xFF228B22, // Forest green
        0xFFFFD700, // Gold
        0xFFADFF2F, // Green yellow
        0xFFADD8E6, // Light blue
        0xFF90EE90  // Light green
    )
}