package com.revertron.mimir

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
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
import com.revertron.mimir.ui.Contact
import com.revertron.mimir.ui.ContactsAdapter
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex

/**
 * A small copy of the MainActivity to accept media and send it to ChatActivity with selected contact
 */
class SendContentActivity : BaseActivity(), View.OnClickListener, StorageListener {

    companion object {
        const val TAG = "SendContentActivity"
    }

    lateinit var sharedUri: Uri

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_content)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.select_contact_to_share)

        val recycler = findViewById<RecyclerView>(R.id.contacts_list)
        recycler.addItemDecoration(DividerItemDecoration(baseContext, RecyclerView.VERTICAL))

        processIntent()
    }

    private fun processIntent() {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    sharedUri = uri
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {}
            else -> finish()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIntent()
    }

    // For now it will not be used
    /*private fun handleMultipleImages(received: Intent) {
        val list = received.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)
        list?.forEach { parcelable ->
            (parcelable as? Uri)?.let { uri ->
                val bitmap = loadBitmap(uri)
                showImage(bitmap)
            }
        }
    }*/

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

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    @Suppress("NAME_SHADOWING")
    @SuppressLint("NotifyDataSetChanged")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                val intent = Intent(this, MainActivity::class.java)
                startActivity(intent, animFromLeft.toBundle())
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
                Log.i(TAG, "Clicked on Saved Messages")
                val intent = Intent(this, ChatActivity::class.java)
                intent.putExtra("contactId", SqlStorage.SAVED_MESSAGES_CONTACT_ID)
                intent.putExtra("name", item.name)
                intent.putExtra("savedMessages", true)
                intent.putExtra(Intent.EXTRA_STREAM, sharedUri)
                startActivity(intent, animFromRight.toBundle())
                finish()
            }
            is ChatListItem.ContactItem -> {
                val addr = Hex.toHexString(item.pubkey)
                Log.i(TAG, "Clicked on contact $addr")
                val intent = Intent(this, ChatActivity::class.java)
                intent.putExtra("pubkey", item.pubkey)
                intent.putExtra("name", item.name)
                intent.putExtra(Intent.EXTRA_STREAM, sharedUri)
                startActivity(intent, animFromRight.toBundle())
                finish()
            }
            is ChatListItem.GroupChatItem -> {
                Log.i(TAG, "Clicked on group chat ${item.chatId}")
                val intent = Intent(this, GroupChatActivity::class.java)
                intent.putExtra(GroupChatActivity.EXTRA_CHAT_ID, item.chatId)
                intent.putExtra(GroupChatActivity.EXTRA_CHAT_NAME, item.name)
                intent.putExtra(GroupChatActivity.EXTRA_CHAT_DESCRIPTION, item.description)
                intent.putExtra(GroupChatActivity.EXTRA_IS_OWNER, item.isOwner)
                intent.putExtra(GroupChatActivity.EXTRA_MEDIATOR_ADDRESS, item.mediatorAddress)
                intent.putExtra(Intent.EXTRA_STREAM, sharedUri)
                startActivity(intent, animFromRight.toBundle())
                finish()
            }
        }
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

        sortedItems.add(0, ChatListItem.SavedMessagesItem(
            name = getString(R.string.saved_messages),
            avatar = savedMessagesIcon,
            lastMessageText = lastSavedMessage?.getText(this),
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