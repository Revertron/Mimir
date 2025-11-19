package com.revertron.mimir

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.*
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.net.PeerStatus
import com.revertron.mimir.storage.StorageListener
import com.revertron.mimir.ui.Contact
import com.revertron.mimir.ui.MessageAdapter
import com.revertron.mimir.ui.SettingsData.KEY_IMAGES_FORMAT
import com.revertron.mimir.ui.SettingsData.KEY_IMAGES_QUALITY
import org.bouncycastle.util.encoders.Hex
import org.json.JSONObject
import java.io.File
import java.lang.Thread.sleep


class ChatActivity : BaseActivity(), Toolbar.OnMenuItemClickListener, StorageListener {

    companion object {
        const val TAG = "ChatActivity"
        const val PICK_IMAGE_REQUEST_CODE = 123
        private const val TAKE_PHOTO_REQUEST_CODE = 125
    }

    lateinit var contact: Contact
    lateinit var replyPanel: LinearLayoutCompat
    lateinit var replyName: AppCompatTextView
    private lateinit var replyText: AppCompatTextView
    private lateinit var attachmentPanel: ConstraintLayout
    private lateinit var attachmentPreview: AppCompatImageView
    private lateinit var adapter: MessageAdapter
    private var attachmentJson: JSONObject? = null
    private var isVisible: Boolean = false
    private var currentPhotoUri: Uri? = null
    var replyTo = 0L

    private val peerStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action == "ACTION_PEER_STATUS") {
                val status: PeerStatus = intent.getSerializableExtra("status") as PeerStatus
                val from = intent.getStringExtra("contact")
                if (from != null && contact.pubkey.contentEquals(Hex.decode(from))) {
                    showOnlineState(status)
                }
            }
        }
    }

    private val reactionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action == "ACTION_REACTION_UPDATED") {
                val messageGuid = intent.getLongExtra("messageGuid", 0L)
                if (messageGuid != 0L) {
                    // Find the message in adapter and refresh it
                    val messageId = getStorage().getMessageIdByGuid(messageGuid)
                    if (messageId != null) {
                        val position = adapter.getMessageIdPosition(messageId)
                        if (position >= 0) {
                            runOnUiThread {
                                adapter.notifyItemChanged(position)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        toolbar.setOnClickListener {
            val intent = Intent(this, ContactActivity::class.java)
            intent.putExtra("pubkey", contact.pubkey)
            intent.putExtra("name", contact.name)
            startActivity(intent, animFromRight.toBundle())
        }

        val pubkey = intent.getByteArrayExtra("pubkey").apply { if (this == null) finish() }!!
        val name = intent.getStringExtra("name").apply { if (this == null) finish() }!!
        val id = getStorage().getContactId(pubkey)
        val avatarPic = getStorage().getContactAvatar(id)
        contact = Contact(id, pubkey, name, null, 0, avatarPic)

        findViewById<AppCompatTextView>(R.id.title).text = contact.name
        supportActionBar?.title = ""
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val avatar = findViewById<AppCompatImageView>(R.id.avatar)
        if (contact.avatar != null) {
            avatar.clearColorFilter()
            avatar.setImageDrawable(contact.avatar)
        } else {
            avatar.setImageResource(R.drawable.button_rounded_white)
            val avatarColor = getAvatarColor(contact.pubkey)
            avatar.setColorFilter(avatarColor, PorterDuff.Mode.MULTIPLY)
        }

        replyPanel = findViewById(R.id.reply_panel)
        replyPanel.visibility = View.GONE
        replyName = findViewById(R.id.reply_contact_name)
        replyText = findViewById(R.id.reply_text)
        findViewById<AppCompatImageView>(R.id.reply_close).setOnClickListener {
            replyPanel.visibility = View.GONE
            replyTo = 0L
        }

        val editText = findViewById<AppCompatEditText>(R.id.message_edit)
        val sendButton = findViewById<AppCompatImageButton>(R.id.send_button)
        sendButton.setOnClickListener {
            val text: String = editText.text.toString().trim()
            if (text.isNotEmpty() || attachmentJson != null) {
                editText.text?.clear()
                sendMessage(contact.pubkey, text, replyTo)
                replyPanel.visibility = View.GONE
                replyText.text = ""
                replyTo = 0L
            }
        }
        findViewById<AppCompatImageButton>(R.id.attach_button).setOnClickListener {
            selectAndSendPicture()
        }
        attachmentPanel = findViewById(R.id.attachment)
        attachmentPanel.visibility = View.GONE
        attachmentPreview = findViewById(R.id.attachment_image)
        val attachmentCancel = findViewById<AppCompatImageView>(R.id.attachment_cancel)
        attachmentCancel.setOnClickListener {
            attachmentPreview.setImageDrawable(null)
            attachmentPanel.visibility = View.GONE
            attachmentJson?.getString("name")?.apply {
                deleteFileAndPreview(this@ChatActivity, this)
            }
            attachmentJson = null
        }

        val image = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (image != null) {
            getImageFromUri(image)
        }

        adapter = MessageAdapter(getStorage(), contact.id, groupChat = false, contact.name, onLongClickMessage(), onClickOnReply(), onClickOnPicture())
        val recycler = findViewById<RecyclerView>(R.id.messages_list)
        recycler.adapter = adapter
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        getStorage().listeners.add(this)

        showOnlineState(PeerStatus.Connecting)
        LocalBroadcastManager.getInstance(this).registerReceiver(peerStatusReceiver, IntentFilter("ACTION_PEER_STATUS"))
        LocalBroadcastManager.getInstance(this).registerReceiver(reactionReceiver, IntentFilter("ACTION_REACTION_UPDATED"))
        fetchStatus(this, contact.pubkey)

        Thread {
            sleep(500)
            connect(this@ChatActivity, contact.pubkey)
        }.start()
    }

    override fun onStart() {
        super.onStart()
        isVisible = true
    }

    override fun onStop() {
        super.onStop()
        isVisible = false
    }

    private fun onClickOnReply() = fun(it: View) {
        //TODO animate target view
        val id = it.tag as Long
        val recycler = findViewById<RecyclerView>(R.id.messages_list)
        val adapter = recycler.adapter as MessageAdapter
        val position = adapter.getMessageIdPosition(id)
        if (position >= 0) {
            recycler.smoothScrollToPosition(position)
        }
    }

    private fun onClickOnPicture() = fun(it: View) {
        val uri = it.tag as Uri
        val intent = Intent(this, PictureActivity::class.java)
        intent.data = uri
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.menu_contact, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
            R.id.contact_call -> {
                checkAndRequestAudioPermission()
            }
            R.id.clear_history -> {

            }

            else -> {
                Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
            }
        }

        return super.onOptionsItemSelected(item)
    }

    private fun makeCall() {
        val intent = Intent(this, ConnectionService::class.java)
        intent.putExtra("command", "call")
        intent.putExtra("pubkey", contact.pubkey)
        startService(intent)

        val storage = (application as App).storage
        val contactId = storage.getContactId(contact.pubkey)
        val name = storage.getContactName(contactId)

        val callIntent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("pubkey", contact.pubkey)
            putExtra("name", name)
            putExtra("outgoing", true)
        }
        startActivity(callIntent, animFromRight.toBundle())
    }

    override fun onDestroy() {
        getStorage().listeners.remove(this)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(peerStatusReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(reactionReceiver)
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.hold_still, R.anim.slide_out_right)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        Log.i(TAG, "Clicked on ${item?.itemId}")
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            PICK_IMAGE_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    if (data == null || data.data == null) {
                        Log.e(TAG, "Error getting picture from gallery")
                        return
                    }
                    val selectedPictureUri = data.data!!
                    getImageFromUri(selectedPictureUri)
                }
            }
            TAKE_PHOTO_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    currentPhotoUri?.let { uri ->
                        getImageFromUri(uri)
                    } ?: run {
                        Log.e(TAG, "Error: photo URI is null")
                        Toast.makeText(this, R.string.error_taking_photo, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    fun showOnlineState(status: PeerStatus) {
        val imageView = findViewById<AppCompatImageView>(R.id.status_image)
        when (status) {
            PeerStatus.NotConnected -> imageView.setImageResource(R.drawable.status_badge_red)
            PeerStatus.Connecting -> imageView.setImageResource(R.drawable.status_badge_yellow)
            PeerStatus.Connected -> imageView.setImageResource(R.drawable.status_badge_green)
            PeerStatus.ErrorConnecting -> imageView.setImageResource(R.drawable.status_badge_red)
        }
    }

    private fun getImageFromUri(uri: Uri) {
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
        Log.i(TAG, "File message for $uri is $message")
        if (message != null) {
            val fileName = message.getString("name")
            val preview = getImagePreview(this, fileName, 320, 85)
            attachmentPreview.setImageBitmap(preview)
            attachmentPanel.visibility = View.VISIBLE
            attachmentJson = message
        }
    }

    private fun sendMessage(pubkey: ByteArray, text: String, replyTo: Long) {
        val intent = Intent(this, ConnectionService::class.java)
        intent.putExtra("command", "send")
        intent.putExtra("pubkey", pubkey)
        intent.putExtra("replyTo", replyTo)
        if (attachmentJson != null) {
            intent.putExtra("type", 1)
            attachmentJson!!.put("text", text)
            intent.putExtra("message", attachmentJson.toString())
            attachmentPanel.visibility = View.GONE
            // TODO check for leaks
            attachmentPreview.setImageDrawable(null)
            attachmentJson = null
        } else {
            intent.putExtra("message", text)
        }
        startService(intent)
    }

    private fun selectAndSendPicture() {
        showImageSourceDialog()
    }

    private fun showImageSourceDialog() {
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        AlertDialog.Builder(wrapper)
            .setTitle(R.string.choose_image_source)
            .setItems(arrayOf(
                getString(R.string.take_photo),
                getString(R.string.choose_from_gallery)
            )) { _, which ->
                when (which) {
                    0 -> checkCameraPermissionAndTakePhoto()
                    1 -> pickImageFromGallery()
                }
            }
            .show()
    }

    private fun checkCameraPermissionAndTakePhoto() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                takePhoto()
            }
            else -> {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                takePhoto()
            } else {
                Toast.makeText(this, R.string.toast_no_permission, Toast.LENGTH_SHORT).show()
            }
        }

    private fun takePhoto() {
        try {
            val cameraDir = File(cacheDir, "camera")
            if (!cameraDir.exists()) {
                cameraDir.mkdirs()
            }

            val photoFile = File(cameraDir, "photo_${System.currentTimeMillis()}.jpg")
            currentPhotoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.file_provider",
                photoFile
            )

            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, currentPhotoUri)
            startActivityForResult(intent, TAKE_PHOTO_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating camera intent", e)
            Toast.makeText(this, R.string.error_taking_photo, Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickImageFromGallery() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        startActivityForResult(intent, PICK_IMAGE_REQUEST_CODE)
    }

    override fun onMessageSent(id: Long, contactId: Long) {
        runOnUiThread {
            Log.i(TAG, "Message $id sent to $contactId")
            if (contact.id == contactId) {
                val recycler = findViewById<RecyclerView>(R.id.messages_list)
                val adapter = recycler.adapter as MessageAdapter
                adapter.addMessageId(id, false)
                recycler.smoothScrollToPosition(adapter.itemCount)
            }
        }
    }

    override fun onMessageDelivered(id: Long, delivered: Boolean) {
        Log.i(TAG, "Message $id delivered = $delivered")
        runOnUiThread {
            val recycler = findViewById<RecyclerView>(R.id.messages_list)
            val adapter = recycler.adapter as MessageAdapter
            adapter.setMessageDelivered(id, delivered)
            startDeliveredSound()
        }
    }

    override fun onMessageReceived(id: Long, contactId: Long): Boolean {
        Log.i(TAG, "Message $id from $contactId")
        if (contact.id == contactId) {
            runOnUiThread {
                Log.i(TAG, "Adding message")
                val recycler = findViewById<RecyclerView>(R.id.messages_list)
                val adapter = recycler.adapter as MessageAdapter
                adapter.addMessageId(id, true)
                if (isVisible) {
                    //TODO scroll only if was already at the bottom
                    recycler.smoothScrollToPosition(adapter.itemCount)
                }
            }
            return isVisible
        }
        return false
    }

    private fun onLongClickMessage() = View.OnLongClickListener { view ->
        // Show combined reactions and context menu
        showCombinedContextMenu(view)
        true
    }

    private var contextMenuWindow: android.widget.PopupWindow? = null

    private fun showCombinedContextMenu(messageView: View) {
        // Dismiss existing window if any
        contextMenuWindow?.dismiss()

        val messageId = messageView.tag as? Long ?: return
        val message = getStorage().getMessage(messageId) ?: return

        // Inflate combined menu (reactions + actions)
        val menuView = layoutInflater.inflate(R.layout.message_context_menu, null)

        val reactions = mapOf(
            R.id.reaction_thumbsup to "👍",
            R.id.reaction_thumbsdown to "👎",
            R.id.reaction_fire to "🔥",
            R.id.reaction_laugh to "😂",
            R.id.reaction_sad to "😢"
        )

        // Get current user pubkey for reaction handling
        val currentUserPubkey = getStorage().getAccountInfo(1, 0L)?.let {
            (it.keyPair.public as org.bouncycastle.crypto.params.Ed25519PublicKeyParameters).encoded
        }

        // Setup reaction buttons
        for ((viewId, emoji) in reactions) {
            menuView.findViewById<View>(viewId)?.setOnClickListener {
                if (currentUserPubkey != null) {
                    // Check if user has an existing reaction
                    val existingReaction = getStorage().getUserCurrentReaction(message.guid, null, currentUserPubkey)

                    // Toggle the reaction locally
                    val added = getStorage().toggleReaction(message.guid, null, currentUserPubkey, emoji)
                    adapter.notifyDataSetChanged()

                    // If user had a different reaction, send removal first
                    if (existingReaction != null && existingReaction != emoji) {
                        val removeIntent = Intent(this, ConnectionService::class.java)
                        removeIntent.putExtra("command", "ACTION_SEND_REACTION")
                        removeIntent.putExtra("messageGuid", message.guid)
                        removeIntent.putExtra("emoji", existingReaction)
                        removeIntent.putExtra("add", false)
                        removeIntent.putExtra("contactPubkey", contact.pubkey)
                        startService(removeIntent)
                    }

                    // Send the new reaction
                    val intent = Intent(this, ConnectionService::class.java)
                    intent.putExtra("command", "ACTION_SEND_REACTION")
                    intent.putExtra("messageGuid", message.guid)
                    intent.putExtra("emoji", emoji)
                    intent.putExtra("add", added)
                    intent.putExtra("contactPubkey", contact.pubkey)
                    startService(intent)
                }

                contextMenuWindow?.dismiss()
            }
        }

        // Setup action menu items
        menuView.findViewById<View>(R.id.menu_reply)?.setOnClickListener {
            replyName.text = contact.name
            replyText.text = message.getText(this)
            replyPanel.visibility = View.VISIBLE
            replyTo = message.guid
            Log.i(TAG, "Replying to guid $replyTo")
            contextMenuWindow?.dismiss()
        }

        menuView.findViewById<View>(R.id.menu_copy)?.setOnClickListener {
            val textview = messageView.findViewById<AppCompatTextView>(R.id.text)
            val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Mimir message", textview.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(applicationContext, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
            contextMenuWindow?.dismiss()
        }

        menuView.findViewById<View>(R.id.menu_forward)?.setOnClickListener {
            // Forward functionality not implemented yet
            contextMenuWindow?.dismiss()
        }

        menuView.findViewById<View>(R.id.menu_delete)?.setOnClickListener {
            showDeleteMessageConfirmDialog(messageId)
            contextMenuWindow?.dismiss()
        }

        // Create popup window
        val popupWindow = android.widget.PopupWindow(
            menuView,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.isOutsideTouchable = true
        popupWindow.isFocusable = true

        // Calculate position above the message
        val location = IntArray(2)
        messageView.getLocationOnScreen(location)

        // Measure the popup
        menuView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupHeight = menuView.measuredHeight
        val popupWidth = menuView.measuredWidth

        // Position above the message, centered
        val xOffset = (messageView.width - popupWidth) / 2
        val yOffset = -(popupHeight + 10)  // 10dp above message

        popupWindow.showAsDropDown(messageView, xOffset, yOffset)
        contextMenuWindow = popupWindow
    }

    private fun showDeleteMessageConfirmDialog(messageId: Long) {
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        val builder: AlertDialog.Builder = AlertDialog.Builder(wrapper)
        builder.setTitle(getString(R.string.delete_message_dialog_title))
        builder.setMessage(R.string.delete_message_dialog_text)
        builder.setIcon(R.drawable.ic_delete)
        builder.setPositiveButton(getString(R.string.menu_delete)) { _, _ ->
            (application as App).storage.deleteMessage(messageId)
            val recycler = findViewById<RecyclerView>(R.id.messages_list)
            val adapter = recycler.adapter as MessageAdapter
            adapter.deleteMessageId(messageId)
        }
        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
            dialog.cancel()
        }
        builder.show()
    }

    private fun startDeliveredSound() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val mediaPlayer = MediaPlayer.create(this, R.raw.message_sent, attributes, 0).apply {
            setOnCompletionListener { player ->
                player?.release()
            }
            isLooping = false
        }
        mediaPlayer.start()
    }

    private fun checkAndRequestAudioPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                // Already have that permission
                makeCall()
            }

            /*shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                // If the user already declined such requests we need to show some text in dialog, rationale
            }*/

            else -> {
                // Ask for microphone permission
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }


    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                makeCall()
            } else {
                // User declined permission :(
            }
        }
}