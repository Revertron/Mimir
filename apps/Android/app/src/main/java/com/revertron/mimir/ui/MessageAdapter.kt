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
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.R
import com.revertron.mimir.getAvatarColor
import com.revertron.mimir.storage.SqlStorage
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
    private val onLongClick: View.OnLongClickListener,
    private val onReplyClick: View.OnClickListener,
    private val onPictureClick: View.OnClickListener
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
        val reactionsContainer: androidx.appcompat.widget.LinearLayoutCompat = view.findViewById(R.id.reactions_container)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = if (viewType == 0) {
            R.layout.message_incoming_layout
        } else {
            R.layout.message_outgoing_layout
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)

        // Single tap shows context menu with reactions
        view.setOnClickListener { v ->
            onLongClick.onLongClick(v)  // Trigger long click behavior on regular click
        }
        // Keep long click as fallback
        view.setOnLongClickListener(onLongClick)

        view.findViewById<View>(R.id.reply_panel).setOnClickListener(onReplyClick)
        view.findViewById<View>(R.id.picture).setOnClickListener(onPictureClick)
        if (!groupChat) {
            view.findViewById<View>(R.id.avatar)?.visibility = View.GONE
        }

        return ViewHolder(view, viewType == 0 && groupChat)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = if (groupChat) {
            storage.getGroupMessage(chatId, messageIds[position].first)
        } else {
            storage.getMessage(messageIds[position].first)
        } ?: return

        val prefs = PreferenceManager.getDefaultSharedPreferences(holder.itemView.context)
        val fontSize = prefs.getInt(SettingsData.KEY_MESSAGE_FONT_SIZE, 15)
        holder.message.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.toFloat())
        holder.replyToText.setTextSize(TypedValue.COMPLEX_UNIT_SP, (fontSize - 2).toFloat())

        if (groupChat) {
            if (message.incoming) {
                val user = users.getOrPut(message.contact, {
                    // TODO make default values
                    storage.getMemberInfo(message.contact, chatId, 48, 6)
                })
                if (user != null) {
                    holder.name.text = user.first
                    holder.name.visibility = View.VISIBLE

                    if (user.second != null) {
                        holder.avatar?.clearColorFilter()
                        holder.avatar?.setImageDrawable(user.second)
                    } else {
                        // Use default avatar with color based on pubkey
                        holder.avatar?.setImageResource(R.drawable.button_rounded_white)
                        val pubKey = storage.getMemberPubkey(message.contact, chatId)
                        pubKey?.let {
                            val avatarColor = getAvatarColor(pubKey)
                            holder.avatar?.setColorFilter(avatarColor, PorterDuff.Mode.MULTIPLY)
                        }
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
        holder.message.text = message.getText(holder.itemView.context)
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
                holder.message.text = holder.name.context.getString(R.string.system_message)
                holder.picture.setImageDrawable(null)
                holder.picturePanel.visibility = View.GONE
            }
            else -> {
                holder.picture.setImageDrawable(null)
                holder.picturePanel.visibility = View.GONE
            }
        }
        holder.time.text = formatTime(message.time)
        holder.itemView.tag = message.id
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
        val layoutParams = holder.itemView.layoutParams as RecyclerView.LayoutParams
        if (position == 0) {
            layoutParams.updateMargins(top = layoutParams.bottomMargin)
        } else {
            layoutParams.updateMargins(top = 0)
        }
        if (!groupChat) {
            storage.setMessageRead(chatId, message.id, true)
        } else {
            storage.setGroupMessageRead(chatId, message.id, true)
        }

        // Display reactions for this message
        displayReactions(holder, message.guid)
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
     * Shows the reaction picker popup when a message is tapped.
     */
    // Deprecated: Reaction picker is now shown in ChatActivity alongside context menu
    @Deprecated("Moved to ChatActivity.showQuickReactions()")
    private fun showReactionPicker(messageView: View) {
        // This method is no longer used but kept for reference
        return
        val messageId = messageView.tag as? Long ?: return

        // Get the message to find its GUID
        val message = if (groupChat) {
            storage.getGroupMessage(chatId, messageId)
        } else {
            storage.getMessage(messageId)
        } ?: return

        val chatIdForReactions = if (groupChat) chatId else null

        // Create popup window
        val popupView = LayoutInflater.from(messageView.context)
            .inflate(R.layout.reaction_picker_popup, null)

        // Measure the popup view to get its dimensions
        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val popupWindow = android.widget.PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        popupWindow.elevation = 8f
        popupWindow.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        popupWindow.isOutsideTouchable = true
        popupWindow.isFocusable = true

        // Get current user's pubkey
        val currentUserPubkey = storage.getAccountInfo(1, 0L)?.let {
            (it.keyPair.public as org.bouncycastle.crypto.params.Ed25519PublicKeyParameters).encoded
        }

        // Set up emoji click listeners (compact: 5 most popular)
        val emojis = listOf(
            R.id.emoji_like to "👍",
            R.id.emoji_love to "❤️",
            R.id.emoji_laugh to "😂",
            R.id.emoji_fire to "🔥",
            R.id.emoji_clap to "👏"
        )

        for ((viewId, emoji) in emojis) {
            popupView.findViewById<View>(viewId)?.setOnClickListener {
                popupWindow.dismiss()

                if (currentUserPubkey != null) {
                    // Check if user has an existing reaction (to send removal to peer)
                    val existingReaction = storage.getUserCurrentReaction(message.guid, chatIdForReactions, currentUserPubkey)

                    // Toggle the reaction locally
                    val added = storage.toggleReaction(message.guid, chatIdForReactions, currentUserPubkey, emoji)

                    // Post update to main thread to avoid RecyclerView inconsistency
                    (messageView.context as? android.app.Activity)?.runOnUiThread {
                        try {
                            notifyDataSetChanged()
                        } catch (e: Exception) {
                            android.util.Log.e("MessageAdapter", "Error updating reactions", e)
                        }
                    }

                    // If user had a different reaction, send removal first
                    if (existingReaction != null && existingReaction != emoji) {
                        val removeIntent = android.content.Intent(messageView.context, com.revertron.mimir.ConnectionService::class.java)
                        removeIntent.putExtra("command", "ACTION_SEND_REACTION")
                        removeIntent.putExtra("messageGuid", message.guid)
                        removeIntent.putExtra("emoji", existingReaction)
                        removeIntent.putExtra("add", false)
                        if (chatIdForReactions != null) {
                            removeIntent.putExtra("chatId", chatIdForReactions)
                        }
                        if (!groupChat) {
                            val contactPubkey = storage.getContactPubkey(chatId)
                            if (contactPubkey != null) {
                                removeIntent.putExtra("contactPubkey", contactPubkey)
                            }
                        }
                        messageView.context.startService(removeIntent)
                    }

                    // Send the new reaction to peer via ConnectionService
                    val intent = android.content.Intent(messageView.context, com.revertron.mimir.ConnectionService::class.java)
                    intent.putExtra("command", "ACTION_SEND_REACTION")
                    intent.putExtra("messageGuid", message.guid)
                    intent.putExtra("emoji", emoji)
                    intent.putExtra("add", added)
                    if (chatIdForReactions != null) {
                        intent.putExtra("chatId", chatIdForReactions)
                    }
                    // For personal chats, need contact pubkey
                    if (!groupChat) {
                        val contactPubkey = storage.getContactPubkey(chatId)
                        if (contactPubkey != null) {
                            intent.putExtra("contactPubkey", contactPubkey)
                        }
                    }
                    messageView.context.startService(intent)
                }
            }
        }

        // Calculate position: show popup centered above the message
        val location = IntArray(2)
        messageView.getLocationOnScreen(location)
        val messageX = location[0]
        val messageY = location[1]

        val popupWidth = popupView.measuredWidth
        val xOffset = (messageView.width - popupWidth) / 2
        val yOffset = -messageView.height - popupView.measuredHeight - 8

        // Show popup above the message
        popupWindow.showAsDropDown(messageView, xOffset, yOffset, android.view.Gravity.NO_GRAVITY)
    }

    /**
     * Displays reactions for a message in the reactions container.
     * Shows up to 4 reactions per row, wraps to new rows as needed.
     * @param holder ViewHolder containing the reactions container
     * @param messageGuid GUID of the message
     */
    private fun displayReactions(holder: ViewHolder, messageGuid: Long) {
        // Get reactions from storage
        val chatIdForReactions = if (groupChat) chatId else null
        val reactionCounts = storage.getReactionCounts(messageGuid, chatIdForReactions)

        if (reactionCounts.isEmpty()) {
            holder.reactionsContainer.visibility = View.GONE
            return
        }

        holder.reactionsContainer.visibility = View.VISIBLE
        holder.reactionsContainer.removeAllViews()

        // Get current user's pubkey to highlight their reactions
        val currentUserPubkey = storage.getAccountInfo(1, 0L)?.let {
            (it.keyPair.public as org.bouncycastle.crypto.params.Ed25519PublicKeyParameters).encoded
        }

        // Create reaction badges with max 4 per row
        var currentRow: androidx.appcompat.widget.LinearLayoutCompat? = null
        var itemsInRow = 0
        val maxPerRow = 4

        for ((emoji, reactors) in reactionCounts) {
            // Create new row if needed
            if (currentRow == null || itemsInRow >= maxPerRow) {
                currentRow = androidx.appcompat.widget.LinearLayoutCompat(holder.itemView.context)
                currentRow.orientation = androidx.appcompat.widget.LinearLayoutCompat.HORIZONTAL
                holder.reactionsContainer.addView(currentRow)
                itemsInRow = 0
            }

            // Inflate reaction badge
            val badgeView = LayoutInflater.from(holder.itemView.context)
                .inflate(R.layout.reaction_badge, currentRow, false)

            val emojiView = badgeView.findViewById<AppCompatTextView>(R.id.reaction_emoji)
            val countView = badgeView.findViewById<AppCompatTextView>(R.id.reaction_count)

            emojiView.text = emoji
            // Show count only if more than 1 reactor
            if (reactors.size > 1) {
                countView.text = reactors.size.toString()
                countView.visibility = View.VISIBLE
            } else {
                countView.visibility = View.GONE
            }

            // Highlight if current user has this reaction
            val userReacted = currentUserPubkey != null && reactors.any { it.contentEquals(currentUserPubkey) }
            if (userReacted) {
                badgeView.setBackgroundResource(R.drawable.reaction_badge_background_selected)
            } else {
                badgeView.setBackgroundResource(R.drawable.reaction_badge_background)
            }

            // Click to toggle reaction
            badgeView.setOnClickListener {
                if (currentUserPubkey != null) {
                    val added = storage.toggleReaction(messageGuid, chatIdForReactions, currentUserPubkey, emoji)
                    notifyItemChanged(holder.bindingAdapterPosition)

                    // Send reaction to peer via ConnectionService
                    val intent = android.content.Intent(holder.itemView.context, com.revertron.mimir.ConnectionService::class.java)
                    intent.putExtra("command", "ACTION_SEND_REACTION")
                    intent.putExtra("messageGuid", messageGuid)
                    intent.putExtra("emoji", emoji)
                    intent.putExtra("add", added)
                    if (chatIdForReactions != null) {
                        intent.putExtra("chatId", chatIdForReactions)
                    }
                    // For personal chats, need contact pubkey
                    if (!groupChat) {
                        val contactPubkey = storage.getContactPubkey(chatId)
                        if (contactPubkey != null) {
                            intent.putExtra("contactPubkey", contactPubkey)
                        }
                    }
                    holder.itemView.context.startService(intent)
                }
            }

            currentRow.addView(badgeView)
            itemsInRow++
        }
    }
}