package com.revertron.mimir.ui

import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.R
import com.revertron.mimir.formatLastSeen
import com.revertron.mimir.getAvatarColor
import com.revertron.mimir.storage.GroupMemberInfo
import com.revertron.mimir.storage.SqlStorage
import org.bouncycastle.util.encoders.Hex

class GroupMemberAdapter(
    private val chatId: Long,
    private val ownerPubkey: ByteArray,
    private val currentUserPubkey: ByteArray,
    private val storage: SqlStorage,
    private var members: List<GroupMemberInfo>,
    private val onClick: View.OnClickListener?,
    private val onLongClick: View.OnLongClickListener?
) : RecyclerView.Adapter<GroupMemberAdapter.ViewHolder>() {

    companion object {
        // Permission flags (must match mediator.go)
        private const val PERM_OWNER = 0x80
        private const val PERM_ADMIN = 0x40
        private const val PERM_MOD = 0x20
        private const val PERM_USER = 0x10
        private const val PERM_READ_ONLY = 0x08
        private const val PERM_BANNED = 0x01
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: AppCompatImageView = view.findViewById(R.id.avatar)
        val memberName: AppCompatTextView = view.findViewById(R.id.member_name)
        val memberStatus: AppCompatTextView = view.findViewById(R.id.member_status)
        val memberRole: AppCompatTextView = view.findViewById(R.id.member_role)
        val memberPubkey: AppCompatTextView = view.findViewById(R.id.member_pubkey)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_group_member, parent, false)
        onClick?.let {
            view.setOnClickListener(it)
        }
        onLongClick?.let {
            view.setOnLongClickListener(it)
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val member = members[position]
        val context = holder.itemView.context

        // Set member name
        val pubKey = Hex.toHexString(member.pubkey).take(8)
        val displayName = member.nickname?.ifEmpty { null } ?: pubKey
        holder.memberName.text = displayName

        // Show online status or last seen timestamp
        if (member.online) {
            holder.memberStatus.text = context.getString(R.string.online)
            holder.memberStatus.setTextColor(context.getColor(R.color.status_online)) // Green color for online
        } else {
            val lastSeenText = formatLastSeen(context, member.lastSeen, false)
            holder.memberStatus.text = lastSeenText.ifEmpty {
                context.getString(R.string.last_seen_never)
            }
            holder.memberStatus.setTextColor(context.getColor(android.R.color.darker_gray))
        }

        // Set member role badge based on permission flags
        when {
            (member.permissions and PERM_OWNER) != 0 -> {
                holder.memberRole.visibility = View.VISIBLE
                holder.memberRole.text = context.getString(R.string.owner)
            }
            (member.permissions and PERM_ADMIN) != 0 -> {
                holder.memberRole.visibility = View.VISIBLE
                holder.memberRole.text = context.getString(R.string.admin)
            }
            (member.permissions and PERM_MOD) != 0 -> {
                holder.memberRole.visibility = View.VISIBLE
                holder.memberRole.text = context.getString(R.string.moderator)
            }
            (member.permissions and PERM_READ_ONLY) != 0 -> {
                holder.memberRole.visibility = View.VISIBLE
                holder.memberRole.text = context.getString(R.string.read_only)
            }
            else -> {
                // Regular user (PERM_USER) - no badge needed
                holder.memberRole.visibility = View.GONE
            }
        }

        holder.memberPubkey.text = pubKey

        // Load avatar
        val avatar = storage.getGroupMemberAvatar(chatId, member.pubkey, 48, 6)
        if (avatar != null) {
            holder.avatar.clearColorFilter()
            holder.avatar.setImageDrawable(avatar)
        } else {
            // Use default avatar with color based on pubkey
            holder.avatar.setImageResource(R.drawable.button_rounded_white)
            val avatarColor = getAvatarColor(member.pubkey)
            holder.avatar.setColorFilter(avatarColor, PorterDuff.Mode.MULTIPLY)
        }

        holder.itemView.tag = member
    }

    override fun getItemCount(): Int = members.size

    fun updateMembers(newMembers: List<GroupMemberInfo>) {
        members = newMembers
        notifyDataSetChanged()
    }
}
