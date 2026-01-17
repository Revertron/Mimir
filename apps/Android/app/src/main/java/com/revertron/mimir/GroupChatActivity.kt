package com.revertron.mimir

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.revertron.mimir.net.MSG_TYPE_REACTION
import com.revertron.mimir.net.MediatorManager
import com.revertron.mimir.net.SystemMessage
import com.revertron.mimir.net.parseSystemMessage
import com.revertron.mimir.ui.GroupChat
import com.revertron.mimir.ui.MessageAdapter
import org.bouncycastle.util.encoders.Hex
import java.io.File

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
class GroupChatActivity : BaseChatActivity() {

    companion object {
        const val TAG = "GroupChatActivity"
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_CHAT_NAME = "chat_name"
        const val EXTRA_CHAT_DESCRIPTION = "chat_description"
        const val EXTRA_IS_OWNER = "is_owner"
        const val EXTRA_MEDIATOR_ADDRESS = "mediator_address"

        private const val REQUEST_SELECT_CONTACT = 100
    }

    private lateinit var groupChat: GroupChat
    private var mediatorAddress: ByteArray? = null
    private lateinit var publicKey: ByteArray
    private val mainHandler = Handler(Looper.getMainLooper())

    private val mediatorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "ACTION_MEDIATOR_MESSAGE_SENT" -> {
                    val chatId = intent.getLongExtra("chat_id", 0)
                    val messageId = intent.getLongExtra("message_id", 0)
                    val guid = intent.getLongExtra("guid", 0L)
                    val type = intent.getIntExtra("type", 0)
                    val replyTo = intent.getLongExtra("replyTo", 0L)

                    if (chatId == groupChat.chatId) {
                        Log.i(TAG, "Message sent successfully: msgId=$messageId, guid=$guid")
                        mainHandler.post {
                            if (type == MSG_TYPE_REACTION) {
                                adapter.notifyMessageChanged(replyTo)
                                return@post
                            }
                            startShortSound(R.raw.message_sent)
                            // Message sent successfully, refresh UI
                            adapter.notifyDataSetChanged()
                            recyclerView.scrollToPosition(adapter.itemCount - 1)
                        }
                    }
                }
                "ACTION_MEDIATOR_CHAT_DELETED" -> {
                    val chatId = intent.getLongExtra("chat_id", 0)
                    if (chatId == groupChat.chatId) {
                        Log.i(TAG, "Chat $chatId deleted successfully, closing activity")
                        mainHandler.post {
                            finish()
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

    private val groupChatStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "ACTION_GROUP_CHAT_STATUS") {
                val chatId = intent.getLongExtra("chat_id", 0L)
                val statusName = intent.getStringExtra("status")

                if (chatId == groupChat.chatId && statusName != null) {
                    try {
                        val status = MediatorManager.GroupChatStatus.valueOf(statusName)
                        mainHandler.post {
                            showConnectionStatus(status)
                        }
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "Invalid status received: $statusName", e)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Extract group chat info before calling super.onCreate()
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

        super.onCreate(savedInstanceState)

        // Set title, subtitle, and avatar
        setToolbarTitle(groupChat.name)
        setToolbarSubtitle(getString(R.string.member_count, groupChat.memberCount, 0))
        setupAvatar(groupChat.avatar)

        // Setup message list
        setupMessageList()

        // Setup broadcast receivers
        setupBroadcastReceivers()

        // Initialize connection status badge (optimistic CONNECTING, will update when service responds)
        showConnectionStatus(MediatorManager.GroupChatStatus.CONNECTING)
        requestGroupChatStatus()

        // Handle shared media if any (similar to ChatActivity:61-64)
        val sharedUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (sharedUri != null) {
            handleSharedMedia(sharedUri)
        }

        // Handle forward mode if present
        if (intent.getBooleanExtra("FORWARD_MODE", false)) {
            handleForwardMode()
        }
    }

    // BaseChatActivity abstract method implementations

    override fun getLayoutResId(): Int = R.layout.activity_group_chat

    override fun getChatId(): Long = groupChat.chatId

    override fun getChatName(): String = groupChat.name

    override fun isGroupChat(): Boolean = true

    override fun getAvatarColorSeed(): ByteArray = groupChat.chatId.toString().toByteArray()

    override fun createMessageAdapter(fontSize: Int): MessageAdapter {
        return MessageAdapter(
            getStorage(),
            groupChat.chatId,
            groupChat = true, // Enable group-chat mode to show sender names
            groupChat.name,
            this,
            onClickOnReply(),
            onClickOnPicture(),
            fontSize,
            onClickOnAvatar()
        )
    }

    override fun getFirstUnreadMessageId(): Long? {
        return getStorage().getFirstUnreadGroupMessageId(groupChat.chatId)
    }

    override fun deleteMessageByIdOrGuid(messageId: Long, guid: Long) {
        // Send delete request to mediator in background thread
        Thread {
            try {
                val mediatorManager = App.app.mediatorManager
                if (mediatorManager == null) {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.mediator_not_connected), Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                // Get or create mediator client
                val mediatorClient = mediatorManager.getOrCreateClient()

                // Request deletion from mediator
                mediatorClient.deleteMessage(groupChat.chatId, guid)

                // Success - the delete message locally
                getStorage().deleteGroupMessageByGuid(groupChat.chatId, guid)
                Log.i(TAG, "Message deletion request sent successfully for guid $guid")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete message", e)
                if (e.toString().contains("message not found")) {
                    getStorage().deleteGroupMessageByGuid(groupChat.chatId, guid)
                } else {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.failed_to_delete_message), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    override fun getMessageForReply(messageId: Long): Pair<String, String>? {
        val message = getStorage().getGroupMessage(groupChat.chatId, messageId, true) ?: return null
        // Get the author's name from the message
        val user = getStorage().getMemberInfo(message.contact, groupChat.chatId, 48, 6)
        val authorName = user?.first ?: getString(R.string.unknown_nickname)
        return Pair(authorName, message.getText(this))
    }

    override fun getMessageFromStorage(messageId: Long): com.revertron.mimir.storage.SqlStorage.Message? {
        return getStorage().getGroupMessage(groupChat.chatId, messageId, byGuid = false)
    }

    override fun handleReaction(targetGuid: Long, emoji: String, currentEmoji: String?) {
        // Toggle behavior: if clicking the same emoji, remove it
        val finalEmoji = if (emoji == currentEmoji) "" else emoji

        // Create reaction message data as JSON
        val reactionData = org.json.JSONObject().apply {
            put("emoji", finalEmoji)
            put("replyTo", targetGuid)
        }.toString()

        Thread {
            try {
                val sendTime = System.currentTimeMillis()
                val guid = getStorage().generateGuid(sendTime, reactionData.toByteArray())

                // Store reaction locally (type = 10)
                val localId = getStorage().addGroupMessage(
                    chatId = groupChat.chatId,
                    serverMsgId = null,
                    guid = guid,
                    author = publicKey,
                    timestamp = sendTime,
                    type = 10,  // Reaction type
                    system = false,
                    data = reactionData.toByteArray(),
                    replyTo = targetGuid
                )

                Log.i(TAG, "Stored reaction locally with ID: $localId, targetGuid = $targetGuid")

                // Send reaction to mediator via ConnectionService
                val intent = Intent(this, ConnectionService::class.java)
                intent.putExtra("command", "mediator_send")
                intent.putExtra("chat_id", groupChat.chatId)
                intent.putExtra("guid", guid)
                intent.putExtra("reply_to", targetGuid)
                intent.putExtra("send_time", sendTime)
                intent.putExtra("type", 10)  // Reaction type
                intent.putExtra("message", reactionData)
                startService(intent)

                Log.i(TAG, "Sent reaction to ConnectionService for chat ${groupChat.chatId}")

                // Refresh the message list to show updated reactions
                runOnUiThread {
                    adapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending reaction", e)
            }
        }.start()
    }

    override fun getUserCurrentReaction(targetGuid: Long): String? {
        return getStorage().getUserReactionForGroupMessage(groupChat.chatId, targetGuid)
    }

    override fun sendMessage(text: String, replyTo: Long) {
        // Prepare message data based on type
        val messageType: Int
        val messageData: String

        if (attachmentJson != null) {
            // Message with attachment - send only JSON metadata
            messageType = attachmentType // 1 = image, 3 = file
            attachmentJson!!.put("text", text)
            messageData = attachmentJson!!.toString()
        } else {
            // Plain text message
            messageType = 0 // 0 = text message
            messageData = text
        }
        Thread {
            try {
                val sendTime = System.currentTimeMillis()
                val guid = getStorage().generateGuid(sendTime, messageData.toByteArray())

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
                        clearAttachment()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending group message", e)
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.failed_to_send_message), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    override fun setupBroadcastReceivers() {
        val filter = IntentFilter().apply {
            addAction("ACTION_MEDIATOR_MESSAGE_SENT")
            addAction("ACTION_MEDIATOR_CHAT_DELETED")
            addAction("ACTION_MEDIATOR_LEFT_CHAT")
            addAction("ACTION_MEDIATOR_ERROR")
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(mediatorReceiver, filter)

        // Register status receiver for connection badge updates
        val statusFilter = IntentFilter("ACTION_GROUP_CHAT_STATUS")
        LocalBroadcastManager.getInstance(this).registerReceiver(groupChatStatusReceiver, statusFilter)
    }

    override fun onToolbarClick() {
        openGroupInfo()
    }

    // Click handlers

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

    // Forward mode handling

    private fun handleForwardMode() {
        val messageText = intent.getStringExtra("FORWARD_MESSAGE_TEXT") ?: ""
        val messageType = intent.getIntExtra("FORWARD_MESSAGE_TYPE", 0)
        val messageJson = intent.getStringExtra("FORWARD_MESSAGE_JSON")

        // Show forwarded message in reply panel
        showReplyPanel(
            getString(R.string.forwarded_message),
            messageText,
            0L  // No actual GUID - this is a forward, not a reply
        )

        // Store forward data for sending
        when (messageType) {
            1 -> {
                // Image message - recreate attachment
                messageJson?.let { json ->
                    try {
                        attachmentJson = org.json.JSONObject(json)
                        attachmentType = messageType

                        // Verify file exists and show attachment UI
                        val filename = attachmentJson?.optString("name")
                        if (filename != null) {
                            val file = java.io.File(java.io.File(filesDir, "files"), filename)
                            if (file.exists()) {
                                val fileSize = attachmentJson?.optLong("size", 0) ?: 0
                                val preview = getImagePreview(this, filename, 320, 85)

                                attachmentPreview.setImageBitmap(preview)
                                attachmentPreview.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                                attachmentName.text = filename
                                attachmentSize.text = formatFileSize(fileSize)
                                attachmentPanel.visibility = android.view.View.VISIBLE
                            } else {
                                Log.e(TAG, "Forwarded file not found: $filename")
                                Toast.makeText(this, "Media file not found", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse forwarded media", e)
                    }
                }
            }
            3 -> {
                // File message - recreate attachment
                messageJson?.let { json ->
                    try {
                        attachmentJson = org.json.JSONObject(json)
                        attachmentType = messageType

                        // Verify file exists and show attachment UI
                        val filename = attachmentJson?.optString("name")
                        if (filename != null) {
                            val file = java.io.File(java.io.File(filesDir, "files"), filename)
                            if (file.exists()) {
                                val displayName = attachmentJson?.optString("originalName", filename) ?: filename
                                val fileSize = attachmentJson?.optLong("size", 0) ?: 0
                                val mimeType = attachmentJson?.optString("mimeType", "application/octet-stream") ?: "application/octet-stream"

                                attachmentPreview.setImageResource(getFileIconForMimeType(mimeType))
                                attachmentPreview.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                                attachmentName.text = displayName
                                attachmentSize.text = formatFileSize(fileSize)
                                attachmentPanel.visibility = android.view.View.VISIBLE
                            } else {
                                Log.e(TAG, "Forwarded file not found: $filename")
                                Toast.makeText(this, "Media file not found", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse forwarded media", e)
                    }
                }
            }
            else -> {
                // Text message - stored in reply panel already
            }
        }
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
                showClearHistoryConfirmDialog()
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

    // Clear history functionality

    private fun showClearHistoryConfirmDialog() {
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        val builder = AlertDialog.Builder(wrapper)
        builder.setTitle(getString(R.string.clear_history))
        builder.setMessage(getString(R.string.clear_history_confirm_text))
        builder.setIcon(R.drawable.ic_clean_chat_outline)
        builder.setPositiveButton(getString(R.string.clear)) { _, _ ->
            clearHistory()
        }
        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
            dialog.cancel()
        }
        builder.show()
    }

    private fun clearHistory() {
        Thread {
            try {
                // 1. Clear messages from database and get attachment files
                val attachmentFiles = getStorage().clearGroupChatHistory(groupChat.chatId)

                // 2. Delete attachment files
                val filesDir = File(filesDir, "files")
                val cacheDir = File(cacheDir, "files")
                for (fileName in attachmentFiles) {
                    File(filesDir, fileName).delete()
                    File(cacheDir, fileName).delete()
                }

                Log.i(TAG, "Cleared group history: deleted ${attachmentFiles.size} attachment files")

                // 3. Update UI on main thread
                runOnUiThread {
                    // Clear adapter
                    adapter.clearAllMessages()

                    // Show confirmation toast
                    Toast.makeText(
                        this,
                        getString(R.string.history_cleared),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing group history", e)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.error_clearing_history),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    // StorageListener implementation

    override fun onGroupMessageReceived(chatId: Long, id: Long, contactId: Long, type: Int, replyTo: Long): Boolean {
        if (chatId == groupChat.chatId) {
            runOnUiThread {
                val message = getStorage().getGroupMessage(chatId, id, false)
                if (message != null) {
                    if (type == MSG_TYPE_REACTION) {
                        adapter.notifyMessageChanged(replyTo)
                        return@runOnUiThread
                    }
                    val isAtEnd = recyclerView.isAtEnd()
                    adapter.addMessageId(id, message.incoming) // Add the message ID like broadcasts do
                    if (isAtEnd) {
                        recyclerView.scrollToEnd()
                    } else {
                        startShortSound(R.raw.message_more)
                    }

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
            return isChatVisible
        }
        return false
    }

    override fun onGroupMessageDeleted(chatId: Long, messageId: Long) {
        if (chatId == groupChat.chatId) {
            runOnUiThread {
                Log.i(TAG, "Message $messageId deleted in chat $chatId, updating adapter")
                adapter.deleteMessageId(messageId)
            }
        }
    }

    private fun updateMemberCount() {
        val members = getStorage().getGroupMembers(groupChat.chatId)
        val onlineCount = members.count { it.online }

        groupChat = groupChat.copy(memberCount = members.size)
        findViewById<AppCompatTextView>(R.id.subtitle).text =
            getString(R.string.member_count, members.size, onlineCount)
    }

    private fun refreshMemberStatus() {
        Thread {
            val mediatorClient = try {
                App.app.mediatorManager?.getOrCreateClient()
            } catch (e: Throwable) {
                Log.d(TAG, "Error getting client: $e")
                showConnectionStatus(MediatorManager.GroupChatStatus.DISCONNECTED)
                return@Thread
            }
            if (mediatorClient != null) {
                try {
                    val members = mediatorClient.getMembers(groupChat.chatId)
                    // Update database with permissions and online status
                    for (member in members) {
                        getStorage().updateGroupMemberStatus(groupChat.chatId, member.pubkey, member.permissions, member.online)
                    }
                    // Reload UI
                    runOnUiThread {
                        updateMemberCount()
                    }
                } catch (e: Exception) {
                    Log.e(GroupInfoActivity.Companion.TAG, "Failed to fetch member status", e)
                }
            }
        }.start()
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

    /**
     * Update the status badge on the group avatar based on connection status.
     * Red = disconnected, Yellow = connecting, Green = subscribed
     */
    private fun showConnectionStatus(status: MediatorManager.GroupChatStatus) {
        val imageView = findViewById<AppCompatImageView>(R.id.status_image)
        when (status) {
            MediatorManager.GroupChatStatus.DISCONNECTED -> {
                imageView.setImageResource(R.drawable.status_badge_red)
            }
            MediatorManager.GroupChatStatus.CONNECTING -> {
                imageView.setImageResource(R.drawable.status_badge_yellow)
            }
            MediatorManager.GroupChatStatus.SUBSCRIBED -> {
                imageView.setImageResource(R.drawable.status_badge_green)
            }
        }
    }

    /**
     * Request the current connection status from ConnectionService.
     * The service will respond with an ACTION_GROUP_CHAT_STATUS broadcast.
     */
    private fun requestGroupChatStatus() {
        val intent = Intent(this, ConnectionService::class.java).apply {
            putExtra("command", "group_chat_status")
            putExtra("chat_id", groupChat.chatId)
        }
        startService(intent)
    }

    private fun handleSharedMedia(uri: Uri) {
        try {
            val mimeType = contentResolver.getType(uri)
            when {
                mimeType?.startsWith("image/") == true -> {
                    getImageFromUri(uri)
                }
                else -> {
                    getFileFromUri(uri)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle shared media: ${e.message}")
            Toast.makeText(this, R.string.error_loading_file, Toast.LENGTH_SHORT).show()
        }
    }

    // Lifecycle

    override fun onResume() {
        super.onResume()
        // Refresh member cache in case avatars or member info was updated
        // (e.g., after returning from GroupInfoActivity)
        adapter.refreshMemberCache()
        refreshMemberStatus()
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mediatorReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(groupChatStatusReceiver)
        super.onDestroy()
    }
}