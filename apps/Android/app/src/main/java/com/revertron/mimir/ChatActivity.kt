package com.revertron.mimir

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.storage.SqlStorage
import com.revertron.mimir.storage.StorageListener
import com.revertron.mimir.ui.Contact
import com.revertron.mimir.ui.MessageAdapter
import okio.blackholeSink
import org.bouncycastle.util.encoders.Hex


class ChatActivity : BaseActivity(), Toolbar.OnMenuItemClickListener, StorageListener, View.OnClickListener {

    companion object {
        const val TAG = "ChatActivity"
    }

    lateinit var contact: Contact

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)

        val pubkey = intent.getStringExtra("pubkey").apply { if (this == null) finish() }!!
        val name = intent.getStringExtra("name").apply { if (this == null) finish() }!!
        val id = getStorage().getContactId(pubkey)
        contact = Contact(id, pubkey, name, "", 0L, 0)

        supportActionBar?.title = contact.name
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setOnMenuItemClickListener(this)

        val editText = findViewById<AppCompatEditText>(R.id.message_edit)
        val sendButton = findViewById<AppCompatImageButton>(R.id.send_button)
        sendButton.setOnClickListener {
            val text: String = editText.text.toString()
            if (text.isNotEmpty()) {
                editText.text?.clear()
                sendText(contact.pubkey, text)
            }
        }
        val adapter = MessageAdapter(getStorage(), contact.id, multiChat = false, "Me", contact.name, this)
        val recycler = findViewById<RecyclerView>(R.id.messages_list)
        recycler.adapter = adapter
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        getStorage().listeners.add(this)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            overridePendingTransition(R.anim.hold_still, R.anim.slide_out_right)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        getStorage().listeners.remove(this)
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.hold_still, R.anim.slide_out_right)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        Log.i(TAG, "Clicked on ${item?.itemId}")
        return true
    }

    private fun sendText(pubkey: String, text: String) {
        val intent = Intent(this, ConnectionService::class.java)
        intent.putExtra("command", "send")
        intent.putExtra("pubkey", Hex.decode(pubkey))
        intent.putExtra("message", text)
        startService(intent)
    }

    override fun onMessageSent(id: Long, contactId: Long, message: String) {
        runOnUiThread {
            Log.i(TAG, "Message $id sent to $contactId: $message")
            if (contact.id == contactId) {
                Log.i(TAG, "Processing message $message")
                val recycler = findViewById<RecyclerView>(R.id.messages_list)
                val adapter = recycler.adapter as MessageAdapter
                adapter.addMessageId(id)
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
        }
    }

    override fun onMessageReceived(id: Long, contactId: Long, message: String): Boolean {
        Log.i(TAG, "Message $id from $contactId: $message")
        if (contact.id == contactId) {
            runOnUiThread {
                Log.i(TAG, "Adding message")
                val recycler = findViewById<RecyclerView>(R.id.messages_list)
                val adapter = recycler.adapter as MessageAdapter
                adapter.addMessageId(id)
                //TODO scroll only if was already at the bottom
                recycler.smoothScrollToPosition(adapter.itemCount)
            }
            return true
        }
        return false
    }

    override fun onClick(view: View) {
        val popup = PopupMenu(this, view, Gravity.TOP or Gravity.END)
        popup.inflate(R.menu.menu_context_message)
        popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_resend -> {
                    val textview = view.findViewById<AppCompatTextView>(R.id.text)
                    val intent = Intent(this, ConnectionService::class.java)
                    intent.putExtra("command", "resend")
                    intent.putExtra("id", view.tag as Long)
                    intent.putExtra("pubkey", Hex.decode(contact.pubkey))
                    intent.putExtra("message", textview.text.toString())
                    startService(intent)
                    true
                }
                R.id.menu_copy -> {
                    val textview = view.findViewById<AppCompatTextView>(R.id.text)
                    val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Mimir message", textview.text)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(applicationContext,R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.menu_reply -> { false }
                R.id.menu_forward -> { false }
                R.id.menu_delete -> { false }
                else -> {
                    Log.w(TAG, "Not implemented handler for menu item ${it.itemId}")
                    false
                }
            }
        }
        val delivered = view.findViewById<View>(R.id.status_image).tag
        popup.menu.findItem(R.id.menu_resend).isVisible = !(delivered as Boolean)
        popup.show()
    }
}