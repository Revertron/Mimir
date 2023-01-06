package com.revertron.mimir

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.widget.AppCompatTextView
import org.bouncycastle.util.encoders.Hex
import java.net.URLDecoder
import java.net.URLEncoder


class UriHandlerActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_uri_handler)
        if (intent.data != null) {
            findViewById<AppCompatTextView>(R.id.uri_text).text = intent.data.toString()
        }
    }

    override fun onStart() {
        super.onStart()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(data: Intent?) {
        val action = data?.action ?: return
        when (action) {
            Intent.ACTION_MAIN -> {}
            Intent.ACTION_VIEW, Intent.ACTION_SENDTO -> if (handleUri(data.data)) {
                val intent = Intent(this, MainActivity::class.java)
                startActivity(intent)
                finish()
            }
        }
    }

    private fun handleUri(uri: Uri?): Boolean {
        if (uri == null) {
            return false
        }
        if (uri.scheme == "mimir") {
            val pubkey = uri.host ?: return false
            val name = uri.fragment
            if (addContact(pubkey, name)) return true
        } else {
            val pubkey = uri.path ?: return false
            val name = URLDecoder.decode(uri.fragment, "UTF-8")
            if (addContact(pubkey, name)) return true
        }
        Toast.makeText(this, R.string.contact_addition_error, Toast.LENGTH_LONG).show()
        return false
    }

    private fun addContact(pubkey: String, name: String?): Boolean {
        if (validPublicKey(pubkey)) {
            if (name != null && name.isNotEmpty()) {
                @Suppress("NAME_SHADOWING")
                val pubkey = Hex.decode(pubkey)
                //TODO validate name
                val storage = getStorage()
                if (storage.getContactId(pubkey) > 0) {
                    Toast.makeText(this, R.string.contact_already_added, Toast.LENGTH_LONG).show()
                    return false
                }
                storage.addContact(pubkey, name)
                Toast.makeText(this, R.string.contact_added, Toast.LENGTH_LONG).show()
                return true
            }
        }
        return false
    }
}