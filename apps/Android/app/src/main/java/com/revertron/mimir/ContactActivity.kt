package com.revertron.mimir

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatImageView
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

        pubkey = intent.getByteArrayExtra("pubkey").apply { if (this == null) finish() }!!
        name = intent.getStringExtra("name").apply { if (this == null) finish() }!!
        host = getMimirUriHost()

        findViewById<AppCompatEditText>(R.id.contact_name).setText(name)
        val pubKeyEdit = findViewById<AppCompatEditText>(R.id.contact_public_key)
        val public = Hex.toHexString(pubkey)
        pubKeyEdit.setText(public)

        val qrCodeImageView = findViewById<AppCompatImageView>(R.id.qr_code)
        updateQrCode(name, public, qrCodeImageView)

        findViewById<View>(R.id.button_copy).setOnClickListener {
            val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("mimir public key", pubKeyEdit.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(applicationContext,R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }

        val buttonLink = findViewById<View>(R.id.button_link)
        buttonLink.setOnClickListener {
            val encoded = URLEncoder.encode(name, "UTF-8")
            val link = "https://$host/u/${pubKeyEdit.text}/$encoded"
            val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("mimir link", link)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(applicationContext,R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }

        buttonLink.setOnLongClickListener {
            val encoded = URLEncoder.encode(name, "UTF-8")
            val link = "mimir://mm/u/${pubKeyEdit.text}/$encoded"
            val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("mimir link", link)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(applicationContext,R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                overridePendingTransition(R.anim.hold_still, R.anim.slide_out_right)
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
        overridePendingTransition(R.anim.hold_still, R.anim.slide_out_right)
    }
}