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
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.Menu
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.net.MediatorManager
import com.revertron.mimir.net.SystemMessage
import com.revertron.mimir.net.parseSystemMessage
import com.revertron.mimir.storage.StorageListener
import com.revertron.mimir.ui.GroupChat
import com.revertron.mimir.ui.MessageAdapter
import com.revertron.mimir.ui.SettingsData.KEY_IMAGES_FORMAT
import com.revertron.mimir.ui.SettingsData.KEY_IMAGES_QUALITY
import com.revertron.mimir.ui.SettingsData.KEY_MESSAGE_FONT_SIZE
import org.bouncycastle.util.encoders.Hex
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Group chat activity using ConnectionService for server-mediated group messaging.
 *
 * This activity manages:
 * - Connection to mediator server via ConnectionService
 * - Sending and receiving group messages via service intents
 * - Message persistence in local storage
 * - Real-time message push notifications via BroadcastReceiver
 * - Group chat UI and interactions
 *
 * Unlike the original design that used MediatorClient directly, this refactored version
 * follows the same pattern as NewChatActivity by delegating all mediator operations to
 * ConnectionService and receiving responses via LocalBroadcast.
 */
class GroupChatActivity : BaseActivity(), Toolbar.OnMenuItemClickListener, StorageListener, View.OnClickListener {

    companion object {
        const val TAG = "GroupChatActivity"
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_CHAT_NAME = "chat_name"
        const val EXTRA_CHAT_DESCRIPTION = "chat_description"
        const val EXTRA_IS_OWNER = "is_owner"
        const val EXTRA_MEDIATOR_ADDRESS = "mediator_address"

        private const val REQUEST_SELECT_CONTACT = 100
        private const val PICK_IMAGE_REQUEST_CODE = 123
        private const val TAKE_PHOTO_REQUEST_CODE = 124
    }

    private lateinit var groupChat: GroupChat
    private lateinit var replyPanel: LinearLayoutCompat
    private lateinit var replyName: AppCompatTextView
    private lateinit var replyText: AppCompatTextView
    private lateinit var attachmentPanel: ConstraintLayout
    private lateinit var attachmentPreview: AppCompatImageView
    private lateinit var attachmentMenu: LinearLayoutCompat
    private lateinit var adapter: MessageAdapter
    private lateinit var recyclerView: RecyclerView

    private var mediatorAddress: ByteArray? = null
    private lateinit var publicKey: ByteArray
    private var replyTo = 0L
    private var attachmentJson: JSONObject? = null
    private var currentPhotoUri: Uri? = null
    private var isVisible: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val mediatorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "ACTION_MEDIATOR_MESSAGE_SENT" -> {
                    val chatId = intent.getLongExtra("chat_id", 0)
                    val messageId = intent.getLongExtra("message_id", 0)
                    val guid = intent.getLongExtra("guid", 0L)

                    if (chatId == groupChat.chatId) {
                        Log.i(TAG, "Message sent successfully: msgId=$messageId, guid=$guid")
                        mainHandler.post {
                            // Message sent successfully, refresh UI
                            adapter.notifyDataSetChanged()
                            recyclerView.scrollToPosition(adapter.itemCount - 1)
                        }
                    }
                }
                "ACTION_MEDIATOR_LEFT_CHAT" -> {
                    val chatId = intent.getLongExtra("chat_id", 0)
                    if (chatId == groupChat.chatId) {
                        mainHandler.post {
                            Log.i(TAG, "Left chat $chatId")
                            finish()
                        }
                    }
                }
                "ACTION_MEDIATOR_ERROR" -> {
                    val operation = intent.getStringExtra("operation")
                    val error = intent.getStringExtra("error")

                    mainHandler.post {
                        val message = when (operation) {
                            "subscribe" -> getString(R.string.connection_failed)
                            "send" -> getString(R.string.message_send_failed)
                            "leave" -> getString(R.string.failed_to_leave_group)
                            "delete" -> getString(R.string.failed_to_delete_group)
                            else -> error ?: getString(R.string.operation_failed)
                        }
                        Toast.makeText(this@GroupChatActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_chat)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setOnMenuItemClickListener(this)
        toolbar.setOnClickListener {
            openGroupInfo()
        }

        // Extract group chat info from intent
        val chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0)
        val chatName = intent.getStringExtra(EXTRA_CHAT_NAME)
        val chatDescription = intent.getStringExtra(EXTRA_CHAT_DESCRIPTION) ?: ""
        val isOwner = intent.getBooleanExtra(EXTRA_IS_OWNER, false)
        mediatorAddress = intent.getByteArrayExtra(EXTRA_MEDIATOR_ADDRESS)
            ?: MediatorManager.getDefaultMediatorPubkey()

        val memberCount = getStorage().getGroupChatMembersCount(chatId)

        if (chatId == 0L || chatName == null || mediatorAddress == null) {
            Log.e(TAG, "Missing required extras: chat_id, chat_name, or mediator_address")
            finish()
            return
        }

        // Get sender pubkey
        App.app.mediatorManager?.apply {
            publicKey = getPublicKey()
        }

        // Load avatar from storage
        val avatar = getStorage().getGroupChatAvatar(chatId)
        groupChat = GroupChat(
            chatId = chatId,
            name = chatName,
            description = chatDescription,
            avatar = avatar,
            lastMessageText = null,
            lastMessageTime = 0L,
            unreadCount = 0,
            memberCount = memberCount,
            isOwner = isOwner
        )

        setupUI()
        setupMessageList()
        registerBroadcastReceivers()
    }

    private fun registerBroadcastReceivers() {
        val filter = IntentFilter().apply {
            addAction("ACTION_MEDIATOR_MESSAGE_SENT")
            addAction("ACTION_MEDIATOR_LEFT_CHAT")
            addAction("ACTION_MEDIATOR_ERROR")
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(mediatorReceiver, filter)
    }

    private fun setupUI() {
        // Setup title and subtitle
        findViewById<AppCompatTextView>(R.id.title).text = groupChat.name
        findViewById<AppCompatTextView>(R.id.subtitle).text =
            getString(R.string.member_count, groupChat.memberCount)

        supportActionBar?.title = ""
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Setup avatar
        val avatar = findViewById<AppCompatImageView>(R.id.avatar)
        if (groupChat.avatar != null) {
            avatar.clearColorFilter()
            avatar.setImageDrawable(groupChat.avatar)
        } else {
            avatar.setImageResource(R.drawable.button_rounded_white)
            // Use chat ID for color generation
            val avatarColor = getAvatarColor(groupChat.chatId.toString().toByteArray())
            avatar.setColorFilter(avatarColor, PorterDuff.Mode.MULTIPLY)
        }

        // Setup reply panel
        replyPanel = findViewById(R.id.reply_panel)
        replyPanel.visibility = View.GONE
        replyName = findViewById(R.id.reply_contact_name)
        replyText = findViewById(R.id.reply_text)
        findViewById<AppCompatImageView>(R.id.reply_close).setOnClickListener {
            replyPanel.visibility = View.GONE
            replyTo = 0L
        }

        // Setup message input
        val editText = findViewById<AppCompatEditText>(R.id.message_edit)
        val sendButton = findViewById<AppCompatImageButton>(R.id.send_button)
        sendButton.setOnClickListener {
            val text = editText.text.toString().trim()
            if (text.isNotEmpty() || attachmentJson != null) {
                editText.text?.clear()
                sendGroupMessage(text, replyTo)
                replyPanel.visibility = View.GONE
                replyText.text = ""
                replyTo = 0L
            }
        }

        // Setup attachment menu
        attachmentMenu = findViewById(R.id.attachment_menu)
        attachmentMenu.visibility = View.GONE

        // Setup attachment button
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
            Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
        }

        // Setup attachment panel
        attachmentPanel = findViewById(R.id.attachment)
        attachmentPanel.visibility = View.GONE
        attachmentPreview = findViewById(R.id.attachment_image)
        val attachmentCancel = findViewById<AppCompatImageView>(R.id.attachment_cancel)
        attachmentCancel.setOnClickListener {
            attachmentPreview.setImageDrawable(null)
            attachmentPanel.visibility = View.GONE
            attachmentJson?.getString("name")?.apply {
                deleteFileAndPreview(this@GroupChatActivity, this)
            }
            attachmentJson = null
        }
    }

    private fun setupMessageList() {
        // For group chats, pass the chat ID directly (not negated)
        // MessageAdapter will use this to look up messages from the messages_<chatId> table
        val chatId = groupChat.chatId.toLong()

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val fontSize = prefs.getInt(KEY_MESSAGE_FONT_SIZE, 15)

        adapter = MessageAdapter(
            getStorage(),
            chatId,
            groupChat = true, // Enable group-chat mode to show sender names
            groupChat.name,
            this,
            onClickOnReply(),
            onClickOnPicture(),
            fontSize,
            onClickOnAvatar()
        )

        recyclerView = findViewById(R.id.messages_list)
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }

        // Scroll to first unread message if any, otherwise scroll to end
        val firstUnreadId = getStorage().getFirstUnreadGroupMessageId(groupChat.chatId)
        if (firstUnreadId != null) {
            val position = adapter.getMessageIdPosition(firstUnreadId)
            if (position >= 0) {
                recyclerView.post {
                    recyclerView.scrollToPosition(position)
                }
            }
        }

        getStorage().listeners.add(this)
    }

    private fun sendGroupMessage(text: String, replyTo: Long) {
        Thread {
            try {
                val sendTime = System.currentTimeMillis()
                val guid = getStorage().generateGuid(sendTime, text.toByteArray())

                // Prepare message data based on type
                val messageType: Int
                val messageData: String

                if (attachmentJson != null) {
                    // Message with attachment - send only JSON metadata
                    messageType = 1 // 1 = media attachment
                    attachmentJson!!.put("text", text)
                    messageData = attachmentJson!!.toString()
                } else {
                    // Plain text message
                    messageType = 0 // 0 = text message
                    messageData = text
                }

                // Store message locally with pending status (msgId = null until confirmed)
                val localId = getStorage().addGroupMessage(
                    chatId = groupChat.chatId,
                    serverMsgId = null, // Will be updated when confirmed
                    guid = guid,
                    author = publicKey,
                    timestamp = sendTime,
                    type = messageType,
                    system = false,
                    data = messageData.toByteArray(),
                    replyTo = replyTo
                )

                Log.i(TAG, "Stored message locally with ID: $localId, replyTo = $replyTo")

                // Send message parameters to ConnectionService
                // ConnectionService will handle serialization, encryption, and transmission
                val intent = Intent(this, ConnectionService::class.java)
                intent.putExtra("command", "mediator_send")
                intent.putExtra("chat_id", groupChat.chatId)
                intent.putExtra("guid", guid)
                intent.putExtra("reply_to", replyTo)
                intent.putExtra("send_time", sendTime)
                intent.putExtra("type", messageType)
                intent.putExtra("message", messageData)  // Just the JSON string or text
                startService(intent)

                Log.i(TAG, "Sent message request to ConnectionService for chat ${groupChat.chatId}")

                // Clean up attachment UI
                if (attachmentJson != null) {
                    runOnUiThread {
                        attachmentPanel.visibility = View.GONE
                        attachmentPreview.setImageDrawable(null)
                    }
                    attachmentJson = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending group message", e)
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.failed_to_send_message), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // Menu handling

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.menu_group_chat, menu)

        // Hide owner-only options if not owner
        if (!groupChat.isOwner) {
            menu.findItem(R.id.add_member)?.isVisible = false
            menu.findItem(R.id.delete_group)?.isVisible = false
        } else {
            // Hide leave group option for owners (they must delete the group instead)
            menu.findItem(R.id.leave_group)?.isVisible = false
        }

        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)

        // Update mute/unmute menu item title based on current muted status
        val muteItem = menu.findItem(R.id.mute_group)
        val currentlyMuted = getStorage().getGroupChat(groupChat.chatId)?.muted ?: false

        muteItem?.title = if (currentlyMuted) {
            getString(R.string.unmute_group)
        } else {
            getString(R.string.mute_group)
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                // Use navigateUpFromSameTask to ensure MainActivity is shown
                onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Check if we should navigate up to parent activity
        // This handles the case where activity was launched from notification
        if (shouldNavigateUpToParent()) {
            val upIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("no_service", true)
            }
            startActivity(upIntent)
            finish()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /*
     * Determines if we need to manually navigate to parent activity.
     * Returns true if the activity has no parent in the back stack.
     */
    private fun shouldNavigateUpToParent(): Boolean {
        // Check if this activity was launched in a way that has no back stack
        // (e.g., from a notification)
        return isTaskRoot
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.add_member -> {
                openContactSelector()
                true
            }
            R.id.mute_group -> {
                val currentlyMuted = getStorage().getGroupChat(groupChat.chatId)?.muted ?: false
                val newMutedStatus = !currentlyMuted

                if (getStorage().setGroupChatMuted(groupChat.chatId, newMutedStatus)) {
                    val message = if (newMutedStatus) {
                        getString(R.string.mute_group)
                    } else {
                        getString(R.string.unmute_group)
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

                    // Update the menu item title
                    invalidateOptionsMenu()
                } else {
                    Toast.makeText(this, "Failed to update mute status", Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.clear_history -> {
                // TODO: Clear message history
                Toast.makeText(this, "Clear history - TODO", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.leave_group -> {
                leaveGroup()
                true
            }
            R.id.delete_group -> {
                deleteGroup()
                true
            }
            else -> false
        }
    }

    private fun leaveGroup() {
        // Check if user is the owner - owners cannot leave, they must delete the group instead
        if (groupChat.isOwner) {
            Toast.makeText(
                this,
                getString(R.string.owner_cannot_leave_group),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Show confirmation dialog
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        AlertDialog.Builder(wrapper)
            .setTitle(R.string.leave_group)
            .setMessage(R.string.confirm_leave_group)
            .setPositiveButton(R.string.leave) { _, _ ->
                // Send intent to ConnectionService to leave the chat
                val intent = Intent(this, ConnectionService::class.java)
                intent.putExtra("command", "mediator_leave")
                intent.putExtra("chat_id", groupChat.chatId)
                startService(intent)

                Log.i(TAG, "Sent leave chat request to ConnectionService for chat ${groupChat.chatId}")

                // The activity will be finished when ACTION_MEDIATOR_LEFT_CHAT broadcast is received
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteGroup() {
        // Show confirmation dialog
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        AlertDialog.Builder(wrapper)
            .setTitle(R.string.delete_group)
            .setMessage(R.string.confirm_delete_group)
            .setPositiveButton(R.string.menu_delete) { _, _ ->
                // Send intent to ConnectionService to delete the chat
                val intent = Intent(this, ConnectionService::class.java)
                intent.putExtra("command", "mediator_delete")
                intent.putExtra("chat_id", groupChat.chatId)
                startService(intent)

                Log.i(TAG, "Sent delete chat request to ConnectionService for chat ${groupChat.chatId}")

                // The activity will be finished when ACTION_MEDIATOR_LEFT_CHAT broadcast is received
                // (same broadcast as leaving, as both result in the chat being removed)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // StorageListener implementation

    override fun onGroupMessageReceived(chatId: Long, id: Long, contactId: Long): Boolean {
        if (chatId == groupChat.chatId) {
            runOnUiThread {
                val message = getStorage().getGroupMessage(chatId, id, false)
                if (message != null) {
                    adapter.addMessageId(id, message.incoming) // Add the message ID like broadcasts do
                    recyclerView.scrollToPosition(adapter.itemCount - 1)

                    // Check if this is a system message that affects member count
                    if (message.type == 1000 && message.data != null) {
                        val sysMsg = parseSystemMessage(message.data)
                        when (sysMsg) {
                            is SystemMessage.UserAdded,
                            is SystemMessage.UserLeft,
                            is SystemMessage.UserBanned -> {
                                // Member list changed - update member count
                                // (Member deletion from table is handled by MediatorManager)
                                updateMemberCount()
                            }
                            else -> {
                                // Other system messages don't affect member count
                            }
                        }
                    }
                }
            }
            return isVisible
        }
        return false
    }

    private fun updateMemberCount() {
        val memberCount = getStorage().getGroupChatMembersCount(groupChat.chatId)
        groupChat = groupChat.copy(memberCount = memberCount)
        findViewById<AppCompatTextView>(R.id.subtitle).text =
            getString(R.string.member_count, memberCount)
    }

    override fun onGroupChatChanged(chatId: Long): Boolean {
        return super.onGroupChatChanged(chatId)
    }

    // Invite member functionality

    private fun openContactSelector() {
        val intent = Intent(this, ContactSelectorActivity::class.java).apply {
            putExtra(ContactSelectorActivity.EXTRA_TITLE, getString(R.string.add_member))
            // TODO: Filter out contacts who are already members
        }
        startActivityForResult(intent, REQUEST_SELECT_CONTACT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_SELECT_CONTACT -> {
                if (resultCode == RESULT_OK && data != null) {
                    val pubkey = data.getByteArrayExtra(ContactSelectorActivity.RESULT_PUBKEY)
                    val name = data.getStringExtra(ContactSelectorActivity.RESULT_NAME)

                    if (pubkey != null) {
                        sendInvite(pubkey, name ?: "Unknown")
                    }
                }
            }
            PICK_IMAGE_REQUEST_CODE -> {
                if (resultCode == RESULT_OK && data != null && data.data != null) {
                    val selectedPictureUri = data.data!!
                    getImageFromUri(selectedPictureUri)
                } else {
                    Log.e(TAG, "Error getting picture")
                }
            }
            TAKE_PHOTO_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    currentPhotoUri?.let { uri ->
                        Log.i(TAG, "Photo captured: $uri")
                        getImageFromUri(uri)
                    } ?: run {
                        Log.e(TAG, "Error: Photo URI is null")
                    }
                }
            }
        }
    }

    private fun sendInvite(recipientPubkey: ByteArray, recipientName: String) {
        // Send intent to ConnectionService to send invite
        val intent = Intent(this, ConnectionService::class.java)
        intent.putExtra("command", "mediator_send_invite")
        intent.putExtra("chat_id", groupChat.chatId)
        intent.putExtra("recipient_pubkey", recipientPubkey)
        startService(intent)

        val pubkeyHex = Hex.toHexString(recipientPubkey).take(8)
        Log.i(TAG, "Sent invite request to ConnectionService for user $pubkeyHex")

        Toast.makeText(
            this,
            "Invite sent to $recipientName",
            Toast.LENGTH_SHORT
        ).show()
    }

    // Image attachment handling

    private fun selectAndSendPicture() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        startActivityForResult(intent, PICK_IMAGE_REQUEST_CODE)
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

    // Click handlers

    override fun onClick(view: View) {
        val popup = PopupMenu(this, view, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        popup.inflate(R.menu.menu_context_message)
        popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_copy -> handleCopy(view)
                R.id.menu_reply -> handleReply(view)
                R.id.menu_forward -> handleForward(view)
                R.id.menu_delete -> handleDelete(view)
                else -> false
            }
        }
        popup.show()
    }

    private fun onClickOnReply() = fun(it: View) {
        val id = it.tag as Long
        val position = adapter.getMessageIdPosition(id)
        if (position >= 0) {
            recyclerView.smoothScrollToPosition(position)
        }
    }

    private fun onClickOnPicture() = fun(it: View) {
        val uri = it.tag as Uri
        val intent = Intent(this, PictureActivity::class.java)
        intent.data = uri
        startActivity(intent)
    }

    private fun onClickOnAvatar() = fun(it: View) {
        val contactId = it.tag as? Long
        if (contactId != null) {
            // Get member info from storage
            val user = getStorage().getMemberInfo(contactId, groupChat.chatId, 48, 6)
            val pubKey = getStorage().getMemberPubkey(contactId, groupChat.chatId)

            if (pubKey != null) {
                // Open ContactActivity with member's info
                val intent = Intent(this, ContactActivity::class.java)
                intent.putExtra("pubkey", pubKey)
                intent.putExtra("name", user?.first ?: Hex.toHexString(pubKey).take(16))
                startActivity(intent, animFromRight.toBundle())
            }
        }
    }

    private fun handleReply(view: View): Boolean {
        val messageId = view.tag as Long
        val message = getStorage().getGroupMessage(groupChat.chatId, messageId) ?: return false

        // Get the author's name from the message
        val user = getStorage().getMemberInfo(message.contact, groupChat.chatId, 48, 6)
        val authorName = user?.first ?: getString(R.string.unknown_nickname)

        replyName.text = authorName // NOT groupChat.name!
        replyText.text = message.getText(this)
        replyPanel.visibility = View.VISIBLE
        replyTo = message.guid

        return false
    }

    private fun handleCopy(view: View): Boolean {
        val textview = view.findViewById<AppCompatTextView>(R.id.text)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Mimir message", textview.text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        return true
    }

    private fun handleForward(view: View): Boolean {
        // TODO: Implement forward functionality
        Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
        return false
    }

    private fun handleDelete(view: View): Boolean {
        showDeleteMessageConfirmDialog(view.tag as Long)
        return true
    }

    private fun showDeleteMessageConfirmDialog(messageId: Long) {
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        AlertDialog.Builder(wrapper)
            .setTitle(getString(R.string.delete_message_dialog_title))
            .setMessage(R.string.delete_message_dialog_text)
            .setIcon(R.drawable.ic_delete)
            .setPositiveButton(getString(R.string.menu_delete)) { _, _ ->
                getStorage().deleteGroupMessage(groupChat.chatId, messageId)
                adapter.deleteMessageId(messageId)
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.cancel()
            }
            .show()
    }

    // Group Info

    private fun openGroupInfo() {
        val intent = Intent(this, GroupInfoActivity::class.java).apply {
            putExtra(GroupInfoActivity.EXTRA_CHAT_ID, groupChat.chatId)
            putExtra(GroupInfoActivity.EXTRA_CHAT_NAME, groupChat.name)
            putExtra(GroupInfoActivity.EXTRA_CHAT_DESCRIPTION, groupChat.description)
            putExtra(GroupInfoActivity.EXTRA_IS_OWNER, groupChat.isOwner)
            putExtra(GroupInfoActivity.EXTRA_MEDIATOR_ADDRESS, mediatorAddress)
        }
        startActivity(intent)
    }

    // Lifecycle

    override fun onStart() {
        super.onStart()
        isVisible = true
    }

    override fun onResume() {
        super.onResume()
        // Refresh member cache in case avatars or member info was updated
        // (e.g., after returning from GroupInfoActivity)
        adapter.refreshMemberCache()
    }

    override fun onStop() {
        super.onStop()
        isVisible = false
    }

    override fun onDestroy() {
        super.onDestroy()
        getStorage().listeners.remove(this)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mediatorReceiver)
    }

    private fun toggleAttachmentMenu() {
        if (attachmentMenu.visibility == View.VISIBLE) {
            hideAttachmentMenu()
        } else {
            showAttachmentMenu()
        }
    }

    private fun showAttachmentMenu() {
        attachmentMenu.visibility = View.VISIBLE
        attachmentMenu.translationY = attachmentMenu.height.toFloat()
        attachmentMenu.animate()
            .translationY(0f)
            .setDuration(200)
            .start()
    }

    private fun hideAttachmentMenu() {
        attachmentMenu.animate()
            .translationY(attachmentMenu.height.toFloat())
            .setDuration(200)
            .withEndAction {
                attachmentMenu.visibility = View.GONE
            }
            .start()
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                takePhoto()
            } else {
                Toast.makeText(this, "Camera permission is required to take photos", Toast.LENGTH_SHORT).show()
            }
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
            Log.e(TAG, "Error creating image file", e)
            null
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.hold_still, R.anim.slide_out_right)
    }
}