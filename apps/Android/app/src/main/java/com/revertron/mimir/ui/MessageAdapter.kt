package com.revertron.mimir.ui

import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.updateMargins
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.R
import com.revertron.mimir.getAvatarColor
import com.revertron.mimir.storage.SqlStorage
import org.bouncycastle.util.encoders.Hex
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

class MessageAdapter(
    private val storage: SqlStorage,
    private val chatId: Long,
    private val groupChat: Boolean,
    private val contactName: String,
    private val onClick: View.OnClickListener,
    private val onReplyClick: View.OnClickListener,
    private val onPictureClick: View.OnClickListener,
    private val fontSize: Int,
    private val onAvatarClick: View.OnClickListener? = null
): RecyclerView.Adapter<MessageAdapter.ViewHolder>() {

    private val timeFormatter = SimpleDateFormat.getTimeInstance(DateFormat.SHORT)
    private val dateFormatter = SimpleDateFormat.getDateInstance(DateFormat.SHORT)

    private val messageIds = if (groupChat) {
        storage.getGroupMessageIds(chatId).toMutableList()
    } else {
        // For 1-on-1 chats, chatId is actually the userId
        storage.getMessageIds(chatId).toMutableList()
    }

    // Cached nicknames and avatars
    private val users: HashMap<Long, Pair<String, Drawable?>?> = HashMap()

    class ViewHolder(view: View, hasAvatar: Boolean): RecyclerView.ViewHolder(view) {
        val avatar: AppCompatImageView? = if (hasAvatar) view.findViewById(R.id.avatar) else null
        val name: AppCompatTextView = view.findViewById(R.id.name)
        val message: AppCompatTextView = view.findViewById(R.id.text)
        val picture: AppCompatImageView = view.findViewById(R.id.picture)
        val picturePanel: FrameLayout = view.findViewById(R.id.picture_panel)
        val time: AppCompatTextView = view.findViewById(R.id.time)
        val sent: AppCompatImageView = view.findViewById(R.id.status_image)
        val replyToName: AppCompatTextView = view.findViewById(R.id.reply_contact_name)
        val replyToText: AppCompatTextView = view.findViewById(R.id.reply_text)
        val replyToPanel: View = view.findViewById(R.id.reply_panel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = if (viewType == 0) {
            R.layout.message_incoming_layout
        } else {
            R.layout.message_outgoing_layout
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        view.findViewById<View>(R.id.message).setOnClickListener(onClick)
        //TODO make item background reflect touches
        view.findViewById<View>(R.id.reply_panel).setOnClickListener(onReplyClick)
        view.findViewById<View>(R.id.picture).setOnClickListener(onPictureClick)
        if (!groupChat) {
            view.findViewById<View>(R.id.avatar)?.visibility = View.GONE
        } else if (onAvatarClick != null) {
            // Set click listener for avatar in group chats
            view.findViewById<View>(R.id.avatar)?.setOnClickListener(onAvatarClick)
        }

        return ViewHolder(view, viewType == 0 && groupChat)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = if (groupChat) {
            storage.getGroupMessage(chatId, messageIds[position].first)
        } else {
            storage.getMessage(messageIds[position].first)
        } ?: return

        holder.message.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.toFloat())
        holder.replyToText.setTextSize(TypedValue.COMPLEX_UNIT_SP, (fontSize - 2).toFloat())

        if (groupChat) {
            if (message.incoming) {
                holder.avatar?.visibility = View.VISIBLE
                val user = users.getOrPut(message.contact, {
                    // TODO make default values
                    storage.getMemberInfo(message.contact, chatId, 48, 6)
                })
                val pubKey = storage.getMemberPubkey(message.contact, chatId)
                val avatarColor = if (pubKey != null) {
                    getAvatarColor(pubKey)
                } else {
                    0x808080
                }
                if (user != null) {
                    holder.name.text = user.first
                    holder.name.visibility = View.VISIBLE

                    if (user.second != null) {
                        holder.avatar?.clearColorFilter()
                        holder.avatar?.setImageDrawable(user.second)
                    } else {
                        // Use default avatar with color based on pubkey
                        holder.avatar?.setImageResource(R.drawable.button_rounded_white)
                        pubKey?.let {
                            holder.avatar?.setColorFilter(avatarColor, PorterDuff.Mode.MULTIPLY)
                        }
                    }
                    // Apply the same color to the nickname
                    holder.name.setTextColor(avatarColor)

                    // Set the contact ID as tag for onClick handler
                    holder.avatar?.tag = message.contact
                } else if (message.type < 1000) {
                    holder.name.text = holder.name.context.getString(R.string.unknown_nickname)
                    // Use default avatar with color based on pubkey
                    holder.avatar?.setImageResource(R.drawable.button_rounded_white)
                    holder.avatar?.let {
                        holder.avatar?.setColorFilter(avatarColor, PorterDuff.Mode.MULTIPLY)
                    }
                } else {
                    holder.name.visibility = View.GONE
                    holder.avatar?.visibility = View.GONE
                }
            } else {
                holder.name.visibility = View.GONE
                holder.name.text = ""
            }
        } else {
            holder.name.visibility = View.GONE
            holder.avatar?.visibility = View.GONE
        }
        // Use overloaded getText with chatId for group chats to format system messages properly
        holder.message.text = if (groupChat) {
            message.getText(holder.itemView.context, storage, chatId)
        } else {
            message.getText(holder.itemView.context)
        }
        holder.message.setCompoundDrawables(null, null, null ,null)
        holder.sent.visibility = if (message.incoming) View.GONE else View.VISIBLE
        when (message.type) {
            1 -> {
                if (message.data != null) {
                    val string = String(message.data)
                    Log.i("Adapter", "Message: $string")
                    val json = JSONObject(string)
                    val name = json.getString("name")
                    val cachePath = File(holder.itemView.context.cacheDir, "files")
                    val filePath = File(holder.itemView.context.filesDir, "files")
                    val cacheFile = File(cachePath, name)
                    val imageFile = File(filePath, name)
                    if (cacheFile.exists()) {
                        val uri: Uri = Uri.fromFile(cacheFile)
                        holder.picture.setImageURI(uri)
                        holder.picture.tag = Uri.fromFile(imageFile)
                    } else {
                        val file = File(filePath, name)
                        if (file.exists()) {
                            val uri: Uri = Uri.fromFile(file)
                            holder.picture.setImageURI(uri)
                            holder.picture.tag = uri
                        }
                    }
                    holder.picturePanel.visibility = View.VISIBLE
                }
            }
            2 -> {
                val icon = ContextCompat.getDrawable(holder.itemView.context, R.drawable.ic_phone_outline_themed)?.apply {
                    setBounds(0, 0, intrinsicWidth, intrinsicHeight)
                }
                holder.message.setCompoundDrawables(icon, null, null ,null)
                holder.message.compoundDrawablePadding = holder.itemView.context.resources.getDimensionPixelSize(R.dimen.icon_padding)
                holder.sent.visibility = View.GONE
                holder.picture.setImageDrawable(null)
                holder.picturePanel.visibility = View.GONE
            }
            1000 -> {
                holder.picture.setImageDrawable(null)
                holder.picturePanel.visibility = View.GONE
            }
            else -> {
                holder.picture.setImageDrawable(null)
                holder.picturePanel.visibility = View.GONE
            }
        }
        // System messages (type 1000) are now handled by getText() method above
        // which provides comprehensive event descriptions

        holder.time.text = formatTime(message.time)
        // Sorry for this
        holder.itemView.findViewById<View>(R.id.message).tag = message.id
        holder.sent.tag = message.delivered

        if (message.replyTo != 0L) {
            val replyToMessage = if (groupChat) {
                storage.getGroupMessage(chatId, message.replyTo, true)
            } else {
                storage.getMessage(message.replyTo, true)
            }
            if (replyToMessage != null) {
                holder.replyToPanel.visibility = View.VISIBLE
                holder.replyToPanel.tag = replyToMessage.id

                val unknown = holder.message.context.getString(R.string.unknown_nickname)

                // For group chats, show the actual author's name
                val authorName = if (groupChat) {
                    val user = storage.getMemberInfo(replyToMessage.contact, chatId, 48, 6)
                    user?.first ?: unknown
                } else {
                    contactName
                }

                val replyText = replyToMessage.getText(holder.itemView.context)

                holder.replyToName.text = authorName
                holder.replyToText.text = replyText
            } else {
                // Message may be deleted or GUID mismatch
                Log.w("MessageAdapter", "Reply lookup FAILED for GUID ${message.replyTo} - original message not found in database")
                holder.replyToPanel.visibility = View.GONE
                holder.replyToName.text = ""
                holder.replyToText.text = ""
            }
        } else {
            holder.replyToPanel.visibility = View.GONE
            holder.replyToName.text = ""
            holder.replyToText.text = ""
        }

        if (message.delivered) {
            holder.sent.setImageResource(R.drawable.ic_message_delivered)
        } else {
            holder.sent.setImageResource(R.drawable.ic_message_not_sent)
        }
        // Apply top margin to the message view (not itemView) for the first message
        val messageView = holder.itemView.findViewById<View>(R.id.message)
        val messageLayoutParams = messageView.layoutParams as ViewGroup.MarginLayoutParams
        if (position == 0) {
            messageLayoutParams.updateMargins(top = messageLayoutParams.bottomMargin)
        } else {
            messageLayoutParams.updateMargins(top = 0)
        }
        if (!groupChat) {
            storage.setMessageRead(chatId, message.id, true)
        } else {
            storage.setGroupMessageRead(chatId, message.id, true)
        }
    }

    private fun formatTime(time: Long): String {
        val date = Date(time)
        val diff = Date().time - date.time
        return if (diff > 86400 * 1000) {
            "${dateFormatter.format(date)} ${timeFormatter.format(date)}"
        } else {
            timeFormatter.format(date)
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (messageIds[position].second) 0 else 1
    }

    override fun getItemCount(): Int {
        return messageIds.size
    }

    fun addMessageId(messageId: Long, incoming: Boolean) {
        messageIds.add(messageId to incoming)
        notifyItemInserted(messageIds.size - 1)
    }

    fun deleteMessageId(messageId: Long) {
        for ((index, message) in messageIds.withIndex()) {
            if (message.first == messageId) {
                messageIds.removeAt(index)
                notifyItemChanged(index)
                break
            }
        }
    }

    fun setMessageDelivered(id: Long, delivered: Boolean) {
        for ((index, message) in messageIds.withIndex()) {
            if (message.first == id) {
                notifyItemChanged(index)
                break
            }
        }
    }

    fun getMessageIdPosition(id: Long): Int {
        for ((index, message) in messageIds.withIndex()) {
            if (message.first == id) {
                return index
            }
        }
        return -1
    }

    /**
     * Clears the cached member info, forcing it to be reloaded from storage.
     * This should be called when member info might have been updated (e.g., avatar changes).
     */
    fun refreshMemberCache() {
        users.clear()
        notifyDataSetChanged()
    }

    /**
     * Refreshes cached info for a specific member by their ID.
     */
    fun refreshMember(memberId: Long) {
        users.remove(memberId)
        // Find and refresh all messages from this member
        for ((index, message) in messageIds.withIndex()) {
            val msg = if (groupChat) {
                storage.getGroupMessage(chatId, message.first)
            } else {
                storage.getMessage(message.first)
            }
            if (msg?.contact == memberId) {
                notifyItemChanged(index)
            }
        }
    }
}