package com.revertron.mimir

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.*
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.storage.StorageListener
import com.revertron.mimir.ui.Contact
import com.revertron.mimir.ui.MessageAdapter
import io.getstream.avatarview.AvatarView
import org.json.JSONObject
import java.lang.Thread.sleep


class ChatActivity : BaseActivity(), Toolbar.OnMenuItemClickListener, StorageListener, View.OnClickListener {

    companion object {
        const val TAG = "ChatActivity"
        const val PICK_IMAGE_REQUEST_CODE = 123
    }

    lateinit var contact: Contact
    lateinit var replyPanel: LinearLayoutCompat
    lateinit var replyName: AppCompatTextView
    private lateinit var replyText: AppCompatTextView
    private lateinit var attachmentPanel: ConstraintLayout
    private lateinit var attachmentPreview: AppCompatImageView
    private var attachmentJson: JSONObject? = null
    var replyTo = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)

        val pubkey = intent.getByteArrayExtra("pubkey").apply { if (this == null) finish() }!!
        val name = intent.getStringExtra("name").apply { if (this == null) finish() }!!
        val id = getStorage().getContactId(pubkey)
        contact = Contact(id, pubkey, name, null, 0)

        findViewById<AppCompatTextView>(R.id.title).text = contact.name
        supportActionBar?.title = ""
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

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
            if (text.isNotEmpty() || attachmentJson != null) {
                editText.text?.clear()
                sendMessage(contact.pubkey, text, replyTo)
                replyPanel.visibility = View.GONE
                replyText.text = ""
                replyTo = 0L
            }
        }
        findViewById<AppCompatImageButton>(R.id.attach_button).setOnClickListener {
            selectAndSendPicture()
        }
        attachmentPanel = findViewById(R.id.attachment)
        attachmentPanel.visibility = View.GONE
        attachmentPreview = findViewById(R.id.attachment_image)
        val attachmentCancel = findViewById<AppCompatImageView>(R.id.attachment_cancel)
        attachmentCancel.setOnClickListener {
            attachmentPreview.setImageDrawable(null)
            attachmentPanel.visibility = View.GONE
            attachmentJson?.getString("name")?.apply {
                deleteFileAndPreview(this@ChatActivity, this)
            }
            attachmentJson = null
        }

        val image = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (image != null) {
            getImageFromUri(image)
        }

        val adapter = MessageAdapter(getStorage(), contact.id, multiChat = false, "Me", contact.name, this, onClickOnReply(), onClickOnPicture())
        val recycler = findViewById<RecyclerView>(R.id.messages_list)
        recycler.adapter = adapter
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        getStorage().listeners.add(this)

        Thread {
            sleep(1000)
            connect(contact.pubkey)
        }.start()
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

    private fun onClickOnPicture() = fun(it: View) {
        val uri = it.tag as Uri
        val intent = Intent(this, PictureActivity::class.java)
        intent.data = uri
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.menu_contact, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                overridePendingTransition(R.anim.hold_still, R.anim.slide_out_right)
                return true
            }
            R.id.contact_call -> {
                checkAndRequestAudioPermission()
            }
            R.id.contact_info -> {
                val intent = Intent(this, ContactActivity::class.java)
                intent.putExtra("pubkey", contact.pubkey)
                intent.putExtra("name", contact.name)
                startActivity(intent)
                overridePendingTransition(R.anim.slide_in_right, R.anim.hold_still)
            }
            else -> {
                Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
            }
        }

        return super.onOptionsItemSelected(item)
    }

    private fun makeCall() {
        val intent = Intent(this, ConnectionService::class.java)
        intent.putExtra("command", "call")
        intent.putExtra("pubkey", contact.pubkey)
        startService(intent)

        val storage = (application as App).storage
        val contactId = storage.getContactId(contact.pubkey)
        val name = storage.getContactName(contactId)
        val intent2 = Intent(this, IncomingCallActivity::class.java)
        intent2.putExtra("pubkey", contact.pubkey)
        intent2.putExtra("name", name)
        intent2.putExtra("outgoing", true)
        startActivity(intent2)
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE_REQUEST_CODE && resultCode == RESULT_OK) {
            if (data == null || data.data == null) {
                Log.e(TAG, "Error getting picture")
                return
            }
            val selectedPictureUri = data.data!!
            getImageFromUri(selectedPictureUri)
        }
    }

    private fun getImageFromUri(uri: Uri) {
        if (uri.length(this) > PICTURE_MAX_SIZE) {
            Toast.makeText(this, getString(R.string.too_big_picture), Toast.LENGTH_SHORT).show()
            return
        }
        val message = prepareFileForMessage(this, uri)
        Log.i(TAG, "File message for $uri is $message")
        if (message != null) {
            val fileName = message.getString("name")
            val preview = getImagePreview(this, fileName, 512, 80)
            attachmentPreview.setImageBitmap(preview)
            attachmentPanel.visibility = View.VISIBLE
            attachmentJson = message
        }
    }

    private fun connect(pubkey: ByteArray) {
        val intent = Intent(this, ConnectionService::class.java)
        intent.putExtra("command", "connect")
        intent.putExtra("pubkey", pubkey)
        startService(intent)
    }

    private fun sendMessage(pubkey: ByteArray, text: String, replyTo: Long) {
        val intent = Intent(this, ConnectionService::class.java)
        intent.putExtra("command", "send")
        intent.putExtra("pubkey", pubkey)
        intent.putExtra("replyTo", replyTo)
        if (attachmentJson != null) {
            intent.putExtra("type", 1)
            attachmentJson!!.put("text", text)
            intent.putExtra("message", attachmentJson.toString())
            attachmentPanel.visibility = View.GONE
            attachmentJson = null
        } else {
            intent.putExtra("message", text)
        }
        startService(intent)
    }

    private fun selectAndSendPicture() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        startActivityForResult(intent, PICK_IMAGE_REQUEST_CODE)
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

    private fun checkAndRequestAudioPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                // Already have that permission
                makeCall()
            }

            /*shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                // If the user already declined such requests we need to show some text in dialog, rationale
            }*/

            else -> {
                // Ask for microphone permission
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }


    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                makeCall()
            } else {
                // User declined permission :(
            }
        }
}