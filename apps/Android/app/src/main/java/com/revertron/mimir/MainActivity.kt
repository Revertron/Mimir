package com.revertron.mimir

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.appcompat.widget.*
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.net.CONNECTION_PORT
import com.revertron.mimir.storage.StorageListener
import com.revertron.mimir.ui.Contact
import com.revertron.mimir.ui.ContactsAdapter
import org.bouncycastle.util.encoders.Hex


class MainActivity : BaseActivity(), View.OnClickListener, View.OnLongClickListener, StorageListener {

    companion object {
        const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setHomeActionContentDescription(R.string.account)
        if (intent?.hasExtra("no_service") != true) {
            startService()
        }
        getStorage().listeners.add(this)
        val recycler = findViewById<RecyclerView>(R.id.contacts_list)
        recycler.addItemDecoration(DividerItemDecoration(baseContext, RecyclerView.VERTICAL))
    }

    override fun onResume() {
        super.onResume()
        val recycler = findViewById<RecyclerView>(R.id.contacts_list)
        if (recycler.adapter == null) {
            val contacts = (application as App).storage.getContactList()
            val adapter = ContactsAdapter(contacts, this, this)
            recycler.adapter = adapter
            recycler.layoutManager = LinearLayoutManager(this)
        } else {
            refreshContacts()
        }
    }

    override fun onDestroy() {
        getStorage().listeners.remove(this)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    @Suppress("NAME_SHADOWING")
    @SuppressLint("NotifyDataSetChanged")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.plus -> {
                showAddContactDialog()
                return true
            }
            android.R.id.home -> {
                val intent = Intent(this, AccountsActivity::class.java)
                startActivity(intent)
                overridePendingTransition(R.anim.slide_in_left, R.anim.hold_still)
            }
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                overridePendingTransition(R.anim.slide_in_right, R.anim.hold_still)
            }
            else -> {
                Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
            }
        }

        return super.onOptionsItemSelected(item)
    }

    private fun startService() {
        val intent = Intent(this, ConnectionService::class.java)
        intent.putExtra("command", "start")
        startService(intent)
    }

    override fun onClick(view: View) {
        if (view.tag != null) {
            val contact = view.tag as Contact
            Log.i(TAG, "Clicked on ${view.tag}")
            val intent = Intent(this, ChatActivity::class.java)
            intent.putExtra("pubkey", contact.pubkey)
            intent.putExtra("name", contact.name)
            startActivity(intent)
            overridePendingTransition(R.anim.slide_in_right, R.anim.hold_still)
        }
    }

    override fun onLongClick(v: View): Boolean {
        val contact = v.tag as Contact
        showContactPopupMenu(contact, v)
        return true
    }

    override fun onMessageReceived(id: Long, contactId: Long): Boolean {
        runOnUiThread {
            refreshContacts()
        }
        return false
    }

    @Suppress("NAME_SHADOWING")
    @SuppressLint("NotifyDataSetChanged")
    private fun showAddContactDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.add_contact_dialog, null)
        val name = view.findViewById<AppCompatEditText>(R.id.contact_name)
        val pubkey = view.findViewById<AppCompatEditText>(R.id.contact_pubkey)
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        val builder: AlertDialog.Builder = AlertDialog.Builder(wrapper)
        builder.setTitle(getString(R.string.add_contact))
        builder.setView(view)
        builder.setIcon(R.drawable.ic_contact_add_daynight)
        builder.setPositiveButton(getString(R.string.contact_add)) { _, _ ->
            val name = name.text.toString().trim()
            val pubKeyString = pubkey.text.toString().trim()
            if (!validPublicKey(pubKeyString)) {
                Toast.makeText(this@MainActivity, R.string.wrong_public_key, Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }
            val pubkey = Hex.decode(pubKeyString)
            if (name.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.empty_name, Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }
            (application as App).storage.addContact(pubkey, name)
            refreshContacts()
        }
        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
            dialog.cancel()
        }
        builder.show()
    }

    @Suppress("NAME_SHADOWING")
    private fun showAddAddressDialog(contact: Contact) {
        val view = LayoutInflater.from(this).inflate(R.layout.add_contact_ip_dialog, null)
        val pubkey = view.findViewById<AppCompatEditText>(R.id.contact_pubkey)
        pubkey.setText(Hex.toHexString(contact.pubkey))
        val address = view.findViewById<AppCompatEditText>(R.id.contact_address)
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        val builder: AlertDialog.Builder = AlertDialog.Builder(wrapper)
        builder.setTitle(getString(R.string.add_contact_address))
        builder.setView(view)
        builder.setIcon(R.drawable.ic_add_address)
        builder.setPositiveButton(getString(R.string.contact_add)) { _, _ ->
            val address = address.text.toString()
            // We add this kind of IPs for 10 days
            val expiration = getUtcTime() + 86400 * 10
            (application as App).storage.saveIp(contact.pubkey, address, CONNECTION_PORT, 0, 0, expiration)
        }
        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
            dialog.cancel()
        }
        builder.show()
    }

    @Suppress("NAME_SHADOWING")
    private fun showRenameContactDialog(contact: Contact) {
        val view = LayoutInflater.from(this).inflate(R.layout.rename_contact_dialog, null)
        val name = view.findViewById<AppCompatEditText>(R.id.contact_name)
        name.setText(contact.name)
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        val builder: AlertDialog.Builder = AlertDialog.Builder(wrapper)
        builder.setTitle(getString(R.string.rename_contact))
        builder.setView(view)
        builder.setIcon(R.drawable.ic_contact_rename)
        builder.setPositiveButton(getString(R.string.rename)) { _, _ ->
            val newName = name.text.toString()
            (application as App).storage.renameContact(contact.id, newName, true)
            refreshContacts()
        }
        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
            dialog.cancel()
        }
        builder.show()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun showContactPopupMenu(contact: Contact, v: View) {
        val popup = PopupMenu(this, v, Gravity.TOP or Gravity.END)
        popup.inflate(R.menu.menu_context_contact)
        popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.add_address -> {
                    showAddAddressDialog(contact)
                    true
                }
                R.id.rename -> {
                    showRenameContactDialog(contact)
                    true
                }
                R.id.copy_id -> {
                    val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Mimir contact ID", Hex.toHexString(contact.pubkey))
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(applicationContext,R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.remove_contact -> {
                    //TODO add confirmation dialog
                    getStorage().removeContactAndChat(contact.id)
                    refreshContacts()
                    true
                }
                else -> {
                    Log.w(ChatActivity.TAG, "Not implemented handler for menu item ${it.itemId}")
                    false
                }
            }
        }
        popup.show()
    }

    override fun onMessageDelivered(id: Long, delivered: Boolean) {
        runOnUiThread {
            super.onMessageDelivered(id, delivered)
            val recycler = findViewById<RecyclerView>(R.id.contacts_list)
            val adapter = recycler.adapter as ContactsAdapter
            adapter.setMessageDelivered(id)
        }
    }

    private fun refreshContacts() {
        val contacts = (application as App).storage.getContactList()
        val recycler = findViewById<RecyclerView>(R.id.contacts_list)
        val adapter = recycler.adapter as ContactsAdapter
        adapter.setContacts(contacts)
    }
}