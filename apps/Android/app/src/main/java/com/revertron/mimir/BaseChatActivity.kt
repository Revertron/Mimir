package com.revertron.mimir

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.revertron.mimir.storage.SqlStorage
import com.revertron.mimir.storage.StorageListener
import com.revertron.mimir.ui.MessageAdapter
import com.revertron.mimir.ui.MessageTag
import com.revertron.mimir.ui.SettingsData.KEY_IMAGES_FORMAT
import com.revertron.mimir.ui.SettingsData.KEY_IMAGES_QUALITY
import com.revertron.mimir.ui.SettingsData.KEY_MESSAGE_FONT_SIZE
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.graphics.drawable.toDrawable

/**
 * Base activity for chat screens (1-on-1 and group chats).
 *
 * Provides shared functionality:
 * - Message input and reply UI
 * - Attachment handling (images, photos, files)
 * - Message list display with RecyclerView
 * - Context menu actions (copy, reply, forward, delete)
 * - Camera and gallery integration
 * - Toolbar and avatar display
 *
 * Subclasses must implement:
 * - Message sending logic (P2P vs mediator)
 * - Broadcast receiver setup
 * - Toolbar click handling
 * - Menu creation
 */
abstract class BaseChatActivity : BaseActivity(), Toolbar.OnMenuItemClickListener, StorageListener, View.OnClickListener {

    companion object {
        const val TAG = "BaseChatActivity"
        const val PICK_IMAGE_REQUEST_CODE = 123
        const val TAKE_PHOTO_REQUEST_CODE = 124
        const val PICK_FILE_REQUEST_CODE = 125
        const val REQUEST_FORWARD_MESSAGE = 126
        private const val PREF_EMOJI_USAGE = "emoji_usage_counts"

        val REACTION_EMOJIS = listOf(
            "\uD83D\uDC4D",  // 👍
            "❤\uFE0F",       // ❤️
            "\uD83D\uDD25",  // 🔥
            "\uD83D\uDE02",  // 😂
            "\uD83D\uDE2E",  // 😮
            "\uD83D\uDE22",  // 😢
            "\uD83D\uDE21",  // 😡
            "\uD83D\uDCA9",  // 💩
            "\uD83D\uDE31",  // 😱
            "\uD83E\uDD37\u200D\u2642\uFE0F",  // 🤷‍♂️
            "\uD83E\uDD37\u200D\u2640\uFE0F",  // 🤷‍♀️
            "\uD83E\uDD17",  // 🤗
            "\uD83D\uDE18",  // 😘
            "\uD83D\uDE0D",  // 😍
            "\uD83D\uDCAA",  // 💪🏻
            "\uD83E\uDD74",  // 🥴
            "\uD83E\uDD14",  // 🤔
            "\uD83E\uDD2E",  // 🤮
            "\uD83D\uDC4C",  // 👌🏻
            "\uD83C\uDF46",  // 🍆
            "\uD83C\uDF4C",  // 🍌
            "\uD83C\uDF51",  // 🍑
            "\uD83D\uDC8A",  // 💊
            "❤\uFE0F\u200D\uD83D\uDD25",  // ❤️‍🔥
            "\uD83D\uDCAF",  // 💯
            "\u2705"         // ✅
        )
    }

    // UI Components - Reply Panel
    protected lateinit var replyPanel: LinearLayoutCompat
    protected lateinit var replyName: AppCompatTextView
    protected lateinit var replyText: AppCompatTextView
    protected var replyTo = 0L

    // UI Components - Attachment
    protected lateinit var attachmentPanel: ConstraintLayout
    protected lateinit var attachmentPreview: AppCompatImageView
    protected lateinit var attachmentName: AppCompatTextView
    protected lateinit var attachmentSize: AppCompatTextView
    protected lateinit var attachmentMenu: LinearLayoutCompat
    protected var attachmentJson: JSONObject? = null
    protected var attachmentType: Int = 1 // 1 = image, 3 = file (DB type)
    protected var currentPhotoUri: Uri? = null

    // UI Components - Message List
    protected lateinit var adapter: MessageAdapter
    protected lateinit var recyclerView: RecyclerView
    protected var scrollToBottomFab: FloatingActionButton? = null
    protected var scrollToReactionFab: FloatingActionButton? = null
    private var isFabShowing = false
    private var isReactionFabShowing = false

    // State
    protected var isChatVisible: Boolean = false
    protected var lastSoundTime = 0L

    // Emoji usage counts (loaded from prefs in onCreate)
    private val emojiUsageCounts = mutableMapOf<String, Int>()

    // Permission launchers
    protected val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                takePhoto()
            } else {
                Toast.makeText(this, "Camera permission is required to take photos", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(getLayoutResId())

        setupToolbar()
        setupReplyPanel()
        setupAttachmentUI()
        setupMessageInput()

        getStorage().listeners.add(this)
        loadEmojiUsageCounts()
    }

    // Emoji Usage Tracking

    private fun loadEmojiUsageCounts() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val json = prefs.getString(PREF_EMOJI_USAGE, null)
        emojiUsageCounts.clear()
        if (json != null) {
            try {
                val jsonObject = JSONObject(json)
                for (emoji in REACTION_EMOJIS) {
                    if (jsonObject.has(emoji)) {
                        emojiUsageCounts[emoji] = jsonObject.getInt(emoji)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse emoji usage counts: ${e.message}")
            }
        }
    }

    private fun saveEmojiUsageCounts() {
        val jsonObject = JSONObject()
        for ((emoji, count) in emojiUsageCounts) {
            if (count > 0) {
                jsonObject.put(emoji, count)
            }
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit().putString(PREF_EMOJI_USAGE, jsonObject.toString()).apply()
    }

    private fun incrementEmojiUsage(emoji: String) {
        val currentCount = emojiUsageCounts[emoji] ?: 0
        if (currentCount < Int.MAX_VALUE) {
            emojiUsageCounts[emoji] = currentCount + 1
            saveEmojiUsageCounts()
        }
    }

    private fun getSortedReactionEmojis(): List<String> {
        return REACTION_EMOJIS.sortedByDescending { emojiUsageCounts[it] ?: 0 }
    }

    // Abstract methods to be implemented by subclasses

    /**
     * Returns the layout resource ID for this chat activity.
     */
    protected abstract fun getLayoutResId(): Int

    /**
     * Returns the chat identifier (contact ID or chat ID).
     */
    protected abstract fun getChatId(): Long

    /**
     * Returns the chat display name.
     */
    protected abstract fun getChatName(): String

    /**
     * Returns whether this is a group chat.
     */
    protected abstract fun isGroupChat(): Boolean

    /**
     * Sends a message with the given text and reply reference.
     */
    protected abstract fun sendMessage(text: String, replyTo: Long)

    /**
     * Sets up broadcast receivers specific to this chat type.
     */
    protected abstract fun setupBroadcastReceivers()

    /**
     * Handles toolbar click events.
     */
    protected abstract fun onToolbarClick()

    /**
     * Returns the avatar color seed (pubkey or chat ID bytes).
     */
    protected abstract fun getAvatarColorSeed(): ByteArray

    /**
     * Creates and configures the message adapter.
     */
    protected abstract fun createMessageAdapter(fontSize: Int): MessageAdapter

    /**
     * Returns the first unread message ID if any.
     */
    protected abstract fun getFirstUnreadMessageId(): Long?

    /**
     * Deletes a message by ID.
     */
    protected abstract fun deleteMessageByIdOrGuid(messageId: Long, guid: Long)

    /**
     * Gets a message for reply purposes.
     */
    protected abstract fun getMessageForReply(messageId: Long): Pair<String, String>? // Returns (authorName, messageText)

    /**
     * Gets a  correct message from storage.
     */
    protected abstract fun getMessageFromStorage(messageId: Long): SqlStorage.Message?

    /**
     * Handles adding/removing a reaction to a message.
     *
     * @param targetGuid The GUID of the message to react to
     * @param emoji The emoji identifier to add (e.g., "thumbsup")
     * @param currentEmoji The user's current reaction emoji, if any
     */
    protected abstract fun handleReaction(targetGuid: Long, emoji: String, currentEmoji: String?)

    /**
     * Gets the user's current reaction for a message.
     *
     * @param targetGuid The GUID of the message to check
     * @return The emoji identifier if user has reacted, null otherwise
     */
    protected abstract fun getUserCurrentReaction(targetGuid: Long): String?

    /**
     * Gets the count of unseen reactions for this chat.
     */
    protected abstract fun getUnseenReactionsCount(): Int

    /**
     * Gets the first unseen reaction and scrolls to the target message.
     * @return true if there was a reaction to scroll to, false otherwise
     */
    protected abstract fun scrollToFirstUnseenReaction(): Boolean

    // Shared UI Setup

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setOnMenuItemClickListener(this)
        toolbar.setOnClickListener { onToolbarClick() }

        supportActionBar?.title = ""
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    protected fun setupAvatar(avatarDrawable: android.graphics.drawable.Drawable?) {
        val avatar = findViewById<AppCompatImageView>(R.id.avatar)
        if (avatarDrawable != null) {
            avatar.clearColorFilter()
            avatar.setImageDrawable(avatarDrawable)
        } else {
            avatar.setImageResource(R.drawable.button_rounded_white)
            val avatarColor = getAvatarColor(getAvatarColorSeed())
            avatar.setColorFilter(avatarColor, PorterDuff.Mode.MULTIPLY)
        }
    }

    protected fun setToolbarTitle(title: String) {
        findViewById<AppCompatTextView>(R.id.title).text = title
    }

    protected fun setToolbarSubtitle(subtitle: String) {
        findViewById<AppCompatTextView>(R.id.subtitle)?.text = subtitle
    }

    private fun setupReplyPanel() {
        replyPanel = findViewById(R.id.reply_panel)
        replyPanel.visibility = View.GONE
        replyName = findViewById(R.id.reply_contact_name)
        replyText = findViewById(R.id.reply_text)
        findViewById<AppCompatImageView>(R.id.reply_close).setOnClickListener {
            replyPanel.visibility = View.GONE
            replyTo = 0L
        }
    }

    private fun setupAttachmentUI() {
        // Attachment menu
        attachmentMenu = findViewById(R.id.attachment_menu)
        attachmentMenu.visibility = View.GONE

        findViewById<AppCompatImageButton>(R.id.attach_button).setOnClickListener {
            toggleAttachmentMenu()
        }

        findViewById<LinearLayoutCompat>(R.id.menu_item_image).setOnClickListener {
            hideAttachmentMenu()
            selectAndSendPicture()
        }

        findViewById<LinearLayoutCompat>(R.id.menu_item_photo).setOnClickListener {
            hideAttachmentMenu()
            checkAndRequestCameraPermission()
        }

        findViewById<LinearLayoutCompat>(R.id.menu_item_file).setOnClickListener {
            hideAttachmentMenu()
            selectAndSendFile()
        }

        // Attachment panel
        attachmentPanel = findViewById(R.id.attachment)
        attachmentPanel.visibility = View.GONE
        attachmentPreview = findViewById(R.id.attachment_image)
        attachmentName = findViewById(R.id.attachment_name)
        attachmentSize = findViewById(R.id.attachment_size)
        val attachmentCancel = findViewById<AppCompatImageView>(R.id.attachment_cancel)
        attachmentCancel.setOnClickListener {
            attachmentPreview.setImageDrawable(null)
            attachmentPanel.visibility = View.GONE
            attachmentJson?.getString("name")?.apply {
                deleteFileAndPreview(this@BaseChatActivity, this)
            }
            attachmentJson = null
            attachmentType = 1
        }
    }

    private fun setupMessageInput() {
        val editText = findViewById<AppCompatEditText>(R.id.message_edit)
        val sendButton = findViewById<AppCompatImageButton>(R.id.send_button)
        sendButton.setOnClickListener {
            val text: String = editText.text.toString().trim()
            val isForwardMode = intent.getBooleanExtra("FORWARD_MODE", false)

            if (isForwardMode && replyTo == 0L) {
                // Forward mode: send user text first (if present), then forwarded message
                editText.text?.clear()

                // Send user's text first if present
                if (text.isNotEmpty()) {
                    sendMessage(text, 0L)
                }

                // Then send the forwarded message
                if (attachmentJson != null) {
                    // Media forward - send with attachment and original caption
                    val originalCaption = attachmentJson?.optString("text", "") ?: ""
                    sendMessage(originalCaption, 0L)
                } else {
                    // Text forward - get text from reply panel and send
                    val forwardedText = replyText.text.toString()
                    if (forwardedText.isNotEmpty()) {
                        // Temporarily clear attachment to ensure text-only send
                        val savedAttachment = attachmentJson
                        val savedType = attachmentType
                        attachmentJson = null
                        sendMessage(forwardedText, 0L)
                        attachmentJson = savedAttachment
                        attachmentType = savedType
                    }
                }

                clearReplyPanel()
                clearAttachment()

                // Clear forward mode
                intent.removeExtra("FORWARD_MODE")
                intent.removeExtra("FORWARD_MESSAGE_ID")
                intent.removeExtra("FORWARD_MESSAGE_GUID")
                intent.removeExtra("FORWARD_MESSAGE_TYPE")
                intent.removeExtra("FORWARD_MESSAGE_TEXT")
                intent.removeExtra("FORWARD_MESSAGE_JSON")

                // Delete the draft since message was sent
                val chatType = if (isGroupChat()) SqlStorage.CHAT_TYPE_GROUP else SqlStorage.CHAT_TYPE_CONTACT
                getStorage().deleteDraft(chatType, getChatId())
            } else if (text.isNotEmpty() || attachmentJson != null) {
                // Normal send flow
                editText.text?.clear()
                sendMessage(text, replyTo)
                clearReplyPanel()
                clearAttachment()

                // Delete the draft since message was sent
                val chatType = if (isGroupChat()) SqlStorage.CHAT_TYPE_GROUP else SqlStorage.CHAT_TYPE_CONTACT
                getStorage().deleteDraft(chatType, getChatId())
            }
        }
    }

    protected fun setupMessageList() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val fontSize = prefs.getInt(KEY_MESSAGE_FONT_SIZE, 15)

        adapter = createMessageAdapter(fontSize)
        recyclerView = findViewById(R.id.messages_list)
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }

        // Setup scroll-to-bottom FAB
        setupScrollToBottomFab()

        // Setup scroll-to-reaction FAB
        setupScrollToReactionFab()

        // Scroll to first unread message if any, otherwise scroll to end
        val firstUnreadId = getFirstUnreadMessageId()
        if (firstUnreadId != null) {
            val position = adapter.getMessageIdPosition(firstUnreadId)
            if (position >= 0) {
                recyclerView.post {
                    recyclerView.scrollToPosition(position)
                    // Check FAB visibility after initial scroll
                    recyclerView.post { updateFabVisibility() }
                }
            }
        }
    }

    private fun updateFabVisibility() {
        if (adapter.itemCount == 0) {
            hideFab()
            return
        }

        // Use hysteresis to prevent flickering: show at higher threshold, hide at lower
        val showThresholdPx = (1600 * resources.displayMetrics.density).toInt()
        val hideThresholdPx = (1400 * resources.displayMetrics.density).toInt()

        // Calculate actual pixel distance from bottom using RecyclerView's scroll computations
        val scrollRange = recyclerView.computeVerticalScrollRange()
        val scrollOffset = recyclerView.computeVerticalScrollOffset()
        val scrollExtent = recyclerView.computeVerticalScrollExtent()
        val distanceFromEnd = scrollRange - scrollOffset - scrollExtent

        if (!isFabShowing && distanceFromEnd > showThresholdPx) {
            showFab()
        } else if (isFabShowing && distanceFromEnd < hideThresholdPx) {
            hideFab()
        }
    }

    private fun setupScrollToBottomFab() {
        scrollToBottomFab = findViewById(R.id.scroll_to_bottom_fab)
        scrollToBottomFab?.let { fab ->
            // Click listener to scroll to end
            fab.setOnClickListener {
                scrollToEndSmart()
                markAllMessagesAsRead()
                hideFab()
            }

            // Scroll listener to show/hide FAB based on distance from end
            recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    updateFabVisibility()
                }
            })
        }
    }

    private fun scrollToEndSmart() {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val lastVisiblePosition = layoutManager.findLastVisibleItemPosition()
        val itemCount = adapter.itemCount
        val lastPosition = itemCount - 1

        if (lastPosition < 0) return

        val itemsFromEnd = lastPosition - lastVisiblePosition
        // If more than 30 items away, scroll instantly; otherwise animate
        if (itemsFromEnd > 30) {
            recyclerView.scrollToPosition(lastPosition)
        } else {
            recyclerView.smoothScrollToPosition(lastPosition)
        }
    }

    private fun markAllMessagesAsRead() {
        if (isGroupChat()) {
            getStorage().markAllGroupMessagesRead(getChatId())
        } else {
            getStorage().markAllMessagesRead(getChatId())
        }
    }

    private fun showFab() {
        if (isFabShowing) return
        scrollToBottomFab?.let { fab ->
            isFabShowing = true
            fab.clearAnimation()
            fab.visibility = View.VISIBLE
            fab.alpha = 0f
            fab.animate()
                .alpha(1f)
                .setDuration(300)
                .start()
        }
    }

    private fun hideFab() {
        if (!isFabShowing) return
        scrollToBottomFab?.let { fab ->
            isFabShowing = false
            fab.clearAnimation()
            fab.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    if (!isFabShowing) {
                        fab.visibility = View.GONE
                    }
                }
                .start()
        }
    }

    // Reaction FAB Setup and Visibility

    private fun setupScrollToReactionFab() {
        scrollToReactionFab = findViewById(R.id.scroll_to_reaction_fab)
        scrollToReactionFab?.setOnClickListener {
            if (scrollToFirstUnseenReaction()) {
                // After scrolling, update the FAB visibility
                updateReactionFabVisibility()
            }
        }
        // Initial visibility check
        updateReactionFabVisibility()
    }

    /**
     * Updates the visibility of the reaction FAB based on unseen reactions count.
     */
    protected fun updateReactionFabVisibility() {
        val count = getUnseenReactionsCount()
        if (count > 0 && !isReactionFabShowing) {
            showReactionFab()
        } else if (count == 0 && isReactionFabShowing) {
            hideReactionFab()
        }
    }

    private fun showReactionFab() {
        if (isReactionFabShowing) return
        scrollToReactionFab?.let { fab ->
            isReactionFabShowing = true
            fab.clearAnimation()
            fab.visibility = View.VISIBLE
            fab.alpha = 0f
            fab.animate()
                .alpha(1f)
                .setDuration(300)
                .start()
        }
    }

    private fun hideReactionFab() {
        if (!isReactionFabShowing) return
        scrollToReactionFab?.let { fab ->
            isReactionFabShowing = false
            fab.clearAnimation()
            fab.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    if (!isReactionFabShowing) {
                        fab.visibility = View.GONE
                    }
                }
                .start()
        }
    }

    // Message List Helpers

    protected fun onClickOnReply() = fun(it: View) {
        val id = it.tag as Long
        val position = adapter.getMessageIdPosition(id)
        if (position >= 0) {
            recyclerView.smoothScrollToPosition(position)
        }
    }

    protected fun onClickOnPicture() = fun(it: View) {
        val uri = it.tag as Uri
        val intent = Intent(this, PictureActivity::class.java)
        intent.data = uri
        startActivity(intent)
    }

    // Attachment Handling

    protected fun toggleAttachmentMenu() {
        if (attachmentMenu.visibility == View.VISIBLE) {
            hideAttachmentMenu()
        } else {
            showAttachmentMenu()
        }
    }

    protected fun showAttachmentMenu() {
        attachmentMenu.visibility = View.VISIBLE
        attachmentMenu.translationY = attachmentMenu.height.toFloat()
        attachmentMenu.animate()
            .translationY(0f)
            .setDuration(200)
            .start()
    }

    protected fun hideAttachmentMenu() {
        attachmentMenu.animate()
            .translationY(attachmentMenu.height.toFloat())
            .setDuration(200)
            .withEndAction {
                attachmentMenu.visibility = View.GONE
            }
            .start()
    }

    private fun selectAndSendPicture() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        startActivityForResult(intent, PICK_IMAGE_REQUEST_CODE)
    }

    private fun checkAndRequestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                takePhoto()
            }
            else -> {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun takePhoto() {
        val photoFile = createImageFile()
        photoFile?.let {
            currentPhotoUri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.file_provider",
                it
            )

            val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, currentPhotoUri)
            startActivityForResult(intent, TAKE_PHOTO_REQUEST_CODE)
        } ?: run {
            Toast.makeText(this, "Error creating photo file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createImageFile(): File? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val imageFileName = "JPEG_${timeStamp}_"
            val storageDir = cacheDir
            File.createTempFile(imageFileName, ".jpg", storageDir)
        } catch (e: Exception) {
            Log.e(this::class.simpleName, "Error creating image file", e)
            null
        }
    }

    private fun selectAndSendFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(intent, PICK_FILE_REQUEST_CODE)
    }

    protected fun getImageFromUri(uri: Uri) {
        val fileSize = uri.length(this)
        if (fileSize > PICTURE_MAX_SIZE) {
            Toast.makeText(this, getString(R.string.too_big_picture_resizing), Toast.LENGTH_LONG).show()
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val resize = prefs.getInt(KEY_IMAGES_FORMAT, 0)
        val imageSize = ImageSize.fromInt(resize)
        val quality = prefs.getInt(KEY_IMAGES_QUALITY, 95)
        // TODO move usage of this function to another Thread
        val message = prepareFileForMessage(this, uri, imageSize, quality)
        Log.i(this::class.simpleName, "File message for $uri is $message")
        if (message != null) {
            attachmentType = 1
            val fileName = message.getString("name")
            val fileSize = message.optLong("size", 0)
            val preview = getImagePreview(this, fileName, 320, 85)

            // Update UI for image attachment
            attachmentPreview.setImageBitmap(preview)
            attachmentPreview.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            attachmentName.text = fileName
            attachmentSize.text = formatFileSize(fileSize)
            attachmentPanel.visibility = View.VISIBLE
            attachmentJson = message
        }
    }

    protected fun getFileFromUri(uri: Uri) {
        // TODO move usage of this function to another Thread
        val message = prepareGeneralFileForMessage(this, uri)
        Log.i(this::class.simpleName, "File message for $uri is $message")
        if (message != null) {
            attachmentType = 3 // Type 3 for file attachments in DB
            val fileName = message.optString("originalName", "file")
            val fileSize = message.optLong("size", 0)
            val mimeType = message.optString("mimeType", "application/octet-stream")

            // Validate file size (already checked in prepareGeneralFileForMessage, but double-check here)
            if (fileSize > MAX_FILE_SIZE) {
                Toast.makeText(this, getString(R.string.file_too_large), Toast.LENGTH_LONG).show()
                return
            }

            // Update UI for file attachment
            attachmentPreview.setImageResource(getFileIconForMimeType(mimeType))
            attachmentPreview.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            attachmentName.text = fileName
            attachmentSize.text = formatFileSize(fileSize)
            attachmentPanel.visibility = View.VISIBLE
            attachmentJson = message
        }
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }

    fun getFileIconForMimeType(mimeType: String): Int {
        return when {
            mimeType.startsWith("application/pdf") -> R.drawable.ic_file_document_outline
            mimeType.startsWith("application/zip") || mimeType.startsWith("application/x-") -> R.drawable.ic_file_document_outline
            mimeType.startsWith("text/") -> R.drawable.ic_file_document_outline
            mimeType.startsWith("audio/") -> R.drawable.ic_file_document_outline
            mimeType.startsWith("video/") -> R.drawable.ic_file_document_outline
            else -> R.drawable.ic_file_document_outline
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            PICK_IMAGE_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    if (data == null || data.data == null) {
                        Log.e(this::class.simpleName, "Error getting picture")
                        return
                    }
                    val selectedPictureUri = data.data!!
                    getImageFromUri(selectedPictureUri)
                }
            }
            TAKE_PHOTO_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    currentPhotoUri?.let { uri ->
                        Log.i(this::class.simpleName, "Photo captured: $uri")
                        getImageFromUri(uri)
                    } ?: run {
                        Log.e(this::class.simpleName, "Error: Photo URI is null")
                    }
                }
            }
            PICK_FILE_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    if (data == null || data.data == null) {
                        Log.e(this::class.simpleName, "Error getting file")
                        return
                    }
                    val selectedFileUri = data.data!!
                    getFileFromUri(selectedFileUri)
                }
            }
            REQUEST_FORWARD_MESSAGE -> {
                if (resultCode == RESULT_OK && data != null) {
                    handleForwardDestination(data)
                }
            }
        }
    }

    // Reply Panel Helpers

    protected fun showReplyPanel(authorName: String, messageText: String, messageGuid: Long) {
        replyName.text = authorName
        replyText.text = messageText
        replyPanel.visibility = View.VISIBLE
        replyTo = messageGuid
    }

    private fun clearReplyPanel() {
        replyPanel.visibility = View.GONE
        replyText.text = ""
        replyTo = 0L
    }

    protected fun clearAttachment() {
        if (attachmentJson != null) {
            attachmentPanel.visibility = View.GONE
            attachmentPreview.setImageDrawable(null)
            attachmentJson = null
        }
    }

    // Context Menu

    override fun onClick(view: View) {
        // Extract coordinates from the tag (set by MessageAdapter)
        val tag = view.tag as? MessageTag
        val (x, y) = if (tag != null && tag.touchX != 0 && tag.touchY != 0) {
            Pair(tag.touchX, tag.touchY)
        } else {
            // Fallback: use view center if coordinates not available
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            Pair(location[0] + view.width / 2, location[1] + view.height / 2)
        }

        showContextMenuAtLocation(view, x, y)
    }

    private fun showContextMenuAtLocation(anchorView: View, x: Int, y: Int) {
        val inflater = LayoutInflater.from(this)
        val elevation = 8f * resources.displayMetrics.density
        val margin = (4 * resources.displayMetrics.density).toInt()
        val spacing = (8 * resources.displayMetrics.density).toInt()

        // Get screen dimensions
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Get message type from tag to determine which elements to show
        val tag = anchorView.tag as? MessageTag
        val messageType = tag?.messageType ?: 0
        val showReactions = messageType != 1000  // Don't show reactions for system messages

        // === Create menu popup ===
        val menuView = inflater.inflate(R.layout.popup_message_context_menu, null)
        menuView.findViewById<View>(R.id.menu_card).elevation = elevation

        val menuPopup = PopupWindow(
            menuView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        menuPopup.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        menuPopup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        menuPopup.elevation = 8f * resources.displayMetrics.density

        // Hide "Save" menu item for messages without file attachments (type != 1 and type != 3)
        if (messageType != 1 && messageType != 3) {
            menuView.findViewById<View>(R.id.menu_save).visibility = View.GONE
        }

        // Measure menu popup
        menuView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val menuWidth = menuView.measuredWidth
        val menuHeight = menuView.measuredHeight

        // === Create reactions popup (if needed) ===
        var reactionsPopup: PopupWindow? = null
        var reactionsWidth = 0
        var reactionsHeight = 0

        if (showReactions) {
            val reactionsView = inflater.inflate(R.layout.popup_reactions, null)
            reactionsView.findViewById<View>(R.id.reactions_card).elevation = elevation

            reactionsPopup = PopupWindow(
                reactionsView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                false  // Not focusable - menu popup handles focus
            )
            reactionsPopup.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            reactionsPopup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            reactionsPopup.elevation = 8f * resources.displayMetrics.density

            // Set up emoji reaction buttons
            val targetGuid = tag?.guid ?: 0L
            val currentEmoji = getUserCurrentReaction(targetGuid)

            val emojiRow = reactionsView.findViewById<LinearLayout>(R.id.emoji_row)
            val emojiSize = (38 * resources.displayMetrics.density).toInt()

            for (emoji in getSortedReactionEmojis()) {
                val emojiView = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(emojiSize, LinearLayout.LayoutParams.MATCH_PARENT)
                    gravity = Gravity.CENTER
                    text = emoji
                    textSize = 22f
                    setBackgroundResource(R.drawable.contact_background)
                    setOnClickListener {
                        incrementEmojiUsage(emoji)
                        handleReaction(targetGuid, emoji, currentEmoji)
                        reactionsPopup.dismiss()
                        menuPopup.dismiss()
                    }
                }
                emojiRow.addView(emojiView)
            }

            // Measure reactions popup
            reactionsView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            reactionsWidth = reactionsView.measuredWidth
            reactionsHeight = reactionsView.measuredHeight
        }

        // === Calculate positions ===
        // Total height of both popups combined (for positioning)
        val totalHeight = if (showReactions) menuHeight + reactionsHeight + spacing else menuHeight

        // Calculate menu position (this is the "anchor" position)
        var menuX = x
        var menuY = y

        // Shift left if in right half of screen
        if (x > screenWidth / 2) {
            menuX = x - menuWidth
        }

        // Shift up if in bottom half of screen
        if (y > screenHeight / 2) {
            menuY = y - totalHeight
            if (showReactions) {
                menuY += reactionsHeight + spacing  // Adjust so menu is at the bottom
            }
        }

        // Ensure menu stays within screen bounds
        val menuMaxX = maxOf(margin, screenWidth - menuWidth - margin)
        val menuMaxY = maxOf(margin, screenHeight - menuHeight - margin)
        menuX = menuX.coerceIn(margin, menuMaxX)
        menuY = menuY.coerceIn(margin, menuMaxY)

        // Calculate reactions position (above the menu)
        var reactionsX = menuX + (menuWidth - reactionsWidth) / 2  // Center horizontally relative to menu
        var reactionsY = menuY - reactionsHeight - spacing

        // Ensure reactions stays within screen bounds
        if (showReactions) {
            val reactionsMaxX = maxOf(margin, screenWidth - reactionsWidth - margin)
            reactionsX = reactionsX.coerceIn(margin, reactionsMaxX)
            // If reactions would go above screen, place it below the menu instead
            if (reactionsY < margin) {
                reactionsY = menuY + menuHeight + spacing
            }
        }

        // === Set up menu click handlers ===
        menuView.setOnClickListener {
            reactionsPopup?.dismiss()
            menuPopup.dismiss()
        }

        menuView.findViewById<View>(R.id.menu_reply).setOnClickListener {
            handleReply(anchorView)
            reactionsPopup?.dismiss()
            menuPopup.dismiss()
        }

        menuView.findViewById<View>(R.id.menu_copy).setOnClickListener {
            handleCopy(anchorView)
            reactionsPopup?.dismiss()
            menuPopup.dismiss()
        }

        menuView.findViewById<View>(R.id.menu_forward).setOnClickListener {
            handleForward(anchorView)
            reactionsPopup?.dismiss()
            menuPopup.dismiss()
        }

        menuView.findViewById<View>(R.id.menu_delete).setOnClickListener {
            handleDelete(anchorView)
            reactionsPopup?.dismiss()
            menuPopup.dismiss()
        }

        menuView.findViewById<View>(R.id.menu_save).setOnClickListener {
            handleSave(anchorView)
            reactionsPopup?.dismiss()
            menuPopup.dismiss()
        }

        // Dismiss reactions when menu is dismissed
        menuPopup.setOnDismissListener {
            reactionsPopup?.dismiss()
        }

        // Make menu popup dismissable by touching outside
        menuPopup.isOutsideTouchable = true
        menuPopup.isFocusable = true

        // === Show popups ===
        // Show menu popup first
        menuPopup.showAtLocation(recyclerView, Gravity.NO_GRAVITY, menuX, menuY)

        // Show reactions popup above menu (if needed)
        reactionsPopup?.let {
            it.isOutsideTouchable = true
            it.isFocusable = false
            it.showAtLocation(recyclerView, Gravity.NO_GRAVITY, reactionsX, reactionsY)
        }
    }

    private fun handleCopy(view: View): Boolean {
        val textview = view.findViewById<AppCompatTextView>(R.id.text)
        val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Mimir message", textview.text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(applicationContext, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        return true
    }

    private fun handleReply(view: View): Boolean {
        // Extract message ID and GUID from tag
        val tag = view.tag as? MessageTag ?: return false
        val guid = tag.guid

        val replyInfo = getMessageForReply(guid)
        if (replyInfo != null) {
            val (authorName, messageText) = replyInfo
            showReplyPanel(authorName, messageText, guid)
        }
        return false
    }

    private fun handleForward(view: View): Boolean {
        // Extract message ID and GUID from tag
        val tag = view.tag as? MessageTag ?: return false
        val messageId = tag.messageId
        val guid = tag.guid

        val message = getMessageFromStorage(messageId)

        if (message == null) {
            Toast.makeText(this, "Message not found", Toast.LENGTH_SHORT).show()
            return false
        }

        // Filter out system messages
        if (message.type == 1000) {
            Toast.makeText(this, "Cannot forward system messages", Toast.LENGTH_SHORT).show()
            return false
        }

        // Launch ForwardSelectorActivity
        val intent = Intent(this, ForwardSelectorActivity::class.java)
        intent.putExtra("FORWARD_MESSAGE_ID", messageId)
        intent.putExtra("FORWARD_MESSAGE_GUID", guid)
        intent.putExtra("FORWARD_MESSAGE_TYPE", message.type)
        intent.putExtra("FORWARD_MESSAGE_TEXT", message.getText(this))

        // Include media JSON for images/files
        if (message.type == 1 || message.type == 3) {
            message.data?.let {
                intent.putExtra("FORWARD_MESSAGE_JSON", String(it))
            }
        }

        startActivityForResult(intent, REQUEST_FORWARD_MESSAGE)
        return true
    }

    private fun handleSave(view: View): Boolean {
        // Extract message ID and GUID from tag
        val tag = view.tag as? MessageTag ?: return false
        val id = tag.messageId

        val message = getMessageFromStorage(id)
        if (message == null) {
            Toast.makeText(this, "Message not found", Toast.LENGTH_SHORT).show()
            return false
        }

        // Only save image (type 1) or file (type 3) attachments
        if (message.type != 1 && message.type != 3) {
            Toast.makeText(this, "This message has no file to save", Toast.LENGTH_SHORT).show()
            return false
        }

        if (message.data == null) {
            Toast.makeText(this, "No file data available", Toast.LENGTH_SHORT).show()
            return false
        }

        try {
            val json = JSONObject(String(message.data))
            val fileName = json.getString("name")
            val originalName = json.optString("originalName", fileName)

            // Get the file from internal storage
            val filesDir = File(this.filesDir, "files")
            val sourceFile = File(filesDir, fileName)

            if (!sourceFile.exists()) {
                Toast.makeText(this, getString(R.string.file_not_found_in_storage), Toast.LENGTH_SHORT).show()
                return false
            }

            // Save to Downloads/Mimir folder
            saveFileToDownloads(sourceFile, originalName)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving file", e)
            Toast.makeText(this, "Error saving file: ${e.message}", Toast.LENGTH_LONG).show()
            return false
        }
    }

    private fun saveFileToDownloads(sourceFile: File, originalName: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ (API 29+): Use MediaStore API
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, originalName)
                    put(MediaStore.Downloads.MIME_TYPE, getMimeTypeFromFilename(originalName))
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Mimir")
                }

                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        sourceFile.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    Toast.makeText(this, getString(R.string.file_saved_to_downloads), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to create file in Downloads", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Android 9 and below (API 21-28): Use classic file path
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val mimirDir = File(downloadsDir, "Mimir")
                if (!mimirDir.exists()) {
                    mimirDir.mkdirs()
                }

                val destFile = File(mimirDir, originalName)
                sourceFile.inputStream().use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Toast.makeText(this, getString(R.string.file_saved_to_downloads), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving file to Downloads", e)
            Toast.makeText(this, "Error saving file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleDelete(view: View): Boolean {
        // Extract message ID and GUID from tag
        val tag = view.tag as? MessageTag ?: return false
        val id = tag.messageId
        val guid = tag.guid

        showDeleteMessageConfirmDialog(id, guid)
        return true
    }

    private fun showDeleteMessageConfirmDialog(messageId: Long, guid: Long) {
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        val builder: AlertDialog.Builder = AlertDialog.Builder(wrapper)
        builder.setTitle(getString(R.string.delete_message_dialog_title))
        builder.setMessage(R.string.delete_message_dialog_text)
        builder.setIcon(R.drawable.ic_delete)
        builder.setPositiveButton(getString(R.string.menu_delete)) { _, _ ->
            deleteMessageByIdOrGuid(messageId, guid)
            adapter.deleteMessageId(messageId)
        }
        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
            dialog.cancel()
        }
        builder.show()
    }

    // Forward Message Handling

    private fun handleForwardDestination(data: Intent) {
        when (data.getStringExtra(ForwardSelectorActivity.RESULT_CHAT_TYPE)) {
            ForwardSelectorActivity.CHAT_TYPE_SAVED -> saveToSavedMessages(data)
            ForwardSelectorActivity.CHAT_TYPE_CONTACT -> openContactChatWithForward(data)
            ForwardSelectorActivity.CHAT_TYPE_GROUP -> openGroupChatWithForward(data)
        }
    }

    private fun saveToSavedMessages(data: Intent) {
        val messageText = data.getStringExtra("FORWARD_MESSAGE_TEXT") ?: ""
        val messageType = data.getIntExtra("FORWARD_MESSAGE_TYPE", 0)
        val messageJson = data.getStringExtra("FORWARD_MESSAGE_JSON")

        // Get current user's pubkey for saved messages
        val accountInfo = getStorage().getAccountInfo(1, 0L)
        val myPubkey = (accountInfo!!.keyPair.public as org.bouncycastle.crypto.params.Ed25519PublicKeyParameters).encoded

        // Send to ConnectionService which properly handles saved messages (sending to self)
        val serviceIntent = Intent(this, ConnectionService::class.java)
        serviceIntent.putExtra("command", "send")
        serviceIntent.putExtra("pubkey", myPubkey)
        serviceIntent.putExtra("replyTo", 0L)

        if (messageType == 1 || messageType == 3) {
            // Media message
            serviceIntent.putExtra("type", messageType)
            serviceIntent.putExtra("message", messageJson ?: messageText)
        } else {
            // Text message
            serviceIntent.putExtra("message", messageText)
        }

        startService(serviceIntent)

        // Show snackbar notification
        showForwardedToSavedSnackbar()
    }

    private fun showForwardedToSavedSnackbar() {
        val rootView = findViewById<View>(android.R.id.content)
        com.google.android.material.snackbar.Snackbar.make(
            rootView,
            getString(R.string.message_saved_to_saved_messages),
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        ).setAction(getString(R.string.show)) {
            val intent = Intent(this, ChatActivity::class.java)
            intent.putExtra("contactId", SqlStorage.SAVED_MESSAGES_CONTACT_ID)
            intent.putExtra("name", getString(R.string.saved_messages))
            intent.putExtra("savedMessages", true)
            startActivity(intent, animFromRight.toBundle())
        }.show()
    }

    private fun openContactChatWithForward(data: Intent) {
        val intent = Intent(this, ChatActivity::class.java)
        intent.putExtra("pubkey", data.getByteArrayExtra(ForwardSelectorActivity.RESULT_PUBKEY))
        intent.putExtra("name", data.getStringExtra(ForwardSelectorActivity.RESULT_NAME))
        copyForwardExtras(intent, data)
        startActivity(intent, animFromRight.toBundle())
    }

    private fun openGroupChatWithForward(data: Intent) {
        val intent = Intent(this, GroupChatActivity::class.java)
        intent.putExtra(GroupChatActivity.EXTRA_CHAT_ID, data.getLongExtra(ForwardSelectorActivity.RESULT_CHAT_ID, 0))
        intent.putExtra(GroupChatActivity.EXTRA_CHAT_NAME, data.getStringExtra(ForwardSelectorActivity.RESULT_NAME))
        intent.putExtra(GroupChatActivity.EXTRA_CHAT_DESCRIPTION, data.getStringExtra(ForwardSelectorActivity.RESULT_DESCRIPTION))
        intent.putExtra(GroupChatActivity.EXTRA_IS_OWNER, data.getBooleanExtra(ForwardSelectorActivity.RESULT_IS_OWNER, false))
        intent.putExtra(GroupChatActivity.EXTRA_MEDIATOR_ADDRESS, data.getByteArrayExtra(ForwardSelectorActivity.RESULT_MEDIATOR_ADDRESS))
        copyForwardExtras(intent, data)
        startActivity(intent, animFromRight.toBundle())
    }

    private fun copyForwardExtras(target: Intent, source: Intent) {
        target.putExtra("FORWARD_MODE", true)
        target.putExtra("FORWARD_MESSAGE_ID", source.getLongExtra("FORWARD_MESSAGE_ID", 0))
        target.putExtra("FORWARD_MESSAGE_GUID", source.getLongExtra("FORWARD_MESSAGE_GUID", 0))
        target.putExtra("FORWARD_MESSAGE_TYPE", source.getIntExtra("FORWARD_MESSAGE_TYPE", 0))
        target.putExtra("FORWARD_MESSAGE_TEXT", source.getStringExtra("FORWARD_MESSAGE_TEXT"))
        source.getStringExtra("FORWARD_MESSAGE_JSON")?.let {
            target.putExtra("FORWARD_MESSAGE_JSON", it)
        }
    }

    // Draft Management

    /**
     * Saves the current message draft (text and attachment) to the database.
     * Called when the activity stops (user navigates away).
     */
    private fun saveDraft() {
        val editText = findViewById<AppCompatEditText>(R.id.message_edit)
        val messageText = editText.text?.toString()?.trim()

        // Get filename from attachmentJson if present
        val fileName = attachmentJson?.optString("name")
        val mediaType = if (attachmentJson != null) attachmentType else 0

        // Construct full path if we have a filename
        val mediaUri = if (!fileName.isNullOrEmpty()) {
            val filesDir = File(this.filesDir, "files")
            File(filesDir, fileName).absolutePath
        } else {
            null
        }

        // Only save if there's actual content
        if (!messageText.isNullOrEmpty() || mediaUri != null) {
            val chatType = if (isGroupChat()) SqlStorage.CHAT_TYPE_GROUP else SqlStorage.CHAT_TYPE_CONTACT
            getStorage().saveDraft(chatType, getChatId(), messageText, mediaUri, mediaType)
        } else {
            // Clear draft if there's no content
            val chatType = if (isGroupChat()) SqlStorage.CHAT_TYPE_GROUP else SqlStorage.CHAT_TYPE_CONTACT
            getStorage().deleteDraft(chatType, getChatId())
        }
    }

    /**
     * Restores a saved draft (text and attachment) from the database.
     * Called when the activity resumes (becomes visible).
     */
    private fun restoreDraft() {
        val chatType = if (isGroupChat()) SqlStorage.CHAT_TYPE_GROUP else SqlStorage.CHAT_TYPE_CONTACT
        val draft = getStorage().getDraft(chatType, getChatId())

        if (draft != null) {
            // Restore text
            if (!draft.text.isNullOrEmpty()) {
                val editText = findViewById<AppCompatEditText>(R.id.message_edit)
                editText.setText(draft.text)
                editText.setSelection(draft.text.length) // Move cursor to end
            }

            // Restore attachment if present
            if (!draft.mediaUri.isNullOrEmpty() && draft.mediaType != 0) {
                try {
                    val file = File(draft.mediaUri)
                    if (file.exists()) {
                        // Reconstruct the attachment JSON from the saved file
                        val fileName = file.name
                        val fileSize = file.length()

                        val message = JSONObject()
                        message.put("name", fileName)
                        message.put("size", fileSize)

                        attachmentType = draft.mediaType
                        attachmentJson = message

                        // Update UI based on media type
                        when (draft.mediaType) {
                            1 -> { // Image
                                val preview = getImagePreview(this, fileName, 320, 85)
                                attachmentPreview.setImageBitmap(preview)
                                attachmentPreview.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            }
                            3 -> { // File
                                // Try to determine MIME type from extension
                                val extension = fileName.substringAfterLast('.', "")
                                val mimeType = when (extension.lowercase()) {
                                    "pdf" -> "application/pdf"
                                    "zip" -> "application/zip"
                                    "txt" -> "text/plain"
                                    else -> "application/octet-stream"
                                }
                                attachmentPreview.setImageResource(getFileIconForMimeType(mimeType))
                                attachmentPreview.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                            }
                        }

                        attachmentName.text = fileName
                        attachmentSize.text = formatFileSize(fileSize)
                        attachmentPanel.visibility = View.VISIBLE
                    } else {
                        // File no longer exists, clear the draft
                        getStorage().deleteDraft(chatType, getChatId())
                    }
                } catch (e: Exception) {
                    Log.w("BaseChatActivity", "Failed to restore draft attachment: ${e.message}")
                }
            }
        }
    }

    // Lifecycle

    override fun onStart() {
        super.onStart()
        isChatVisible = true
    }

    override fun onPause() {
        super.onPause()
        saveDraft()
    }

    override fun onStop() {
        super.onStop()
        isChatVisible = false
    }

    override fun onResume() {
        super.onResume()
        restoreDraft()
        // Update reaction FAB visibility when resuming
        if (::recyclerView.isInitialized) {
            updateReactionFabVisibility()
        }
    }

    override fun onDestroy() {
        getStorage().listeners.remove(this)
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.hold_still, R.anim.slide_out_right)
    }

    // Other

    fun startShortSound(soundRes: Int) {
        if (System.currentTimeMillis() + 300 > lastSoundTime) {
            return
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val mediaPlayer = MediaPlayer.create(this, soundRes, attributes, 0).apply {
            setOnCompletionListener { player ->
                player?.release()
            }
            isLooping = false
        }
        mediaPlayer.start()
        lastSoundTime = System.currentTimeMillis()
    }
}