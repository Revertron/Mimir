package com.revertron.mimir

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.storage.StorageListener
import com.revertron.mimir.ui.MessageAdapter
import com.revertron.mimir.ui.SettingsData.KEY_IMAGES_FORMAT
import com.revertron.mimir.ui.SettingsData.KEY_IMAGES_QUALITY
import com.revertron.mimir.ui.SettingsData.KEY_MESSAGE_FONT_SIZE
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        const val PICK_IMAGE_REQUEST_CODE = 123
        const val TAKE_PHOTO_REQUEST_CODE = 124
        const val PICK_FILE_REQUEST_CODE = 125
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

    // State
    protected var isChatVisible: Boolean = false
    protected var lastSoundTime = 0L

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
            if (text.isNotEmpty() || attachmentJson != null) {
                editText.text?.clear()
                sendMessage(text, replyTo)
                clearReplyPanel()
                clearAttachment()
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

        // Scroll to first unread message if any, otherwise scroll to end
        val firstUnreadId = getFirstUnreadMessageId()
        if (firstUnreadId != null) {
            val position = adapter.getMessageIdPosition(firstUnreadId)
            if (position >= 0) {
                recyclerView.post {
                    recyclerView.scrollToPosition(position)
                }
            }
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

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
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
        val popup = PopupMenu(this, view, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        popup.inflate(R.menu.menu_context_message)
        popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_copy -> {
                    handleCopy(view)
                    true
                }
                R.id.menu_reply -> {
                    handleReply(view)
                    false
                }
                R.id.menu_forward -> {
                    handleForward(view)
                    false
                }
                R.id.menu_delete -> {
                    handleDelete(view)
                    true
                }
                else -> {
                    Log.w(this::class.simpleName, "Not implemented handler for menu item ${it.itemId}")
                    false
                }
            }
        }
        popup.show()
    }

    private fun handleCopy(view: View): Boolean {
        val textview = view.findViewById<AppCompatTextView>(R.id.text)
        val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Mimir message", textview.text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(applicationContext, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleReply(view: View): Boolean {
        val (_, guid) = view.tag as Pair<Long, Long>
        val replyInfo = getMessageForReply(guid)
        if (replyInfo != null) {
            val (authorName, messageText) = replyInfo
            showReplyPanel(authorName, messageText, guid)
        }
        return false
    }

    private fun handleForward(view: View): Boolean {
        Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
        return false
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleDelete(view: View): Boolean {
        val (id, guid) = view.tag as Pair<Long, Long>
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

    // Lifecycle

    override fun onStart() {
        super.onStart()
        isChatVisible = true
    }

    override fun onStop() {
        super.onStop()
        isChatVisible = false
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