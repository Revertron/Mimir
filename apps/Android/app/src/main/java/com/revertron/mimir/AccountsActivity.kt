package com.revertron.mimir

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.Toolbar
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import java.net.URLEncoder

class AccountsActivity: BaseActivity(), Toolbar.OnMenuItemClickListener {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accounts)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setOnMenuItemClickListener(this)

        val accountNumber = 1 //TODO make multi account

        val accountInfo = getStorage().getAccountInfo(accountNumber, 0L)!!
        var name = accountInfo.name
        val public = Hex.toHexString((accountInfo.keyPair.public as Ed25519PublicKeyParameters).encoded).uppercase()

        val myNameEdit = findViewById<AppCompatEditText>(R.id.my_name)
        myNameEdit.setText(name)
        // Saving the name when it changes
        myNameEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val newName = s.toString()
                if (getStorage().updateName(accountNumber, newName)) {
                    name = newName
                }
            }
        })

        val pubKeyEdit = findViewById<AppCompatEditText>(R.id.my_public_key)
        pubKeyEdit.setText(public)

        findViewById<View>(R.id.button_copy).setOnClickListener {
            val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("public key", pubKeyEdit.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(applicationContext,R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.button_link).setOnClickListener {
            val encoded = URLEncoder.encode(name, "UTF-8")
            val link = "mimir://${pubKeyEdit.text}#$encoded"
            val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("mimir link", link)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(applicationContext,R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.button_qrcode).setOnClickListener {
            Toast.makeText(applicationContext, getString(R.string.not_yet_implemented) , Toast.LENGTH_SHORT).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            overridePendingTransition(R.anim.hold_still, R.anim.slide_out_left)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.hold_still, R.anim.slide_out_left)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        return true
    }
}