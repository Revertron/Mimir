package com.revertron.mimir

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.Toolbar
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.revertron.mimir.net.MediatorManager
import com.revertron.mimir.sec.GroupChatCrypto
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.util.encoders.Hex

class GroupInviteActivity : BaseActivity() {

    companion object {
        const val TAG = "GroupInviteActivity"
    }

    private var inviteId: Long = 0
    private var chatId: Long = 0L
    private lateinit var fromPubkey: ByteArray
    private lateinit var chatName: String
    private var chatDescription: String? = null
    private var chatAvatarPath: String? = null
    private lateinit var encryptedData: ByteArray
    private var timestamp: Long = 0

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "ACTION_MEDIATOR_ERROR" -> {
                    val operation = intent.getStringExtra("operation")
                    val error = intent.getStringExtra("error")
                    if (operation == "add_user") {
                        runOnUiThread {
                            Toast.makeText(
                                this@GroupInviteActivity,
                                getString(R.string.error_accepting_invite, error),
                                Toast.LENGTH_LONG
                            ).show()
                            enableButtons(true)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_invite)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.group_invite)

        // Get invite data from intent
        inviteId = intent.getLongExtra("invite_id", 0)
        chatId = intent.getLongExtra("chat_id", 0)
        fromPubkey = intent.getByteArrayExtra("from_pubkey") ?: ByteArray(0)
        chatName = intent.getStringExtra("chat_name") ?: ""
        chatDescription = intent.getStringExtra("chat_description")
        chatAvatarPath = intent.getStringExtra("chat_avatar_path")
        encryptedData = intent.getByteArrayExtra("encrypted_data") ?: ByteArray(0)
        timestamp = intent.getLongExtra("timestamp", 0)

        if (inviteId == 0L || fromPubkey.isEmpty() || chatName.isEmpty() || encryptedData.isEmpty()) {
            Toast.makeText(this, R.string.invalid_invite, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupUI()

        // Register broadcast receiver
        val filter = IntentFilter().apply {
            addAction("ACTION_MEDIATOR_ERROR")
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver)
    }

    private fun setupUI() {
        // Set avatar
        val avatarView = findViewById<AppCompatImageView>(R.id.avatar)
        if (chatAvatarPath != null && chatAvatarPath!!.isNotEmpty()) {
            val drawable = loadRoundedAvatar(this, chatAvatarPath!!, 128, 16)
            if (drawable != null) {
                avatarView.clearColorFilter()
                avatarView.setImageDrawable(drawable)
            } else {
                setDefaultAvatar(avatarView)
            }
        } else {
            setDefaultAvatar(avatarView)
        }

        // Set chat name
        findViewById<AppCompatTextView>(R.id.chat_name).text = chatName

        // Set chat description
        val descriptionView = findViewById<AppCompatTextView>(R.id.chat_description)
        if (!chatDescription.isNullOrEmpty()) {
            descriptionView.text = chatDescription
            descriptionView.visibility = View.VISIBLE
        } else {
            descriptionView.visibility = View.GONE
        }

        // Set from info
        // TODO later try to get Contact with this pubkey and get their name
        val fromPubkeyHex = Hex.toHexString(fromPubkey)
        findViewById<AppCompatTextView>(R.id.from_info).text =
            getString(R.string.invited_by, fromPubkeyHex.take(16))

        // Set button listeners
        findViewById<Button>(R.id.button_accept).setOnClickListener {
            acceptInvite()
        }

        findViewById<Button>(R.id.button_reject).setOnClickListener {
            rejectInvite()
        }
    }

    private fun setDefaultAvatar(avatarView: AppCompatImageView) {
        avatarView.setImageResource(R.drawable.button_rounded_white)
        val colorSeed = chatId.toLong().toInt()
        val avatarColor = getAvatarColor(byteArrayOf(
            (colorSeed shr 24).toByte(),
            (colorSeed shr 16).toByte(),
            (colorSeed shr 8).toByte(),
            colorSeed.toByte()
        ))
        avatarView.setColorFilter(avatarColor, PorterDuff.Mode.MULTIPLY)
    }

    private fun acceptInvite() {
        enableButtons(false)

        Thread {
            try {
                val storage = getStorage()
                val accountInfo = storage.getAccountInfo(1, 0L)
                if (accountInfo == null) {
                    runOnUiThread {
                        Toast.makeText(this, R.string.no_account_found, Toast.LENGTH_SHORT).show()
                        enableButtons(true)
                    }
                    return@Thread
                }

                // Decrypt shared key
                val privkeyParams = accountInfo.keyPair.private as Ed25519PrivateKeyParameters
                val privkey = privkeyParams.encoded
                val sharedKey = GroupChatCrypto.decryptSharedKey(encryptedData, privkey)

                Log.i(TAG, "Decrypted shared key for chat $chatId")

                // Save chat to database
                val mediatorPubkey = MediatorManager.getDefaultMediatorPubkey()

                // Determine owner pubkey from invite (for now, use the sender as a placeholder)
                // TODO: The mediator should send the actual owner pubkey in the invite
                val ownerPubkey = fromPubkey

                // Save chat to database (avatar is already saved as file from invite)
                storage.saveGroupChat(
                    chatId,
                    chatName,
                    chatDescription,
                    chatAvatarPath, // Use the file path from the invite
                    mediatorPubkey,
                    ownerPubkey,
                    sharedKey
                )

                // Tell mediator we're accepting the invite
                // This will cause mediator to add us to members table and ask for our info
                val mediatorClient = (application as? App)?.mediatorManager?.getOrCreateClient(mediatorPubkey, accountInfo.keyPair)
                if (mediatorClient != null) {
                    try {
                        mediatorClient.respondToInvite(inviteId, 1) // 1 = accept
                        Log.i(TAG, "Sent acceptance to mediator for invite $inviteId")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send invite acceptance to mediator", e)
                        throw e
                    }
                } else {
                    Log.e(TAG, "Could not get mediator client to send acceptance")
                    throw Exception("Mediator client not available")
                }

                // Subscribe to the chat to receive messages
                // (User should already be in members table at this point)
                val intent = Intent(this, ConnectionService::class.java).apply {
                    putExtra("command", "mediator_subscribe")
                    putExtra("chat_id", chatId)
                }
                startService(intent)

                // Mark invite as accepted
                storage.updateGroupInviteStatus(inviteId, 1) // 1 = accepted

                runOnUiThread {
                    Toast.makeText(this, R.string.invite_accepted, Toast.LENGTH_SHORT).show()

                    // Open the group chat
                    val chatIntent = Intent(this, GroupChatActivity::class.java).apply {
                        putExtra(GroupChatActivity.EXTRA_CHAT_ID, chatId)
                        putExtra(GroupChatActivity.EXTRA_CHAT_NAME, chatName)
                        putExtra(GroupChatActivity.EXTRA_CHAT_DESCRIPTION, chatDescription ?: "")
                        putExtra(GroupChatActivity.EXTRA_MEMBER_COUNT, 0) // Unknown at this point
                        putExtra(GroupChatActivity.EXTRA_IS_OWNER, false) // Not owner when accepting invite
                        putExtra(GroupChatActivity.EXTRA_MEDIATOR_ADDRESS, Hex.toHexString(mediatorPubkey))
                    }
                    startActivity(chatIntent)
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error accepting invite", e)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.error_accepting_invite, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                    enableButtons(true)
                }
            }
        }.start()
    }

    private fun rejectInvite() {
        enableButtons(false)

        Thread {
            try {
                val storage = getStorage()
                val accountInfo = storage.getAccountInfo(1, 0L)
                if (accountInfo == null) {
                    runOnUiThread {
                        Toast.makeText(this, R.string.no_account_found, Toast.LENGTH_SHORT).show()
                        enableButtons(true)
                    }
                    return@Thread
                }

                // Tell mediator we're rejecting the invite
                val mediatorPubkey = MediatorManager.getDefaultMediatorPubkey()
                val mediatorClient = (application as? App)?.mediatorManager?.getOrCreateClient(mediatorPubkey, accountInfo.keyPair)
                if (mediatorClient != null) {
                    try {
                        mediatorClient.respondToInvite(inviteId, 0) // 0 = reject
                        Log.i(TAG, "Sent rejection to mediator for invite $inviteId")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send invite rejection to mediator", e)
                        // Continue anyway to delete locally
                    }
                }

                // Delete locally
                storage.deleteGroupInvite(inviteId)

                runOnUiThread {
                    Toast.makeText(this, R.string.invite_rejected, Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error rejecting invite", e)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.error_rejecting_invite, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                    enableButtons(true)
                }
            }
        }.start()
    }

    private fun enableButtons(enabled: Boolean) {
        findViewById<Button>(R.id.button_accept).isEnabled = enabled
        findViewById<Button>(R.id.button_reject).isEnabled = enabled
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.hold_still, R.anim.slide_out_right)
    }
}