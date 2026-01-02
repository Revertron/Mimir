package com.revertron.mimir

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.net.PeerStatus
import com.revertron.mimir.storage.SqlStorage
import com.revertron.mimir.ui.Contact
import com.revertron.mimir.ui.MessageAdapter
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
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
            fontSize
        )
    }

    override fun getFirstUnreadMessageId(): Long? {
        return getStorage().getFirstUnreadMessageId(contact.id)
    }

    override fun deleteMessageByIdOrGuid(messageId: Long, guid: Long) {
        getStorage().deleteMessage(messageId)
    }

    override fun getMessageForReply(messageId: Long): Pair<String, String>? {
        val message = getStorage().getMessage(messageId, true)
        return if (message != null) {
            Pair(contact.name, message.getText(this))
        } else {
            null
        }
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.contact_call -> {
                checkAndRequestAudioPermission()
                return true
            }
            R.id.clear_history -> {
                // TODO: Implement clear history
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
            else -> {
                Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
            }
        }
        return true
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

    override fun onMessageSent(id: Long, contactId: Long) {
        runOnUiThread {
            Log.i(TAG, "Message $id sent to $contactId")
            if (contact.id == contactId) {
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

    override fun onMessageReceived(id: Long, contactId: Long): Boolean {
        Log.i(TAG, "Message $id from $contactId")
        if (contact.id == contactId) {
            runOnUiThread {
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
        super.onDestroy()
    }
}