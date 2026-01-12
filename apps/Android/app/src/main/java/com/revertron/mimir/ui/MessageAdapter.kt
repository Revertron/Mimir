package com.revertron.mimir.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.method.MovementMethod
import android.text.style.ClickableSpan
import android.text.util.Linkify
import android.view.MotionEvent
import android.widget.TextView
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import java.util.regex.Pattern
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.FileProvider
import androidx.core.view.updateMargins
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.R
import com.revertron.mimir.getAvatarColor
import com.revertron.mimir.storage.SqlStorage
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import androidx.core.net.toUri
import kotlin.math.abs

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

    companion object {
        const val TAG = "MessageAdapter"

        // Custom URL scheme patterns for unusual protocols
        private val CUSTOM_SCHEMES = arrayOf("mimir", "tcp", "tls", "ws", "wss", "quic")

        // Regex pattern that matches URLs with custom schemes
        // Pattern: (scheme)://[any non-whitespace characters]
        private val CUSTOM_URL_PATTERN = Pattern.compile(
            "\\b(" + CUSTOM_SCHEMES.joinToString("|") + ")://[^\\s]+",
            Pattern.CASE_INSENSITIVE
        )
    }

    /**
     * Custom MovementMethod that handles link clicks while passing
     * non-link touch events to the parent view for natural onClick handling.
     * Clears the pressed state when the user scrolls to prevent stuck highlights.
     */
    private class LinkAndClickMovementMethod : LinkMovementMethod() {
        private var initialX = 0f
        private var initialY = 0f
        private var touchSlop = 0
        private var isMoving = false

        override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
            val action = event.action

            // Initialize touch slop on first use
            if (touchSlop == 0) {
                touchSlop = 16
            }

            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    // Store initial touch position
                    initialX = event.x
                    initialY = event.y
                    isMoving = false
                    if (widget.parent is View) {
                        val p = widget.parent as View
                        if (p.id == R.id.message) {
                            // Store touch coordinates for later use
                            val currentTag = p.tag
                            if (currentTag is MessageTag) {
                                p.tag = currentTag.copy(
                                    touchX = event.rawX.toInt(),
                                    touchY = event.rawY.toInt()
                                )
                            }
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    // Check if movement exceeds touch slop
                    val deltaX = abs(event.x - initialX)
                    val deltaY = abs(event.y - initialY)

                    if (deltaX >= touchSlop || deltaY >= touchSlop) {
                        // User is scrolling, clear pressed state on both widget and parent
                        if (!isMoving) {
                            isMoving = true

                            // Clear pressed state on TextView
                            widget.cancelLongPress()
                            widget.isPressed = false
                            widget.isSelected = false
                            widget.refreshDrawableState()

                            // Also clear pressed state on parent view
                            val parent = widget.parent
                            if (parent is View) {
                                parent.cancelLongPress()
                                parent.isPressed = false
                                parent.isSelected = false
                                parent.refreshDrawableState()
                                // Force drawable to jump to current state
                                parent.background?.jumpToCurrentState()
                            }

                            // Send ACTION_CANCEL to properly cancel the touch sequence
                            val cancelEvent = MotionEvent.obtain(event)
                            cancelEvent.action = MotionEvent.ACTION_CANCEL
                            widget.onTouchEvent(cancelEvent)
                            if (parent is View) {
                                parent.onTouchEvent(cancelEvent)
                            }
                            cancelEvent.recycle()
                        }
                        // Return false to let RecyclerView handle scrolling
                        return false
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    // Touch was cancelled (e.g., parent intercepted), clear state
                    isMoving = false
                    widget.isPressed = false
                    widget.isSelected = false
                    widget.refreshDrawableState()

                    val parent = widget.parent
                    if (parent is View) {
                        parent.isPressed = false
                        parent.isSelected = false
                        parent.refreshDrawableState()
                        parent.background?.jumpToCurrentState()
                    }
                    return false
                }
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_DOWN) {
                var x = event.x.toInt()
                var y = event.y.toInt()

                x -= widget.totalPaddingLeft
                y -= widget.totalPaddingTop

                x += widget.scrollX
                y += widget.scrollY

                val layout = widget.layout
                val line = layout.getLineForVertical(y)
                val off = layout.getOffsetForHorizontal(line, x.toFloat())

                val links = buffer.getSpans(off, off, ClickableSpan::class.java)

                if (links.isNotEmpty()) {
                    // There's a link at this position, handle it normally
                    if (action == MotionEvent.ACTION_UP) {
                        links[0].onClick(widget)
                    }
                    return true
                } else {
                    // No link - pass the touch event to parent view so it can handle onClick naturally
                    val parent = widget.parent
                    if (parent is View) {
                        // Dispatch the event to parent's touch handling
                        return parent.onTouchEvent(event)
                    }
                }
            }

            return super.onTouchEvent(widget, buffer, event)
        }

        companion object {
            private var sInstance: LinkAndClickMovementMethod? = null

            fun getInstance(): MovementMethod {
                if (sInstance == null) {
                    sInstance = LinkAndClickMovementMethod()
                }
                return sInstance!!
            }
        }
    }

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
        val filePanel: View = view.findViewById(R.id.file_panel)
        val fileIcon: AppCompatImageView = view.findViewById(R.id.file_icon)
        val fileName: AppCompatTextView = view.findViewById(R.id.file_name)
        val fileSize: AppCompatTextView = view.findViewById(R.id.file_size)
        val time: AppCompatTextView = view.findViewById(R.id.time)
        val sent: AppCompatImageView = view.findViewById(R.id.status_image)
        val replyToName: AppCompatTextView = view.findViewById(R.id.reply_contact_name)
        val replyToText: AppCompatTextView = view.findViewById(R.id.reply_text)
        val replyToPanel: View = view.findViewById(R.id.reply_panel)
    }

    /**
     * Applies custom link patterns to the TextView for unusual URL schemes.
     * Handles both standard web URLs and custom schemes like mimir://, tcp://, tls://, ws://, wss://
     * Uses custom MovementMethod to allow clicking on non-link text to trigger the popup menu.
     */
    private fun applyCustomLinks(textView: AppCompatTextView, text: CharSequence, context: Context) {
        val spannable = SpannableString(text)

        // First apply standard web URL linkification
        Linkify.addLinks(spannable, Linkify.WEB_URLS)

        // Then add custom scheme patterns
        val matcher = CUSTOM_URL_PATTERN.matcher(text)

        while (matcher.find()) {
            val url = matcher.group()
            val clickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    handleCustomUrl(context, url)
                }
            }
            spannable.setSpan(
                clickableSpan,
                matcher.start(),
                matcher.end(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        textView.text = spannable

        // Use custom MovementMethod that handles both link clicks and delegates
        // non-link clicks to the parent view (R.id.message) for the popup menu
        textView.movementMethod = LinkAndClickMovementMethod.getInstance()
    }

    /**
     * Handles clicks on custom URL schemes.
     * Opens the URL using an ACTION_VIEW intent.
     */
    private fun handleCustomUrl(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Try to start the activity
            try {
                context.startActivity(intent)
            } catch (_: android.content.ActivityNotFoundException) {
                // No app can handle this scheme, show a toast
                Toast.makeText(context, context.getString(R.string.no_app_found_to_open, url), Toast.LENGTH_SHORT).show()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Url", url)
                clipboard.setPrimaryClip(clip)
                Log.w(TAG, "No app to handle URL: $url")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening custom URL: $url", e)
            Toast.makeText(context, "Cannot open URL: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = if (viewType == 0) {
            R.layout.message_incoming_layout
        } else {
            R.layout.message_outgoing_layout
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)

        // Use touch listener to capture coordinates for popup menu
        val onTouchListener: (View, MotionEvent) -> Boolean = { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val view = if (v.id == R.id.message) {
                        v
                    } else {
                        v.findViewById(R.id.message)
                    }
                    // Store touch coordinates for later use
                    val currentTag = view.tag
                    if (currentTag is MessageTag) {
                        v.tag = currentTag.copy(
                            touchX = event.rawX.toInt(),
                            touchY = event.rawY.toInt()
                        )
                    }
                    false
                }

                else -> false
            }
        }
        view.setOnClickListener(onClick)
        view.setOnTouchListener(onTouchListener)
        view.findViewById<View>(R.id.message).setOnTouchListener(onTouchListener)

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

        // Get message text first
        val messageText = if (groupChat) {
            message.getText(holder.itemView.context, storage, chatId)
        } else {
            message.getText(holder.itemView.context)
        }

        // Apply link detection based on message type
        if (message.type != 3) {
            // For non-file messages, apply custom link detection (web URLs + custom schemes)
            applyCustomLinks(holder.message, messageText, holder.itemView.context)
        } else {
            // For file attachments, disable autoLink to prevent filename from being interpreted as a URL
            holder.message.autoLinkMask = 0
            holder.message.text = messageText
        }

        holder.message.visibility = View.VISIBLE
        holder.message.setCompoundDrawables(null, null, null ,null)
        holder.sent.visibility = if (message.incoming) View.GONE else View.VISIBLE

        when (message.type) {
            1 -> {
                // Ordinary text messages, may contain a picture
                holder.filePanel.visibility = View.GONE
                if (message.data != null) {
                    val string = String(message.data)
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
                // Audio call info
                holder.filePanel.visibility = View.VISIBLE
                holder.fileIcon.setImageResource(R.drawable.ic_phone_outline_themed)
                holder.fileName.text = if (message.incoming) {
                    holder.fileName.context.getString(R.string.audio_call_incoming)
                } else {
                    holder.fileName.context.getString(R.string.audio_call_outgoing)
                }
                holder.fileSize.text = holder.message.text
                holder.message.visibility = View.GONE
                holder.sent.visibility = View.GONE
                holder.picture.setImageDrawable(null)
                holder.picturePanel.visibility = View.GONE
            }
            3 -> {
                // File attachment - show file panel with icon and info
                if (message.data != null) {
                    val string = String(message.data)
                    try {
                        val json = JSONObject(string)
                        val name = json.getString("name")
                        val original = json.optString("originalName", name)
                        val size = json.optLong("size", 0L)
                        val filePath = File(holder.itemView.context.filesDir, "files")
                        val file = File(filePath, name)

                        // Get file icon based on MIME type
                        val mimeType = json.optString("mimeType", "application/octet-stream")
                        holder.fileIcon.setImageResource(getFileIconForMimeType(mimeType))

                        // Set file name and size
                        holder.fileName.text = original
                        holder.fileSize.text = formatFileSize(size)

                        // Show file panel, hide text
                        holder.filePanel.visibility = View.VISIBLE
                        if (holder.message.text.isEmpty()) {
                            holder.message.visibility = View.GONE
                        }

                        // Set click listener to open file
                        if (file.exists()) {
                            holder.filePanel.setOnClickListener {
                                openFile(holder.itemView.context, file, mimeType)
                            }
                        }
                    } catch (e: JSONException) {
                        val text = "Unable to format message of type ${message.type}:\n" + String(message.data)
                        holder.message.text = text
                        holder.picture.setImageDrawable(null)
                        holder.picturePanel.visibility = View.GONE
                    }
                }
                holder.picture.setImageDrawable(null)
                holder.picturePanel.visibility = View.GONE
            }
            1000 -> {
                holder.filePanel.visibility = View.GONE
                holder.picture.setImageDrawable(null)
                holder.picturePanel.visibility = View.GONE
            }
            else -> {
                holder.filePanel.visibility = View.GONE
                holder.picture.setImageDrawable(null)
                holder.picturePanel.visibility = View.GONE
            }
        }
        // System messages (type 1000) are now handled by getText() method above
        // which provides comprehensive event descriptions

        holder.time.text = formatTime(message.time)
        // Store message metadata using MessageTag
        holder.itemView.findViewById<View>(R.id.message).tag = MessageTag(message.id, message.guid)
        holder.sent.tag = message.delivered

        // Disable click listener for system messages (type 1000)
        if (message.type == 1000) {
            holder.itemView.findViewById<View>(R.id.message).setOnClickListener(null)
            holder.itemView.findViewById<View>(R.id.message).isClickable = false
        } else {
            holder.itemView.findViewById<View>(R.id.message).setOnClickListener(onClick)
            holder.itemView.findViewById<View>(R.id.message).isClickable = true
        }

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

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
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
                notifyItemRemoved(index)
                break
            }
        }
    }

    fun clearAllMessages() {
        val oldSize = messageIds.size
        messageIds.clear()
        notifyItemRangeRemoved(0, oldSize)
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

    private fun getFileIconForMimeType(mimeType: String): Int {
        return when {
            mimeType.startsWith("application/pdf") -> R.drawable.ic_file_document_outline
            mimeType.startsWith("application/zip") || mimeType.startsWith("application/x-") -> R.drawable.ic_file_document_outline
            mimeType.startsWith("text/") -> R.drawable.ic_file_document_outline
            mimeType.startsWith("audio/") -> R.drawable.ic_file_document_outline
            mimeType.startsWith("video/") -> R.drawable.ic_file_document_outline
            else -> R.drawable.ic_file_document_outline
        }
    }

    private fun openFile(context: Context, file: File, mimeType: String) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.file_provider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, mimeType)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Always show chooser to let user pick the app
            val chooserIntent = Intent.createChooser(intent, context.getString(R.string.open_with))
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            try {
                context.startActivity(chooserIntent)
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("MessageAdapter", "Error opening file", e)
            Toast.makeText(context, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}