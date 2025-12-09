package com.revertron.mimir.ui

import android.annotation.SuppressLint
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.R
import com.revertron.mimir.getAvatarColor
import com.revertron.mimir.loadRoundedAvatar
import com.revertron.mimir.storage.GroupInvite
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

class InvitesAdapter(
    private var dataSet: List<GroupInvite>,
    private val onClick: View.OnClickListener
) : RecyclerView.Adapter<InvitesAdapter.ViewHolder>() {

    private val dateFormatter = SimpleDateFormat.getDateInstance(DateFormat.SHORT)
    private val timeFormatter = SimpleDateFormat.getTimeInstance(DateFormat.SHORT)

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val chatName: AppCompatTextView = view.findViewById(R.id.chat_name)
        val chatDescription: AppCompatTextView = view.findViewById(R.id.chat_description)
        val inviteTime: AppCompatTextView = view.findViewById(R.id.invite_time)
        val avatar: AppCompatImageView = view.findViewById(R.id.avatar)
        val fromName: AppCompatTextView = view.findViewById(R.id.from_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.invite_item, parent, false)
        view.setOnClickListener(onClick)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val invite = dataSet[position]

        // Set chat name
        holder.chatName.text = invite.chatName

        // Set chat description
        if (!invite.chatDescription.isNullOrEmpty()) {
            holder.chatDescription.text = invite.chatDescription
            holder.chatDescription.visibility = View.VISIBLE
        } else {
            holder.chatDescription.visibility = View.GONE
        }

        // Set timestamp
        val date = Date(invite.timestamp)
        val diff = Date().time - date.time
        if (diff > 86400 * 1000) {
            holder.inviteTime.text = dateFormatter.format(date)
        } else {
            holder.inviteTime.text = timeFormatter.format(date)
        }

        // Set from name
        holder.fromName.text = holder.itemView.context.getString(R.string.invited_by, invite.senderName)

        // Set avatar
        if (invite.chatAvatarPath != null && invite.chatAvatarPath.isNotEmpty()) {
            val bitmap = loadRoundedAvatar(holder.itemView.context, invite.chatAvatarPath!!, 64, 8)
            if (bitmap != null) {
                holder.avatar.clearColorFilter()
                holder.avatar.setImageDrawable(bitmap)
            } else {
                setDefaultAvatar(holder, invite)
            }
        } else {
            setDefaultAvatar(holder, invite)
        }

        holder.itemView.tag = invite
    }

    private fun setDefaultAvatar(holder: ViewHolder, invite: GroupInvite) {
        holder.avatar.setImageResource(R.drawable.button_rounded_white)
        // Use chat ID for color generation
        val colorSeed = invite.chatId.toLong().toInt()
        val avatarColor = getAvatarColor(byteArrayOf(
            (colorSeed shr 24).toByte(),
            (colorSeed shr 16).toByte(),
            (colorSeed shr 8).toByte(),
            colorSeed.toByte()
        ))
        holder.avatar.setColorFilter(avatarColor, PorterDuff.Mode.MULTIPLY)
    }

    override fun getItemCount(): Int {
        return dataSet.size
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setInvites(invites: List<GroupInvite>) {
        dataSet = invites
        notifyDataSetChanged()
    }
}