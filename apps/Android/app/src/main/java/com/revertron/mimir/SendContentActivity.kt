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
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.storage.StorageListener
import com.revertron.mimir.ui.Contact
import com.revertron.mimir.ui.ContactsAdapter
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
            val contacts = (application as App).storage.getContactList()
            val adapter = ContactsAdapter(contacts, this, null)
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
        if (view.tag != null) {
            val contact = view.tag as Contact
            val addr = Hex.toHexString(contact.pubkey)
            Log.i(TAG, "Clicked on $addr")
            val intent = Intent(this, ChatActivity::class.java)
            intent.putExtra("pubkey", contact.pubkey)
            intent.putExtra("name", contact.name)
            intent.putExtra(Intent.EXTRA_STREAM, sharedUri)
            startActivity(intent, animFromRight.toBundle())
            finish()
        }
    }

    private fun refreshContacts() {
        val contacts = (application as App).storage.getContactList()
        val recycler = findViewById<RecyclerView>(R.id.contacts_list)
        val adapter = recycler.adapter as ContactsAdapter
        adapter.setContacts(contacts)
    }
}