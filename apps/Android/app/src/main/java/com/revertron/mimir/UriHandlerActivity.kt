package com.revertron.mimir

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import org.bouncycastle.util.encoders.Hex
import java.net.URLDecoder


class UriHandlerActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_uri_handler)
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
            Intent.ACTION_VIEW, Intent.ACTION_SENDTO -> {
                handleUri(data.data)
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
        val path = uri.path?.trim('/') ?: return false
        val parts = path.split("/")
        // parts[0] contains the type of entity that we are trying to add (user, chat, news)
        if (parts.size >= 2) {
            val pubkey = parts[1]
            val name = if (parts.size == 3) URLDecoder.decode(parts[2], "UTF-8") else ""
            if (addContact(pubkey, name)) return true
        }
        return false
    }

    private fun addContact(pubkey: String, name: String): Boolean {
        if (validPublicKey(pubkey)) {
            val publicKey = Hex.decode(pubkey)
            //TODO validate name
            val storage = getStorage()
            if (storage.getContactId(publicKey) > 0) {
                Toast.makeText(this, R.string.contact_already_added, Toast.LENGTH_LONG).show()
                return false
            }
            storage.addContact(publicKey, name)
            Toast.makeText(this, R.string.contact_added, Toast.LENGTH_LONG).show()
            return true
        } else {
            Toast.makeText(this, R.string.contact_addition_error_wrong_key, Toast.LENGTH_LONG).show()
            return false
        }
    }
}