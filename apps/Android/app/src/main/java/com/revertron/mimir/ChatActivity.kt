package com.revertron.mimir

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.storage.StorageListener
import com.revertron.mimir.ui.Contact
import com.revertron.mimir.ui.MessageAdapter
import io.getstream.avatarview.AvatarView


class ChatActivity : BaseActivity(), Toolbar.OnMenuItemClickListener, StorageListener, View.OnClickListener {

    companion object {
        const val TAG = "ChatActivity"
    }

    lateinit var contact: Contact
    lateinit var replyPanel: LinearLayoutCompat
    lateinit var replyName: AppCompatTextView
    private lateinit var replyText: AppCompatTextView
    var replyTo = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)

        val pubkey = intent.getByteArrayExtra("pubkey").apply { if (this == null) finish() }!!
        val name = intent.getStringExtra("name").apply { if (this == null) finish() }!!
        val id = getStorage().getContactId(pubkey)
        contact = Contact(id, pubkey, name, "", 0L, false, 0)

        findViewById<AppCompatTextView>(R.id.title).text = contact.name
        supportActionBar?.title = ""
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setOnMenuItemClickListener(this)

        val avatar = findViewById<AvatarView>(R.id.avatar)
        val initials = getInitials(contact)
        avatar.avatarInitials = initials
        val avatarColor = getAvatarColor(contact.pubkey)
        avatar.avatarInitialsBackgroundColor = avatarColor
        if (isColorDark(avatarColor)) {
            avatar.avatarInitialsTextColor = 0xFFFFFFFF.toInt()
        } else {
            avatar.avatarInitialsTextColor = 0xFF000000.toInt()
        }

        replyPanel = findViewById(R.id.reply_panel)
        replyPanel.visibility = View.GONE
        replyName = findViewById(R.id.reply_contact_name)
        replyText = findViewById(R.id.reply_text)
        findViewById<AppCompatImageView>(R.id.reply_close).setOnClickListener {
            replyPanel.visibility = View.GONE
            replyTo = 0L
        }

        val editText = findViewById<AppCompatEditText>(R.id.message_edit)
        val sendButton = findViewById<AppCompatImageButton>(R.id.send_button)
        sendButton.setOnClickListener {
            val text: String = editText.text.toString().trim()
            if (text.isNotEmpty()) {
                editText.text?.clear()
                sendMessage(contact.pubkey, text, replyTo)
                replyPanel.visibility = View.GONE
                replyText.text = ""
                replyTo = 0L
            }
        }
        val adapter = MessageAdapter(getStorage(), contact.id, multiChat = false, "Me", contact.name, this, onClickOnReply())
        val recycler = findViewById<RecyclerView>(R.id.messages_list)
        recycler.adapter = adapter
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        getStorage().listeners.add(this)
    }

    private fun onClickOnReply() = fun(it: View) {
        //TODO animate target view
        val id = it.tag as Long
        val recycler = findViewById<RecyclerView>(R.id.messages_list)
        val adapter = recycler.adapter as MessageAdapter
        val position = adapter.getMessageIdPosition(id)
        if (position >= 0) {
            recycler.smoothScrollToPosition(position)
        }
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

    private fun sendMessage(pubkey: ByteArray, text: String, replyTo: Long) {
        val intent = Intent(this, ConnectionService::class.java)
        intent.putExtra("command", "send")
        intent.putExtra("pubkey", pubkey)
        intent.putExtra("message", text)
        intent.putExtra("replyTo", replyTo)
        startService(intent)
    }

    override fun onMessageSent(id: Long, contactId: Long) {
        runOnUiThread {
            Log.i(TAG, "Message $id sent to $contactId")
            if (contact.id == contactId) {
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

    override fun onMessageReceived(id: Long, contactId: Long): Boolean {
        Log.i(TAG, "Message $id from $contactId")
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
                R.id.menu_copy -> {
                    val textview = view.findViewById<AppCompatTextView>(R.id.text)
                    val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Mimir message", textview.text)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(applicationContext,R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.menu_reply -> {
                    val id = (view.tag as Long)
                    val message = getStorage().getMessage(id)
                    replyName.text = contact.name
                    replyText.text = message?.getText()
                    replyPanel.visibility = View.VISIBLE
                    replyTo = message?.guid ?: 0L
                    Log.i(TAG, "Replying to guid $replyTo")
                    false
                }
                R.id.menu_forward -> { false }
                R.id.menu_delete -> {
                    showDeleteMessageConfirmDialog(view.tag as Long)
                    true
                }
                else -> {
                    Log.w(TAG, "Not implemented handler for menu item ${it.itemId}")
                    false
                }
            }
        }
        popup.show()
    }

    private fun showDeleteMessageConfirmDialog(messageId: Long) {
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        val builder: AlertDialog.Builder = AlertDialog.Builder(wrapper)
        builder.setTitle(getString(R.string.delete_message_dialog_title))
        builder.setMessage(R.string.delete_message_dialog_text)
        builder.setIcon(R.drawable.ic_delete)
        builder.setPositiveButton(getString(R.string.menu_delete)) { _, _ ->
            (application as App).storage.deleteMessage(messageId)
            val recycler = findViewById<RecyclerView>(R.id.messages_list)
            val adapter = recycler.adapter as MessageAdapter
            adapter.deleteMessageId(messageId)
        }
        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
            dialog.cancel()
        }
        builder.show()
    }
}