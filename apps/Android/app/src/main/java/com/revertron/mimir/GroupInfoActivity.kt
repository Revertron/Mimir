package com.revertron.mimir

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.PorterDuff
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.revertron.mimir.storage.GroupMemberInfo
import com.revertron.mimir.ui.GroupMemberAdapter
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import kotlin.math.abs

class GroupInfoActivity : BaseActivity(), View.OnClickListener {

    companion object {
        const val TAG = "GroupInfoActivity"
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_CHAT_NAME = "chat_name"
        const val EXTRA_CHAT_DESCRIPTION = "chat_description"
        const val EXTRA_IS_OWNER = "is_owner"
        const val EXTRA_MEDIATOR_ADDRESS = "mediator_address"

        private const val REQUEST_SELECT_CONTACT = 100
    }

    private lateinit var chatName: String
    private lateinit var chatDescription: String
    private lateinit var ownerPubkey: ByteArray
    private lateinit var currentUserPubkey: ByteArray
    private lateinit var mediatorAddress: ByteArray
    private lateinit var adapter: GroupMemberAdapter
    private var chatId: Long = 0
    private var isOwner: Boolean = false
    private var memberCount: Int = 0
    private var onlineCount: Int = 0 // TODO: Implement real online tracking

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_info)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "" // Hide default title initially

        // Extract group chat info from intent
        chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0)
        chatName = intent.getStringExtra(EXTRA_CHAT_NAME) ?: ""
        chatDescription = intent.getStringExtra(EXTRA_CHAT_DESCRIPTION) ?: ""
        isOwner = intent.getBooleanExtra(EXTRA_IS_OWNER, false)
        mediatorAddress = intent.getByteArrayExtra(EXTRA_MEDIATOR_ADDRESS) ?: ByteArray(0)

        if (chatId == 0L) {
            Log.e(TAG, "Missing chat_id")
            finish()
            return
        }

        // Get current user's public key
        val accountInfo = getStorage().getAccountInfo(1, 0L)
        if (accountInfo != null) {
            currentUserPubkey = (accountInfo.keyPair.public as Ed25519PublicKeyParameters).encoded
        } else {
            Log.e(TAG, "No account found")
            finish()
            return
        }

        // Load group chat info from database
        val chatInfo = getStorage().getGroupChat(chatId)
        if (chatInfo != null) {
            ownerPubkey = chatInfo.ownerPubkey
        } else {
            Log.e(TAG, "Chat $chatId not found in database")
            finish()
            return
        }

        setupUI()
        setupScrollListener()
        loadMembers()
    }

    private fun setupScrollListener() {
        val scrollView = findViewById<androidx.core.widget.NestedScrollView>(R.id.scroll_view)
        val headerSection = findViewById<View>(R.id.header_section)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        val toolbarTextContainer = findViewById<View>(R.id.toolbar_text_container)
        val toolbarTitle = findViewById<AppCompatTextView>(R.id.toolbar_title)
        //val toolbarSubtitle = findViewById<AppCompatTextView>(R.id.toolbar_subtitle)

        // Set toolbar text content
        toolbarTitle.text = chatName
        val maxElevation = 4 * resources.displayMetrics.density // convert 4dp to pixels

        scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            // Get the height of the header section
            headerSection.post {
                val headerHeight = headerSection.height

                // Calculate fade percentage based on scroll position
                // When scrollY reaches headerHeight, toolbar texts should be fully visible
                val fadePercentage = (scrollY.toFloat() / headerHeight.toFloat()).coerceIn(0f, 1f)

                // Animate toolbar text alpha
                toolbarTextContainer.alpha = fadePercentage

                // Adjust toolbar elevation
                toolbar.elevation = maxElevation * fadePercentage
            }
        }
    }

    private fun setupUI() {
        // Set group name
        findViewById<AppCompatTextView>(R.id.group_name).text = chatName

        // Load and set avatar
        val avatar = getStorage().getGroupChatAvatar(chatId, 128, 8)
        val avatarView = findViewById<AppCompatImageView>(R.id.avatar)
        if (avatar != null) {
            avatarView.clearColorFilter()
            avatarView.setImageDrawable(avatar)
        } else {
            avatarView.setImageResource(R.drawable.button_rounded_white)
            val avatarColor = getAvatarColor(chatId.toString().toByteArray())
            avatarView.setColorFilter(avatarColor, PorterDuff.Mode.MULTIPLY)
        }

        // Set member count
        memberCount = getStorage().getGroupChatMembersCount(chatId)
        updateMemberCount()

        // Set description if available
        val descriptionSection = findViewById<LinearLayoutCompat>(R.id.description_section)
        val descriptionText = findViewById<AppCompatTextView>(R.id.description_text)
        if (chatDescription.isNotEmpty()) {
            descriptionSection.visibility = View.VISIBLE
            descriptionText.text = chatDescription
        } else {
            descriptionSection.visibility = View.GONE
        }

        // Set invite link
        val inviteLinkText = findViewById<AppCompatTextView>(R.id.invite_link_text)
        val inviteLink = "mimir://g/$chatId"
        inviteLinkText.text = inviteLink

        // Setup click listeners
        findViewById<LinearLayoutCompat>(R.id.btn_message).setOnClickListener {
            // Close this activity to return to chat
            finish()
        }

        findViewById<LinearLayoutCompat>(R.id.btn_mute).setOnClickListener {
            // TODO: Implement mute/unmute functionality
            Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
        }

        findViewById<LinearLayoutCompat>(R.id.btn_video_chat).setOnClickListener {
            // TODO: Implement video chat functionality
            Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
        }

        findViewById<LinearLayoutCompat>(R.id.invite_link_section).setOnClickListener {
            val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("mimir group invite link", inviteLink)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }

        findViewById<AppCompatImageView>(R.id.qr_code_button).setOnClickListener {
            // TODO: Show QR code dialog
            Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
        }

        findViewById<LinearLayoutCompat>(R.id.add_members_button).setOnClickListener {
            if (isOwner) {
                openContactSelector()
            } else {
                Toast.makeText(this, "Only owner can add members", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateMemberCount() {
        val memberCountText = findViewById<AppCompatTextView>(R.id.member_count)
        val toolbarSubtitle = findViewById<AppCompatTextView>(R.id.toolbar_subtitle)

        // Set member count text
        val countText = if (onlineCount > 0) {
            getString(R.string.member_count_online, memberCount, onlineCount)
        } else {
            getString(R.string.members_only, memberCount)
        }

        // Update both header and toolbar
        memberCountText.text = countText
        toolbarSubtitle.text = countText
    }

    private fun loadMembers() {
        Thread {
            val members = getStorage().getGroupMembers(chatId).toMutableList()

            if (BuildConfig.DEBUG) {
                // Add 20 fake members for testing scroll
                val fakeNames = listOf(
                    "Alice Johnson", "Bob Smith", "Charlie Brown", "Diana Prince",
                    "Edward Norton", "Fiona Green", "George Miller", "Hannah Montana",
                    "Ivan Petrov", "Julia Roberts", "Kevin Hart", "Laura Palmer",
                    "Michael Scott", "Natasha Romanoff", "Oliver Queen", "Patricia Hill",
                    "Quentin Blake", "Rachel Green", "Samuel Jackson", "Tina Turner"
                )

                for (i in 0..19) {
                    val fakePubkey = ByteArray(32) { (i * 13 + it).toByte() }
                    members.add(
                        GroupMemberInfo(
                            pubkey = fakePubkey,
                            nickname = fakeNames[i],
                            info = "Test user $i",
                            avatarPath = null,
                            permissions = if (i % 7 == 0) 1 else 0, // Every 7th is admin
                            joinedAt = System.currentTimeMillis() - (i * 86400000L),
                            banned = false
                        )
                    )
                }
            }

            // Sort members: owner first, then admins, then regular members
            val sortedMembers = members.sortedWith(compareBy(
                { !it.pubkey.contentEquals(ownerPubkey) }, // Owner first
                { it.permissions == 0 }, // Admins second
                { it.nickname ?: Hex.toHexString(it.pubkey) } // Then by name
            ))

            runOnUiThread {
                setupMembersList(sortedMembers)
            }
        }.start()
    }

    private fun setupMembersList(members: List<GroupMemberInfo>) {
        adapter = GroupMemberAdapter(
            chatId = chatId,
            ownerPubkey = ownerPubkey,
            currentUserPubkey = currentUserPubkey,
            storage = getStorage(),
            members = members,
            onClick = this
        )

        val recyclerView = findViewById<RecyclerView>(R.id.members_list)
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun openContactSelector() {
        val intent = Intent(this, ContactSelectorActivity::class.java).apply {
            putExtra(ContactSelectorActivity.EXTRA_TITLE, getString(R.string.add_member))
            // TODO: Filter out contacts who are already members
        }
        startActivityForResult(intent, REQUEST_SELECT_CONTACT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_SELECT_CONTACT -> {
                if (resultCode == RESULT_OK && data != null) {
                    val pubkey = data.getByteArrayExtra(ContactSelectorActivity.RESULT_PUBKEY)
                    val name = data.getStringExtra(ContactSelectorActivity.RESULT_NAME)

                    if (pubkey != null) {
                        sendInvite(pubkey, name ?: "Unknown")
                    }
                }
            }
        }
    }

    private fun sendInvite(recipientPubkey: ByteArray, recipientName: String) {
        // Send intent to ConnectionService to send invite
        val intent = Intent(this, ConnectionService::class.java)
        intent.putExtra("command", "mediator_send_invite")
        intent.putExtra("chat_id", chatId)
        intent.putExtra("recipient_pubkey", recipientPubkey)
        startService(intent)

        val pubkeyHex = Hex.toHexString(recipientPubkey).take(8)
        Log.i(TAG, "Sent invite request to ConnectionService for user $pubkeyHex")

        Toast.makeText(
            this,
            "Invite sent to $recipientName",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onClick(v: View?) {
        // Handle member item click
        val member = v?.tag as? GroupMemberInfo
        if (member != null) {
            // TODO: Show member profile or actions dialog
            Toast.makeText(
                this,
                "Member: ${member.nickname ?: Hex.toHexString(member.pubkey).take(16)}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
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
