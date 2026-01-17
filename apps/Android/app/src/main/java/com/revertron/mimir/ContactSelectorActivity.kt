package com.revertron.mimir

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.storage.StorageListener
import com.revertron.mimir.ui.ChatListItem
import com.revertron.mimir.ui.ContactsAdapter
import org.bouncycastle.util.encoders.Hex

/**
 * Reusable activity for selecting a contact from the user's contact list.
 *
 * Usage:
 * ```
 * val intent = Intent(this, ContactSelectorActivity::class.java).apply {
 *     putExtra(EXTRA_TITLE, "Select Contact")
 *     putExtra(EXTRA_FILTER_PUBKEYS, arrayOf(...)) // optional: exclude specific contacts
 * }
 * startActivityForResult(intent, REQUEST_SELECT_CONTACT)
 *
 * // In onActivityResult:
 * if (resultCode == RESULT_OK && data != null) {
 *     val pubkey = data.getByteArrayExtra(RESULT_PUBKEY)
 *     val name = data.getStringExtra(RESULT_NAME)
 * }
 * ```
 */
class ContactSelectorActivity : BaseActivity(), View.OnClickListener, StorageListener {

    companion object {
        const val TAG = "ContactSelectorActivity"

        // Intent extras
        const val EXTRA_TITLE = "title"
        const val EXTRA_FILTER_PUBKEYS = "filter_pubkeys" // ByteArray array to exclude

        // Result extras
        const val RESULT_PUBKEY = "pubkey"
        const val RESULT_NAME = "name"
        const val RESULT_CONTACT_ID = "contact_id"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ContactsAdapter
    private var filterPubkeys: Set<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_content)

        // Get title from intent or use default
        val title = intent.getStringExtra(EXTRA_TITLE)
            ?: getString(R.string.select_contact_to_share)

        // Setup toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = title

        // Get filter list if provided
        @Suppress("DEPRECATION")
        val filterArray = intent.getSerializableExtra(EXTRA_FILTER_PUBKEYS) as? Array<ByteArray>
        filterPubkeys = filterArray?.map { org.bouncycastle.util.encoders.Hex.toHexString(it) }?.toSet()

        // Setup RecyclerView
        recyclerView = findViewById(R.id.contacts_list)
        recyclerView.addItemDecoration(DividerItemDecoration(baseContext, RecyclerView.VERTICAL))
        recyclerView.layoutManager = LinearLayoutManager(this)

        loadContacts()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            refreshContacts()
        }
    }

    private fun loadContacts() {
        val allContacts = (application as App).storage.getContactList()

        // Filter out contacts if filter list provided
        val contacts = if (filterPubkeys != null) {
            allContacts.filter { contact ->
                val pubkeyHex = Hex.toHexString(contact.pubkey)
                !filterPubkeys!!.contains(pubkeyHex)
            }
        } else {
            allContacts
        }

        // Convert to ChatListItem
        val chatItems = contacts.map { contact ->
            ChatListItem.ContactItem(
                id = contact.id,
                pubkey = contact.pubkey,
                name = contact.name,
                lastMessage = contact.lastMessage,
                unreadCount = contact.unread,
                avatar = contact.avatar
            )
        }

        adapter = ContactsAdapter(chatItems, this, null)
        recyclerView.adapter = adapter
    }

    private fun refreshContacts() {
        val allContacts = (application as App).storage.getContactList()

        // Filter out contacts if filter list provided
        val contacts = if (filterPubkeys != null) {
            allContacts.filter { contact ->
                val pubkeyHex = org.bouncycastle.util.encoders.Hex.toHexString(contact.pubkey)
                !filterPubkeys!!.contains(pubkeyHex)
            }
        } else {
            allContacts
        }

        // Convert to ChatListItem
        val chatItems = contacts.map { contact ->
            ChatListItem.ContactItem(
                id = contact.id,
                pubkey = contact.pubkey,
                name = contact.name,
                lastMessage = contact.lastMessage,
                unreadCount = contact.unread,
                avatar = contact.avatar
            )
        }

        adapter.setContacts(chatItems)
    }

    override fun onClick(view: View) {
        if (view.tag is ChatListItem.ContactItem) {
            val contactItem = view.tag as ChatListItem.ContactItem

            // Return selected contact via result
            val resultIntent = Intent().apply {
                putExtra(RESULT_PUBKEY, contactItem.pubkey)
                putExtra(RESULT_NAME, contactItem.name)
                putExtra(RESULT_CONTACT_ID, contactItem.id)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // StorageListener methods - refresh on contact changes
    override fun onContactAdded(id: Long) {
        runOnUiThread { refreshContacts() }
    }

    override fun onContactRemoved(id: Long) {
        runOnUiThread { refreshContacts() }
    }

    override fun onContactChanged(id: Long) {
        runOnUiThread { refreshContacts() }
    }

    override fun onMessageSent(id: Long, contactId: Long, type: Int, replyTo: Long) {}
    override fun onMessageDelivered(id: Long, delivered: Boolean) {}
    override fun onMessageRead(id: Long, contactId: Long) {}
    override fun onMessageReceived(id: Long, contactId: Long, type: Int, replyTo: Long): Boolean = false
}