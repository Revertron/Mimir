package com.revertron.mimir

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
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
import com.revertron.mimir.ui.Contact
import com.revertron.mimir.ui.ContactsAdapter
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

    lateinit var topSheet: LinearLayout
    lateinit var overlay: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setHomeActionContentDescription(R.string.account)
        if (intent?.hasExtra("no_service") != true) {
            startService()
        }

        /*topSheet = findViewById(R.id.topSheet)
        overlay = findViewById(R.id.overlay)

        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_settings) {
                if (topSheet.isGone) openMenu() else closeMenu()
                true
            } else false
        }

        overlay.setOnClickListener {
            closeMenu()
        }
        findViewById<View>(R.id.menu_item_settings).setOnClickListener { view ->
            closeMenu()
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
            overridePendingTransition(R.anim.slide_in_right, R.anim.hold_still)
        }
        findViewById<View>(R.id.menu_item_about).setOnClickListener { view ->
            closeMenu()
            val intent = Intent(this, AboutActivity::class.java)
            startActivity(intent)
            overridePendingTransition(R.anim.slide_in_right, R.anim.hold_still)
        }*/

        getStorage().listeners.add(this)
        val recycler = findViewById<RecyclerView>(R.id.contacts_list)
        recycler.addItemDecoration(DividerItemDecoration(baseContext, RecyclerView.VERTICAL))
    }

    override fun onResume() {
        super.onResume()
        val recycler = findViewById<RecyclerView>(R.id.contacts_list)
        if (recycler.adapter == null) {
            val contacts = (application as App).storage.getContactList()
            val adapter = ContactsAdapter(contacts, this, this)
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

    @Suppress("NAME_SHADOWING")
    @SuppressLint("NotifyDataSetChanged")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.plus -> {
                showAddContactDialog()
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
            val contact = view.tag as Contact
            val addr = Hex.toHexString(contact.pubkey)
            Log.i(TAG, "Clicked on $addr")

            val intent = Intent(this, ChatActivity::class.java)
            intent.putExtra("pubkey", contact.pubkey)
            intent.putExtra("name", contact.name)
            startActivity(intent, animFromRight.toBundle())
        }
    }

    override fun onLongClick(v: View): Boolean {
        val contact = v.tag as Contact
        showContactPopupMenu(contact, v)
        return true
    }

    /*override fun onBackPressed() {
        if (topSheet.isVisible) {
            closeMenu()
            return
        }
        super.onBackPressed()
    }*/

    override fun onMessageReceived(id: Long, contactId: Long): Boolean {
        runOnUiThread {
            refreshContacts()
        }
        return false
    }

    fun showOnlineState(isOnline: Boolean) {
        val avatar = ContextCompat.getDrawable(this, R.drawable.contact_no_avatar_small)!!
        val badge    = ContextCompat.getDrawable(this, R.drawable.status_badge_green)!!.mutate()

        if (!isOnline) {
            badge.setTint(0xFFCC0000.toInt())
            badge.setTintMode(PorterDuff.Mode.SRC_IN)
        }

        // combine both drawables; dot on top, bottom-right aligned
        val layers = arrayOf(avatar, badge)
        val layered = LayerDrawable(layers)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
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

    fun openMenu() {
        topSheet.visibility = View.VISIBLE
        overlay.visibility = View.VISIBLE

        topSheet.translationY = -topSheet.height.toFloat()
        topSheet.animate()
            .translationY(0f)
            .setDuration(250)
            .start()
    }

    fun closeMenu() {
        topSheet.animate()
            .translationY(-topSheet.height.toFloat())
            .setDuration(250)
            .withEndAction {
                topSheet.visibility = View.GONE
                overlay.visibility = View.GONE
            }
            .start()
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

    private fun refreshContacts() {
        val contacts = (application as App).storage.getContactList()
        val recycler = findViewById<RecyclerView>(R.id.contacts_list)
        val adapter = recycler.adapter as ContactsAdapter
        adapter.setContacts(contacts)
    }

    fun areNotificationsEnabled(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun isNotBatteryOptimised(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
}