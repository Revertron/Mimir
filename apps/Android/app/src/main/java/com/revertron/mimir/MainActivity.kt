package com.revertron.mimir

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.revertron.mimir.storage.StorageListener
import com.revertron.mimir.ui.ChatListItem
import com.revertron.mimir.ui.Contact
import com.revertron.mimir.ui.ContactsAdapter
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex


class MainActivity : BaseActivity(), View.OnClickListener, View.OnLongClickListener, StorageListener {

    companion object {
        const val TAG = "MainActivity"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            showOnlineState(App.app.online)   // update the dot colour
            handler.postDelayed(this, 3_000)  // schedule again in 3 s
        }
    }

    var avatarDrawable: Drawable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setHomeActionContentDescription(R.string.account)
        if (intent?.hasExtra("no_service") != true) {
            startService()
        }

        val info = getStorage().getAccountInfo(1, 0L)
        if (info != null && info.avatar.isNotEmpty()) {
            avatarDrawable = loadRoundedAvatar(this, info.avatar)
            if (avatarDrawable != null) {
                toolbar.navigationIcon = avatarDrawable
            }
        }

        getStorage().listeners.add(this)
        val recycler = findViewById<RecyclerView>(R.id.contacts_list)
        recycler.addItemDecoration(DividerItemDecoration(baseContext, RecyclerView.VERTICAL))
    }

    override fun onResume() {
        super.onResume()
        val recycler = findViewById<RecyclerView>(R.id.contacts_list)
        if (recycler.adapter == null) {
            val chatList = getChatList()
            val adapter = ContactsAdapter(chatList, this, this)
            recycler.adapter = adapter
            recycler.layoutManager = LinearLayoutManager(this)
        } else {
            refreshContacts()
        }
        showSnackBars()
        handler.post(refreshTask)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshTask)
    }

    override fun onDestroy() {
        getStorage().listeners.remove(this)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)

        val invites = getStorage().getPendingGroupInvites()
        menu.findItem(R.id.group_invites).isVisible = invites.isNotEmpty()
        return true
    }

    @Suppress("NAME_SHADOWING")
    @SuppressLint("NotifyDataSetChanged")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.plus -> {
                showAddContactDialog()
                return true
            }
            R.id.group_invites -> {
                val intent = Intent(this, InviteListActivity::class.java)
                startActivity(intent, animFromRight.toBundle())
                return true
            }
            R.id.create_chat -> {
                val intent = Intent(this, NewChatActivity::class.java)
                startActivity(intent, animFromRight.toBundle())
                return true
            }
            android.R.id.home -> {
                val intent = Intent(this, AccountsActivity::class.java)
                startActivity(intent, animFromLeft.toBundle())
            }
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent, animFromRight.toBundle())
            }
            else -> {
                Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
            }
        }

        return super.onOptionsItemSelected(item)
    }

    private fun startService() {
        val intent = Intent(this, ConnectionService::class.java)
        intent.putExtra("command", "start")
        startService(intent)
    }

    override fun onClick(view: View) {
        if (view.tag != null) {
            when (val item = view.tag as ChatListItem) {
                is ChatListItem.ContactItem -> {
                    val addr = Hex.toHexString(item.pubkey)
                    Log.i(TAG, "Clicked on contact $addr")

                    val intent = Intent(this, ChatActivity::class.java)
                    intent.putExtra("pubkey", item.pubkey)
                    intent.putExtra("name", item.name)
                    startActivity(intent, animFromRight.toBundle())
                }
                is ChatListItem.GroupChatItem -> {
                    Log.i(TAG, "Clicked on group chat ${item.chatId}")

                    val intent = Intent(this, GroupChatActivity::class.java)
                    intent.putExtra(GroupChatActivity.EXTRA_CHAT_ID, item.chatId)
                    intent.putExtra(GroupChatActivity.EXTRA_CHAT_NAME, item.name)
                    intent.putExtra(GroupChatActivity.EXTRA_CHAT_DESCRIPTION, item.description)
                    intent.putExtra(GroupChatActivity.EXTRA_IS_OWNER, item.isOwner)
                    intent.putExtra(GroupChatActivity.EXTRA_MEDIATOR_ADDRESS, item.mediatorAddress)
                    startActivity(intent, animFromRight.toBundle())
                }
            }
        }
    }

    override fun onLongClick(v: View): Boolean {
        when (val item = v.tag as ChatListItem) {
            is ChatListItem.ContactItem -> {
                // Convert ChatListItem.ContactItem back to Contact for the popup menu
                val contact = Contact(item.id, item.pubkey, item.name, item.lastMessage, item.unreadCount, item.avatar)
                showContactPopupMenu(contact, v)
            }
            is ChatListItem.GroupChatItem -> {
                // TODO: Show group chat popup menu (leave group, mute, etc.)
                Toast.makeText(this, "Group chat options - TODO", Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    override fun onMessageReceived(id: Long, contactId: Long): Boolean {
        runOnUiThread {
            refreshContacts()
        }
        return false
    }

    override fun onGroupMessageReceived(chatId: Long, id: Long, contactId: Long): Boolean {
        runOnUiThread {
            refreshContacts()
        }
        return false
    }

    override fun onGroupChatChanged(chatId: Long): Boolean {
        runOnUiThread {
            refreshContacts()
        }
        return false
    }

    fun showOnlineState(isOnline: Boolean) {
        val avatar = if (avatarDrawable != null) {
            avatarDrawable
        } else {
            ContextCompat.getDrawable(this, R.drawable.contact_no_avatar_small)!!
        }
        val badge = if (isOnline) {
            ContextCompat.getDrawable(this, R.drawable.status_badge_green)!!
        } else {
            ContextCompat.getDrawable(this, R.drawable.status_badge_red)!!
        }

        // combine both drawables; dot on top, bottom-right aligned
        val layers = arrayOf(avatar, badge)
        val layered = LayerDrawable(layers)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.navigationIcon = layered
    }

    fun showSnackBars() {
        // Check if notifications are allowed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!areNotificationsEnabled(this)) {
                val root = findViewById<View>(android.R.id.content)
                Snackbar.make(root, getString(R.string.allow_notifications_snack), Snackbar.LENGTH_INDEFINITE)
                    .setAction(getString(R.string.allow)) {
                        try {
                            val intent =
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                                }
                            startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            e.printStackTrace()
                        }
                    }
                    .setTextMaxLines(3)
                    .show()
            }
        }

        // Check if app is battery optimized
        if (!isNotBatteryOptimised(this)) {
            val root = findViewById<View>(android.R.id.content)
            Snackbar.make(root, getString(R.string.add_to_power_exceptions), Snackbar.LENGTH_INDEFINITE)
                .setAction(getString(R.string.allow)) {
                    val action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    try {
                        val intent = Intent(action, "package:$packageName".toUri())
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        e.printStackTrace()
                        // Fallback: open the generic battery-settings screen
                        startActivity(Intent(action))
                    }
                }
                .setTextMaxLines(3)
                .show()
        }

        // Check if our links are processed properly
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!isDefaultForDomain(this, getMimirUriHost())) {
                val root = findViewById<View>(android.R.id.content)
                Snackbar.make(root, getString(R.string.enable_domain_links), Snackbar.LENGTH_INDEFINITE)
                    .setAction(getString(R.string.enable)) {
                        val action = Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS
                        try {
                            val intent = Intent(action, "package:$packageName".toUri())
                            startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            e.printStackTrace()
                            // Fallback: open the generic battery-settings screen
                            startActivity(Intent(action))
                        }
                    }
                    .setTextMaxLines(3)
                    .show()
            }
        }
    }

    @Suppress("NAME_SHADOWING")
    @SuppressLint("NotifyDataSetChanged")
    private fun showAddContactDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.add_contact_dialog, null)
        val name = view.findViewById<AppCompatEditText>(R.id.contact_name)
        val pubkey = view.findViewById<AppCompatEditText>(R.id.contact_pubkey)
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        val builder: AlertDialog.Builder = AlertDialog.Builder(wrapper)
        builder.setTitle(getString(R.string.add_contact))
        builder.setView(view)
        builder.setIcon(R.drawable.ic_contact_add_daynight)
        builder.setPositiveButton(getString(R.string.contact_add)) { _, _ ->
            val name = name.text.toString().trim()
            val pubKeyString = pubkey.text.toString().trim()
            if (!validPublicKey(pubKeyString)) {
                Toast.makeText(this@MainActivity, R.string.wrong_public_key, Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }
            val pubkey = Hex.decode(pubKeyString)
            if (name.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.empty_name, Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }
            (application as App).storage.addContact(pubkey, name)
            refreshContacts()
        }
        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
            dialog.cancel()
        }
        builder.show()
    }

    private fun showRenameContactDialog(contact: Contact) {
        val view = LayoutInflater.from(this).inflate(R.layout.rename_contact_dialog, null)
        val name = view.findViewById<AppCompatEditText>(R.id.contact_name)
        name.setText(contact.name)
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        val builder: AlertDialog.Builder = AlertDialog.Builder(wrapper)
        builder.setTitle(getString(R.string.rename_contact))
        builder.setView(view)
        builder.setIcon(R.drawable.ic_contact_rename)
        builder.setPositiveButton(getString(R.string.rename)) { _, _ ->
            val newName = name.text.toString()
            (application as App).storage.renameContact(contact.id, newName, true)
            refreshContacts()
        }
        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
            dialog.cancel()
        }
        builder.show()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun showContactPopupMenu(contact: Contact, v: View) {
        val popup = PopupMenu(this, v, Gravity.TOP or Gravity.END)
        popup.inflate(R.menu.menu_context_contact)
        popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                /*R.id.add_address -> {
                    showAddAddressDialog(contact)
                    true
                }*/
                R.id.rename -> {
                    showRenameContactDialog(contact)
                    true
                }
                R.id.copy_id -> {
                    val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Mimir contact ID", Hex.toHexString(contact.pubkey))
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(applicationContext,R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.remove_contact -> {
                    //TODO add confirmation dialog
                    getStorage().removeContactAndChat(contact.id)
                    refreshContacts()
                    true
                }
                else -> {
                    Log.w(ChatActivity.TAG, "Not implemented handler for menu item ${it.itemId}")
                    false
                }
            }
        }
        popup.show()
    }

    override fun onMessageDelivered(id: Long, delivered: Boolean) {
        runOnUiThread {
            super.onMessageDelivered(id, delivered)
            val recycler = findViewById<RecyclerView>(R.id.contacts_list)
            val adapter = recycler.adapter as ContactsAdapter
            adapter.setMessageDelivered(id)
        }
    }

    private fun getChatList(): List<ChatListItem> {
        val storage = getStorage()
        val contacts = storage.getContactList()
        val groupChats = storage.getGroupChatList()

        val chatItems = mutableListOf<ChatListItem>()

        // Convert contacts to ChatListItems
        chatItems.addAll(contacts.map { contact ->
            ChatListItem.ContactItem(
                id = contact.id,
                pubkey = contact.pubkey,
                name = contact.name,
                lastMessage = contact.lastMessage,
                unreadCount = contact.unread,
                avatar = contact.avatar
            )
        })

        // Convert group chats to ChatListItems
        chatItems.addAll(groupChats.map { groupChat ->
            val avatar = storage.getGroupChatAvatar(groupChat.chatId)
            // Check if current user is the owner
            val accountInfo = storage.getAccountInfo(1, 0L)
            val isOwner = accountInfo?.let { info ->
                groupChat.ownerPubkey.contentEquals(info.keyPair.public.let {
                    (it as Ed25519PublicKeyParameters).encoded
                })
            } ?: false

            // Get last message for the group chat
            val lastMessage = storage.getLastGroupMessage(groupChat.chatId)
            val lastMessageText = if (lastMessage?.type == 1) {
                "\uD83D\uDDBC\uFE0F " + lastMessage.getText(this)
            } else {
                lastMessage?.getText(this)
            }

            ChatListItem.GroupChatItem(
                id = groupChat.chatId,
                chatId = groupChat.chatId,
                name = groupChat.name,
                description = groupChat.description ?: "",
                mediatorAddress = groupChat.mediatorPubkey,
                memberCount = storage.getGroupChatMembersCount(groupChat.chatId),
                isOwner = isOwner,
                avatar = avatar,
                lastMessageText = lastMessageText,
                lastMessageTime = groupChat.lastMessageTime,
                unreadCount = groupChat.unreadCount
            )
        })

        // Sort by last message time (most recent first)
        return chatItems.sortedByDescending { it.lastMessageTime }
    }

    private fun refreshContacts() {
        val chatList = getChatList()
        val recycler = findViewById<RecyclerView>(R.id.contacts_list)
        val adapter = recycler.adapter as ContactsAdapter
        adapter.setContacts(chatList)
    }

    fun areNotificationsEnabled(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun isNotBatteryOptimised(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
}