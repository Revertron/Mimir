package com.revertron.mimir

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.Toolbar
import org.bouncycastle.util.encoders.Hex
import java.net.URLEncoder

class ContactActivity: BaseActivity() {

    lateinit var pubkey: ByteArray
    lateinit var name: String
    lateinit var host: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.contact_info)

        pubkey = intent.getByteArrayExtra("pubkey").apply { if (this == null) finish() }!!
        name = intent.getStringExtra("name").apply { if (this == null) finish() }!!
        host = getMimirUriHost()

        val id = getStorage().getContactId(pubkey)
        val avatar = getStorage().getContactAvatar(id, 128, 8)
        if (avatar != null) {
            val avatarView = findViewById<AppCompatImageView>(R.id.avatar)
            avatarView.setImageDrawable(avatar)
        }

        // Set contact name in header
        findViewById<AppCompatTextView>(R.id.contact_name).text = name

        // Set contact description if available
        val contactInfo = getStorage().getContactInfo(id)
        if (!contactInfo.isNullOrEmpty()) {
            findViewById<View>(R.id.description_section).visibility = View.VISIBLE
            findViewById<AppCompatTextView>(R.id.description_text).text = contactInfo
        } else {
            findViewById<View>(R.id.description_section).visibility = View.GONE
        }

        // Set public key
        val public = Hex.toHexString(pubkey)
        val publicKeyView = findViewById<AppCompatTextView>(R.id.contact_public_key)
        publicKeyView.text = public

        // Set contact link
        val encoded = URLEncoder.encode(name, "UTF-8")
        val link = "mimir://mm/u/$public/$encoded"
        findViewById<AppCompatTextView>(R.id.contact_link_text).text = link
        val link2 = "https://$host/u/$public/$encoded"
        findViewById<AppCompatTextView>(R.id.contact_link2_text).text = link2

        // Message button
        findViewById<View>(R.id.btn_message).setOnClickListener {
            val intent = Intent(this, ChatActivity::class.java)
            intent.putExtra("pubkey", pubkey)
            intent.putExtra("name", name)
            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            startActivity(intent, animFromRight.toBundle())
            finish()
        }

        // Mute button
        findViewById<View>(R.id.btn_mute).setOnClickListener {
            // TODO: Implement mute functionality
            Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
        }

        // Block button
        findViewById<View>(R.id.btn_block).setOnClickListener {
            // TODO: Implement block functionality
            Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
        }

        // Copy public key button
        findViewById<View>(R.id.copy_key_button).setOnClickListener {
            val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("mimir public key", public)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this@ContactActivity, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }

        // Copy public key section (entire section clickable)
        findViewById<View>(R.id.public_key_section).setOnClickListener {
            val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("mimir public key", public)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this@ContactActivity, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }

        // Copy contact link section (entire section clickable)
        findViewById<View>(R.id.share_link_section).setOnClickListener {
            val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("mimir link", link)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this@ContactActivity, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }

        // Copy contact link section (entire section clickable)
        findViewById<View>(R.id.share_link2_section).setOnClickListener {
            val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("mimir link", link2)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this@ContactActivity, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }

        // QR code button
        val qrCodeButton = findViewById<View>(R.id.qr_code_button)
        qrCodeButton.setOnClickListener {
            showQrCodeDialog(this, name, public)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
            else -> {
                Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.hold_still, R.anim.slide_out_right)
    }
}
