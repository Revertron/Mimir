package com.revertron.mimir

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.net.MSG_TYPE_REACTION
import com.revertron.mimir.net.PeerStatus
import com.revertron.mimir.storage.SqlStorage
import com.revertron.mimir.ui.Contact
import com.revertron.mimir.ui.MessageAdapter
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import java.io.File
import java.lang.Thread.sleep


class ChatActivity : BaseChatActivity() {

    companion object {
        const val TAG = "ChatActivity"
    }

    private lateinit var contact: Contact
    private var isSavedMessages = false

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

    private val audioPlaybackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action == AudioPlaybackService.ACTION_PLAYBACK_STATE_CHANGED) {
                val isPlaying = intent.getBooleanExtra(AudioPlaybackService.EXTRA_IS_PLAYING, false)
                val messageId = intent.getLongExtra(AudioPlaybackService.EXTRA_CURRENT_MESSAGE_ID, -1)
                adapter.updateAudioPlaybackState(isPlaying, messageId)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Check if this is saved messages
        isSavedMessages = intent.getBooleanExtra("savedMessages", false)

        // Extract contact info before calling super.onCreate()
        val pubkey: ByteArray
        val id: Long
        val name = intent.getStringExtra("name").apply { if (this == null) finish() }!!

        if (isSavedMessages) {
            // For saved messages, use special ID and current user's pubkey
            id = SqlStorage.SAVED_MESSAGES_CONTACT_ID
            val accountInfo = getStorage().getAccountInfo(1, 0L)
            pubkey = (accountInfo!!.keyPair.public as Ed25519PublicKeyParameters).encoded
        } else {
            // Normal contact flow
            pubkey = intent.getByteArrayExtra("pubkey").apply { if (this == null) finish() }!!
            id = getStorage().getContactId(pubkey)
        }

        val avatarPic = if (isSavedMessages) {
            ContextCompat.getDrawable(this, R.drawable.ic_saved_messages)
        } else {
            getStorage().getContactAvatar(id)
        }
        contact = Contact(id, pubkey, name, null, 0, avatarPic)

        super.onCreate(savedInstanceState)

        // Set title and avatar
        setToolbarTitle(contact.name)
        setupAvatar(contact.avatar)

        // Hide online indicator for saved messages
        if (isSavedMessages) {
            val statusImage = findViewById<AppCompatImageView>(R.id.status_image)
            statusImage?.visibility = View.GONE
        }

        // Handle shared image if any
        val image = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (image != null) {
            getImageFromUri(image)
        }

        // Setup message list
        setupMessageList()

        // Setup empty view for saved messages
        if (isSavedMessages) {
            setupEmptyView()
        }

        // Setup broadcast receivers
        setupBroadcastReceivers()

        // Skip connection for saved messages
        if (!isSavedMessages) {
            // Show initial connection state
            showOnlineState(PeerStatus.Connecting)
            fetchStatus(this, contact.pubkey)

            // Connect to peer
            Thread {
                sleep(500)
                connect(this@ChatActivity, contact.pubkey)
            }.start()
        }

        // Handle forward mode if present
        if (intent.getBooleanExtra("FORWARD_MODE", false)) {
            handleForwardMode()
        }
    }

    // BaseChatActivity abstract method implementations

    override fun getLayoutResId(): Int = R.layout.activity_chat

    override fun getChatId(): Long {
        return if (isSavedMessages) {
            SqlStorage.SAVED_MESSAGES_CONTACT_ID
        } else {
            contact.id
        }
    }

    override fun getChatName(): String = contact.name

    override fun isGroupChat(): Boolean = false

    override fun getAvatarColorSeed(): ByteArray = contact.pubkey

    override fun createMessageAdapter(fontSize: Int): MessageAdapter {
        return MessageAdapter(
            getStorage(),
            contact.id,
            groupChat = false,
            contact.name,
            this,
            onClickOnReply(),
            onClickOnPicture(),
            fontSize,
            onAvatarClick = null,
            onReactionsMarkedSeen = { updateReactionFabVisibility() }
        )
    }

    override fun getFirstUnreadMessageId(): Long? {
        return getStorage().getFirstUnreadMessageId(contact.id)
    }

    override fun deleteMessageByIdOrGuid(messageId: Long, guid: Long) {
        val attachmentFileName = getStorage().deleteMessage(messageId)
        // Delete attachment files if present
        if (attachmentFileName != null) {
            File(File(filesDir, "files"), attachmentFileName).delete()
            File(File(cacheDir, "files"), attachmentFileName).delete()
        }
    }

    override fun getMessageForReply(messageId: Long): Pair<String, String>? {
        val message = getStorage().getMessage(messageId, true)
        return if (message != null) {
            Pair(contact.name, message.getText(this))
        } else {
            null
        }
    }

    override fun getMessageFromStorage(messageId: Long): SqlStorage.Message? {
        return getStorage().getMessage(messageId, byGuid = false)
    }

    override fun handleReaction(targetGuid: Long, emoji: String, currentEmoji: String?) {
        // Toggle behavior: if clicking the same emoji, remove it
        val finalEmoji = if (emoji == currentEmoji) "" else emoji

        // Create reaction message data as JSON
        val reactionData = org.json.JSONObject().apply {
            put("emoji", finalEmoji)
            put("replyTo", targetGuid)
        }.toString()

        // Generate GUID for the reaction message
        val sendTime = getUtcTime()
        val guid = getStorage().generateGuid(sendTime, reactionData.toByteArray())

        // Store reaction locally (type = 10)
        getStorage().addMessage(
            contact = contact.pubkey,
            guid = guid,
            replyTo = targetGuid,
            incoming = false,
            delivered = false,
            sendTime = sendTime,
            editTime = 0L,
            type = 10,
            message = reactionData.toByteArray()
        )

        // Send reaction to peer via ConnectionService
        val intent = Intent(this, ConnectionService::class.java)
        intent.putExtra("command", "send")
        intent.putExtra("pubkey", contact.pubkey)
        intent.putExtra("replyTo", targetGuid)
        intent.putExtra("type", 10)
        intent.putExtra("message", reactionData)
        startService(intent)

        // Refresh the message list to show updated reactions
        adapter.notifyDataSetChanged()
    }

    override fun getUserCurrentReaction(targetGuid: Long): String? {
        return getStorage().getUserReactionForMessage(contact.id, targetGuid)
    }

    override fun getUnseenReactionsCount(): Int {
        return getStorage().getUnseenReactionsCount(contact.id)
    }

    override fun scrollToFirstUnseenReaction(): Boolean {
        val reaction = getStorage().getFirstUnseenReaction(contact.id) ?: return false
        val (reactionGuid, targetMessageGuid) = reaction

        // Find position of the target message
        val position = adapter.getMessageGuidPosition(targetMessageGuid)
        if (position >= 0) {
            recyclerView.smoothScrollToPosition(position)
        }

        // Mark the reaction as seen
        getStorage().markReactionAsSeen(contact.id, reactionGuid)

        return true
    }

    override fun sendMessage(text: String, replyTo: Long) {
        val intent = Intent(this, ConnectionService::class.java)
        intent.putExtra("command", "send")
        intent.putExtra("pubkey", contact.pubkey)
        intent.putExtra("replyTo", replyTo)
        if (attachmentJson != null) {
            intent.putExtra("type", attachmentType) // 1 = image, 3 = file
            attachmentJson!!.put("text", text)
            intent.putExtra("message", attachmentJson.toString())
            clearAttachment()
        } else {
            intent.putExtra("message", text)
        }
        startService(intent)
    }

    override fun setupBroadcastReceivers() {
        LocalBroadcastManager.getInstance(this).registerReceiver(
            peerStatusReceiver,
            IntentFilter("ACTION_PEER_STATUS")
        )
        LocalBroadcastManager.getInstance(this).registerReceiver(
            audioPlaybackReceiver,
            IntentFilter(AudioPlaybackService.ACTION_PLAYBACK_STATE_CHANGED)
        )
    }

    override fun onToolbarClick() {
        val intent = Intent(this, ContactActivity::class.java)
        intent.putExtra("pubkey", contact.pubkey)
        intent.putExtra("name", contact.name)
        startActivity(intent, animFromRight.toBundle())
    }

    // Menu handling

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.menu_contact, menu)
        if (isSavedMessages) {
            menu.findItem(R.id.contact_call)?.isVisible = false
            menu.findItem(R.id.mute_contact)?.isVisible = false
            menu.findItem(R.id.block_contact)?.isVisible = false
            menu.findItem(R.id.remove_contact)?.isVisible = false
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)
        if (!isSavedMessages) {
            val isMuted = getStorage().isContactMuted(contact.id)
            menu.findItem(R.id.mute_contact)?.title = if (isMuted) {
                getString(R.string.unmute)
            } else {
                getString(R.string.mute)
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.contact_call -> {
                checkAndRequestAudioPermission()
                return true
            }
            R.id.clear_history -> {
                showClearHistoryConfirmDialog()
                return true
            }
            R.id.mute_contact -> {
                toggleMuteContact()
                return true
            }
            else -> {
                if (item.itemId != android.R.id.home) {
                    Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
                }
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        Log.i(TAG, "Clicked on ${item?.itemId}")
        when (item?.itemId) {
            R.id.contact_call -> {
                checkAndRequestAudioPermission()
            }
            R.id.clear_history -> {
                showClearHistoryConfirmDialog()
            }
            R.id.mute_contact -> {
                toggleMuteContact()
            }
            else -> {
                Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    private fun toggleMuteContact() {
        val isMuted = getStorage().isContactMuted(contact.id)
        val newMutedStatus = !isMuted
        getStorage().setContactMuted(contact.id, newMutedStatus)
        val message = if (newMutedStatus) {
            getString(R.string.mute) + ": " + contact.name
        } else {
            getString(R.string.unmute) + ": " + contact.name
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        invalidateOptionsMenu()
    }

    // Audio call functionality

    private fun checkAndRequestAudioPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                makeCall()
            }
            else -> {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                makeCall()
            }
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
                val attachmentFiles = getStorage().clearContactHistory(contact.id)

                // 2. Delete attachment files
                val filesDir = File(filesDir, "files")
                val cacheDir = File(cacheDir, "files")
                for (fileName in attachmentFiles) {
                    File(filesDir, fileName).delete()
                    File(cacheDir, fileName).delete()
                }

                Log.i(TAG, "Cleared history: deleted ${attachmentFiles.size} attachment files")

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
                e.printStackTrace()
                Log.e(TAG, "Error clearing history", e)
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

    // Empty view handling

    private fun setupEmptyView() {
        updateEmptyView()

        // Register observer to update empty view when adapter data changes
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                updateEmptyView()
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                updateEmptyView()
            }

            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                updateEmptyView()
            }
        })
    }

    private fun updateEmptyView() {
        val emptyView = findViewById<View>(R.id.empty_view)
        val messagesCount = adapter.itemCount

        // Keep RecyclerView always visible for proper keyboard handling
        // Just overlay the empty view on top when needed
        if (messagesCount == 0) {
            emptyView.visibility = View.VISIBLE
        } else {
            emptyView.visibility = View.GONE
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

    // Peer status display

    private fun showOnlineState(status: PeerStatus) {
        val imageView = findViewById<AppCompatImageView>(R.id.status_image)
        when (status) {
            PeerStatus.NotConnected -> imageView.setImageResource(R.drawable.status_badge_red)
            PeerStatus.Connecting -> imageView.setImageResource(R.drawable.status_badge_yellow)
            PeerStatus.Connected -> imageView.setImageResource(R.drawable.status_badge_green)
            PeerStatus.ErrorConnecting -> imageView.setImageResource(R.drawable.status_badge_red)
        }
    }

    // StorageListener implementation

    override fun onMessageSent(id: Long, contactId: Long, type: Int, replyTo: Long) {
        runOnUiThread {
            Log.i(TAG, "Message $id sent to $contactId")
            if (contact.id == contactId) {
                if (type == MSG_TYPE_REACTION) {
                    adapter.notifyMessageChanged(replyTo)
                    return@runOnUiThread
                }
                val isAtEnd = recyclerView.isAtEnd()
                adapter.addMessageId(id, false)
                if (isAtEnd) {
                    recyclerView.scrollToEnd()
                }
            }
        }
    }

    override fun onMessageDelivered(id: Long, delivered: Boolean) {
        Log.i(TAG, "Message $id delivered = $delivered")
        runOnUiThread {
            adapter.setMessageDelivered(id, delivered)
            startShortSound(R.raw.message_sent)
        }
    }

    override fun onMessageReceived(id: Long, contactId: Long, type: Int, replyTo: Long): Boolean {
        Log.i(TAG, "Message $id from $contactId")
        if (contact.id == contactId) {
            runOnUiThread {
                if (type == MSG_TYPE_REACTION) {
                    adapter.notifyMessageChanged(replyTo)
                    return@runOnUiThread
                }
                Log.i(TAG, "Adding message")
                val isAtEnd = recyclerView.isAtEnd()
                adapter.addMessageId(id, true)
                if (isChatVisible) {
                    if (isAtEnd) {
                        recyclerView.scrollToEnd()
                    } else {
                        startShortSound(R.raw.message_more)
                    }
                }
            }
            return isChatVisible
        }
        return false
    }

    // Lifecycle

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(peerStatusReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(audioPlaybackReceiver)
        super.onDestroy()
    }
}