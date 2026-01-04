package com.revertron.mimir

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.storage.SqlStorage
import com.revertron.mimir.storage.StorageListener
import com.revertron.mimir.ui.ChatListItem
import com.revertron.mimir.ui.ContactsAdapter
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex

/**
 * Activity for selecting a chat destination when forwarding messages.
 * Displays contacts, group chats, and saved messages.
 */
class ForwardSelectorActivity : BaseActivity(), View.OnClickListener, StorageListener {

    companion object {
        const val TAG = "ForwardSelectorActivity"

        // Result extras
        const val RESULT_CHAT_TYPE = "RESULT_CHAT_TYPE"
        const val RESULT_CONTACT_ID = "RESULT_CONTACT_ID"
        const val RESULT_PUBKEY = "RESULT_PUBKEY"
        const val RESULT_NAME = "RESULT_NAME"
        const val RESULT_CHAT_ID = "RESULT_CHAT_ID"
        const val RESULT_DESCRIPTION = "RESULT_DESCRIPTION"
        const val RESULT_IS_OWNER = "RESULT_IS_OWNER"
        const val RESULT_MEDIATOR_ADDRESS = "RESULT_MEDIATOR_ADDRESS"

        // Chat types
        const val CHAT_TYPE_SAVED = "SAVED"
        const val CHAT_TYPE_CONTACT = "CONTACT"
        const val CHAT_TYPE_GROUP = "GROUP"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_content)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.select_chat_to_forward)

        val recycler = findViewById<RecyclerView>(R.id.contacts_list)
        recycler.addItemDecoration(DividerItemDecoration(baseContext, RecyclerView.VERTICAL))
    }

    override fun onResume() {
        super.onResume()
        val recycler = findViewById<RecyclerView>(R.id.contacts_list)
        if (recycler.adapter == null) {
            val chatItems = getChatList()
            val adapter = ContactsAdapter(chatItems, this, null)
            recycler.adapter = adapter
            recycler.layoutManager = LinearLayoutManager(this)
        } else {
            refreshContacts()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
            }
            else -> {
                Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onClick(view: View) {
        when (val item = view.tag as ChatListItem) {
            is ChatListItem.SavedMessagesItem -> {
                Log.i(TAG, "Selected Saved Messages")
                returnSavedMessages()
            }
            is ChatListItem.ContactItem -> {
                val addr = Hex.toHexString(item.pubkey)
                Log.i(TAG, "Selected contact $addr")
                returnContact(item)
            }
            is ChatListItem.GroupChatItem -> {
                Log.i(TAG, "Selected group chat ${item.chatId}")
                returnGroupChat(item)
            }
        }
    }

    private fun returnSavedMessages() {
        val resultIntent = Intent().apply {
            putExtra(RESULT_CHAT_TYPE, CHAT_TYPE_SAVED)

            // Copy forward message data from incoming intent
            intent.getLongExtra("FORWARD_MESSAGE_ID", 0).let { if (it != 0L) putExtra("FORWARD_MESSAGE_ID", it) }
            intent.getLongExtra("FORWARD_MESSAGE_GUID", 0).let { if (it != 0L) putExtra("FORWARD_MESSAGE_GUID", it) }
            intent.getIntExtra("FORWARD_MESSAGE_TYPE", 0).let { putExtra("FORWARD_MESSAGE_TYPE", it) }
            intent.getStringExtra("FORWARD_MESSAGE_TEXT")?.let { putExtra("FORWARD_MESSAGE_TEXT", it) }
            intent.getStringExtra("FORWARD_MESSAGE_JSON")?.let { putExtra("FORWARD_MESSAGE_JSON", it) }
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun returnContact(item: ChatListItem.ContactItem) {
        val resultIntent = Intent().apply {
            putExtra(RESULT_CHAT_TYPE, CHAT_TYPE_CONTACT)
            putExtra(RESULT_CONTACT_ID, item.id)
            putExtra(RESULT_PUBKEY, item.pubkey)
            putExtra(RESULT_NAME, item.name)

            // Copy forward message data from incoming intent
            intent.getLongExtra("FORWARD_MESSAGE_ID", 0).let { if (it != 0L) putExtra("FORWARD_MESSAGE_ID", it) }
            intent.getLongExtra("FORWARD_MESSAGE_GUID", 0).let { if (it != 0L) putExtra("FORWARD_MESSAGE_GUID", it) }
            intent.getIntExtra("FORWARD_MESSAGE_TYPE", 0).let { putExtra("FORWARD_MESSAGE_TYPE", it) }
            intent.getStringExtra("FORWARD_MESSAGE_TEXT")?.let { putExtra("FORWARD_MESSAGE_TEXT", it) }
            intent.getStringExtra("FORWARD_MESSAGE_JSON")?.let { putExtra("FORWARD_MESSAGE_JSON", it) }
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun returnGroupChat(item: ChatListItem.GroupChatItem) {
        val resultIntent = Intent().apply {
            putExtra(RESULT_CHAT_TYPE, CHAT_TYPE_GROUP)
            putExtra(RESULT_CHAT_ID, item.chatId)
            putExtra(RESULT_NAME, item.name)
            putExtra(RESULT_DESCRIPTION, item.description)
            putExtra(RESULT_IS_OWNER, item.isOwner)
            putExtra(RESULT_MEDIATOR_ADDRESS, item.mediatorAddress)

            // Copy forward message data from incoming intent
            intent.getLongExtra("FORWARD_MESSAGE_ID", 0).let { if (it != 0L) putExtra("FORWARD_MESSAGE_ID", it) }
            intent.getLongExtra("FORWARD_MESSAGE_GUID", 0).let { if (it != 0L) putExtra("FORWARD_MESSAGE_GUID", it) }
            intent.getIntExtra("FORWARD_MESSAGE_TYPE", 0).let { putExtra("FORWARD_MESSAGE_TYPE", it) }
            intent.getStringExtra("FORWARD_MESSAGE_TEXT")?.let { putExtra("FORWARD_MESSAGE_TEXT", it) }
            intent.getStringExtra("FORWARD_MESSAGE_JSON")?.let { putExtra("FORWARD_MESSAGE_JSON", it) }
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun getChatList(): List<ChatListItem> {
        val storage = (application as App).storage
        val contacts = storage.getContactList()
        val groupChats = storage.getGroupChatList()

        val chatItems = mutableListOf<ChatListItem>()

        // Convert contacts to ChatListItems
        chatItems.addAll(contacts.map { contact ->
            ChatListItem.ContactItem(
                id = contact.id,
                pubkey = contact.pubkey,
                name = contact.name,
                lastMessage = contact.lastMessage,
                unreadCount = contact.unread,
                avatar = contact.avatar
            )
        })

        // Convert group chats to ChatListItems
        val accountInfo = storage.getAccountInfo(1, 0L)
        val myPubKey = (accountInfo!!.keyPair.public as Ed25519PublicKeyParameters).encoded
        chatItems.addAll(groupChats.map { groupChat ->
            val avatar = storage.getGroupChatAvatar(groupChat.chatId, 48, 6)
            val isOwner = myPubKey?.contentEquals(groupChat.ownerPubkey) ?: false
            val lastMessage = storage.getLastGroupMessage(groupChat.chatId)

            ChatListItem.GroupChatItem(
                id = groupChat.chatId,
                chatId = groupChat.chatId,
                name = groupChat.name,
                description = groupChat.description ?: "",
                mediatorAddress = groupChat.mediatorPubkey,
                memberCount = storage.getGroupChatMembersCount(groupChat.chatId),
                isOwner = isOwner,
                avatar = avatar,
                lastMessageText = lastMessage?.getText(this),
                lastMessageTime = groupChat.lastMessageTime,
                unreadCount = groupChat.unreadCount
            )
        })

        // Sort by last message time (most recent first)
        val sortedItems = chatItems.sortedByDescending { it.lastMessageTime }.toMutableList()

        // Add Saved Messages at the top (after sorting)
        val savedMessagesIcon = ContextCompat.getDrawable(this, R.drawable.ic_saved_messages)
        val lastSavedMessage = storage.getLastSavedMessage()

        // Show tip if there are no saved messages
        val messageText = lastSavedMessage?.getText(this) ?: getString(R.string.saved_messages_tip)

        sortedItems.add(0, ChatListItem.SavedMessagesItem(
            name = getString(R.string.saved_messages),
            avatar = savedMessagesIcon,
            lastMessageText = messageText,
            lastMessageTime = lastSavedMessage?.time ?: 0
        ))

        return sortedItems
    }

    private fun refreshContacts() {
        val chatItems = getChatList()
        val recycler = findViewById<RecyclerView>(R.id.contacts_list)
        val adapter = recycler.adapter as ContactsAdapter
        adapter.setContacts(chatItems)
    }
}
