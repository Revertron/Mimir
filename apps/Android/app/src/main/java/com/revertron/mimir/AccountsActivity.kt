package com.revertron.mimir

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.Toolbar
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex

class AccountsActivity: BaseActivity(), Toolbar.OnMenuItemClickListener {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accounts)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setOnMenuItemClickListener(this)

        val accountNumber = 1 //TODO make multi account

        val accountInfo = getStorage().getAccountInfo(accountNumber)
        var name = accountInfo.name
        val public = Hex.toHexString((accountInfo.keyPair.public as Ed25519PublicKeyParameters).encoded)

        val myNameEdit = findViewById<AppCompatEditText>(R.id.my_name)
        myNameEdit.setText(name)
        val pubKeyEdit = findViewById<AppCompatEditText>(R.id.my_public_key)
        pubKeyEdit.setText(public)

        findViewById<AppCompatButton>(R.id.save_button).setOnClickListener {
            val newName = myNameEdit.text.toString()
            if (getStorage().updateName(accountNumber, newName)) {
                name = newName
                Toast.makeText(applicationContext, R.string.saved, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.button_copy).setOnClickListener {
            val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("public key", pubKeyEdit.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(applicationContext,R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.button_link).setOnClickListener {
            val link = "mimir://${pubKeyEdit.text}#$name"
            val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("mimir link", link)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(applicationContext,R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.button_qrcode).setOnClickListener {
            Toast.makeText(applicationContext, getString(R.string.not_yet_implemented) , Toast.LENGTH_SHORT).show()
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        return true
    }
}