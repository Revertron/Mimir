package com.revertron.mimir

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.Toolbar
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.ByteArrayOutputStream

/**
 * Activity for creating or editing a group chat via MediatorClient.
 *
 * Create mode flow:
 * 1. User fills in group name, description, and optionally selects an avatar
 * 2. On "Create" button click:
 *    - Connects to mediator server
 *    - Authenticates with user's key pair
 *    - Calls createChat() with group info
 *    - On success, opens GroupChatActivity with the new chat
 *
 * Edit mode flow:
 * 1. Loads existing chat info and pre-fills fields
 * 2. User modifies fields
 * 3. On "Save" button click:
 *    - Only sends changed fields to updateChatInfo()
 *    - On success, finishes activity with result
 */
class GroupChatEditActivity : BaseActivity() {

    companion object {
        const val TAG = "GroupChatEditActivity"

        // Mode constants
        const val EXTRA_MODE = "mode"
        const val MODE_CREATE = 0
        const val MODE_EDIT = 1

        // Edit mode extras
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_CHAT_NAME = "chat_name"
        const val EXTRA_CHAT_DESCRIPTION = "chat_description"
        const val EXTRA_MEDIATOR_ADDRESS = "mediator_address"

        // Constraints
        private const val MAX_NAME_LENGTH = 25
        private const val MAX_DESCRIPTION_LENGTH = 200
        private const val MAX_AVATAR_SIZE = 200 * 1024 // 200KB
    }

    private lateinit var avatarImage: AppCompatImageView
    private lateinit var nameEdit: AppCompatEditText
    private lateinit var descriptionEdit: AppCompatEditText
    private lateinit var nameCounter: AppCompatTextView
    private lateinit var descriptionCounter: AppCompatTextView
    private lateinit var actionButton: AppCompatButton
    private lateinit var progressOverlay: FrameLayout
    private lateinit var progressText: AppCompatTextView
    private lateinit var infoText: AppCompatTextView

    private var avatarBitmap: Bitmap? = null
    private var avatarBytes: ByteArray? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Mode and edit state
    private var mode: Int = MODE_CREATE
    private var chatId: Long = 0
    private var originalName: String = ""
    private var originalDescription: String = ""
    private var avatarChanged: Boolean = false

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleAvatarSelection(it) }
    }

    private val mediatorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "ACTION_MEDIATOR_CHAT_CREATED" -> {
                    val chatId = intent.getLongExtra("chat_id", 0)
                    val name = intent.getStringExtra("name")
                    val description = intent.getStringExtra("description")
                    val mediatorAddress = intent.getByteArrayExtra("mediator_address")

                    showProgress(false)
                    Toast.makeText(this@GroupChatEditActivity, R.string.chat_created_successfully, Toast.LENGTH_SHORT).show()

                    // Open the new group chat
                    val chatIntent = Intent(this@GroupChatEditActivity, GroupChatActivity::class.java)
                    chatIntent.putExtra(GroupChatActivity.EXTRA_CHAT_ID, chatId)
                    chatIntent.putExtra(GroupChatActivity.EXTRA_CHAT_NAME, name)
                    chatIntent.putExtra(GroupChatActivity.EXTRA_CHAT_DESCRIPTION, description)
                    chatIntent.putExtra(GroupChatActivity.EXTRA_IS_OWNER, true)
                    chatIntent.putExtra(GroupChatActivity.EXTRA_MEDIATOR_ADDRESS, mediatorAddress)
                    startActivity(chatIntent, animFromRight.toBundle())
                    finish()
                }
                "ACTION_MEDIATOR_CHAT_INFO_UPDATED" -> {
                    val intentChatId = intent.getLongExtra("chat_id", 0)
                    if (intentChatId == chatId) {
                        showProgress(false)
                        Toast.makeText(this@GroupChatEditActivity, R.string.chat_info_updated, Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    }
                }
                "ACTION_MEDIATOR_ERROR" -> {
                    val operation = intent.getStringExtra("operation")
                    val error = intent.getStringExtra("error")

                    if (operation == "create_chat") {
                        showProgress(false)
                        val message = error ?: getString(R.string.failed_to_create_chat)
                        Toast.makeText(this@GroupChatEditActivity, message, Toast.LENGTH_LONG).show()
                    } else if (operation == "update_chat_info") {
                        showProgress(false)
                        val message = error ?: getString(R.string.failed_to_update_chat_info)
                        Toast.makeText(this@GroupChatEditActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_chat_edit)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Determine mode
        mode = intent.getIntExtra(EXTRA_MODE, MODE_CREATE)
        chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0)

        setupViews()
        setupListeners()
        registerBroadcastReceivers()

        // Configure UI based on mode
        if (mode == MODE_EDIT) {
            setupEditMode()
        } else {
            setupCreateMode()
        }
    }

    private fun setupCreateMode() {
        supportActionBar?.title = getString(R.string.create_new_chat)
        actionButton.text = getString(R.string.create_chat)
        infoText.text = getString(R.string.create_chat_info)
    }

    private fun setupEditMode() {
        supportActionBar?.title = getString(R.string.edit_group_info)
        actionButton.text = getString(R.string.save_changes)
        infoText.text = getString(R.string.edit_chat_info)

        // Load existing data
        originalName = intent.getStringExtra(EXTRA_CHAT_NAME) ?: ""
        originalDescription = intent.getStringExtra(EXTRA_CHAT_DESCRIPTION) ?: ""

        // Pre-fill fields
        nameEdit.setText(originalName)
        descriptionEdit.setText(originalDescription)

        // Load avatar from database
        val chatInfo = getStorage().getGroupChat(chatId)
        if (chatInfo?.avatarPath != null && chatInfo.avatarPath.isNotEmpty()) {
            val avatar = getStorage().getGroupChatAvatar(chatId, 128, 8)
            avatarImage.setImageDrawable(avatar)
            avatarImage.setPadding(0, 0, 0, 0)
        }

        updateCounters()
    }

    private fun registerBroadcastReceivers() {
        val filter = IntentFilter().apply {
            addAction("ACTION_MEDIATOR_CHAT_CREATED")
            addAction("ACTION_MEDIATOR_CHAT_INFO_UPDATED")
            addAction("ACTION_MEDIATOR_ERROR")
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(mediatorReceiver, filter)
    }

    private fun setupViews() {
        avatarImage = findViewById(R.id.avatar_image)
        nameEdit = findViewById(R.id.name_edit)
        descriptionEdit = findViewById(R.id.description_edit)
        nameCounter = findViewById(R.id.name_counter)
        descriptionCounter = findViewById(R.id.description_counter)
        actionButton = findViewById(R.id.action_button)
        progressOverlay = findViewById(R.id.progress_overlay)
        progressText = findViewById(R.id.progress_text)
        infoText = findViewById(R.id.info_text)

        // Initialize counters
        updateCounters()
    }

    private fun setupListeners() {
        // Avatar selection
        findViewById<View>(R.id.avatar_edit_icon).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        avatarImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        // Text change listeners for character counters
        nameEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateCounters()
            }
        })

        descriptionEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateCounters()
            }
        })

        // Action button
        actionButton.setOnClickListener {
            if (mode == MODE_CREATE) {
                validateAndCreateChat()
            } else {
                validateAndUpdateChat()
            }
        }
    }

    private fun updateCounters() {
        val nameLength = nameEdit.text?.length ?: 0
        val descLength = descriptionEdit.text?.length ?: 0

        nameCounter.text = "$nameLength/$MAX_NAME_LENGTH"
        descriptionCounter.text = "$descLength/$MAX_DESCRIPTION_LENGTH"
    }

    private fun handleAvatarSelection(uri: Uri) {
        try {
            val bitmap = loadSquareAvatar(this.applicationContext, uri, 256)

            if (bitmap == null) {
                Toast.makeText(this, R.string.error_loading_image, Toast.LENGTH_SHORT).show()
                return
            }

            val compressed = compressBitmap(bitmap, MAX_AVATAR_SIZE)

            if (compressed.size > MAX_AVATAR_SIZE) {
                Toast.makeText(this, R.string.avatar_too_large, Toast.LENGTH_SHORT).show()
                return
            }

            avatarBitmap = bitmap
            avatarBytes = compressed
            avatarChanged = true
            val rounded = loadRoundedBitmap(this, compressed, 120, 8)
            avatarImage.setImageDrawable(rounded)
            avatarImage.setPadding(0, 0, 0, 0)

            Log.i(TAG, "Avatar selected: ${compressed.size} bytes")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading avatar", e)
            Toast.makeText(this, R.string.error_loading_image, Toast.LENGTH_SHORT).show()
        }
    }

    private fun compressBitmap(bitmap: Bitmap, maxSize: Int): ByteArray {
        var quality = 90
        var compressed: ByteArray

        do {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            compressed = stream.toByteArray()
            quality -= 5
        } while (compressed.size > maxSize && quality > 30)

        return compressed
    }

    private fun validateAndCreateChat() {
        val name = nameEdit.text.toString().trim()
        val description = descriptionEdit.text.toString().trim()

        // Validate name
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.group_name_required, Toast.LENGTH_SHORT).show()
            nameEdit.requestFocus()
            return
        }

        if (name.length > MAX_NAME_LENGTH) {
            Toast.makeText(this, R.string.group_name_too_long, Toast.LENGTH_SHORT).show()
            nameEdit.requestFocus()
            return
        }

        // Validate description
        if (description.length > MAX_DESCRIPTION_LENGTH) {
            Toast.makeText(this, R.string.group_description_too_long, Toast.LENGTH_SHORT).show()
            descriptionEdit.requestFocus()
            return
        }

        // Proceed with chat creation
        createChat(name, description, avatarBytes)
    }

    private fun validateAndUpdateChat() {
        val name = nameEdit.text.toString().trim()
        val description = descriptionEdit.text.toString().trim()

        // Validate name
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.group_name_required, Toast.LENGTH_SHORT).show()
            nameEdit.requestFocus()
            return
        }

        if (name.length > MAX_NAME_LENGTH) {
            Toast.makeText(this, R.string.group_name_too_long, Toast.LENGTH_SHORT).show()
            nameEdit.requestFocus()
            return
        }

        // Validate description
        if (description.length > MAX_DESCRIPTION_LENGTH) {
            Toast.makeText(this, R.string.group_description_too_long, Toast.LENGTH_SHORT).show()
            descriptionEdit.requestFocus()
            return
        }

        // Check what has changed
        val nameChanged = name != originalName
        val descriptionChanged = description != originalDescription

        if (!nameChanged && !descriptionChanged && !avatarChanged) {
            Toast.makeText(this, R.string.no_changes_made, Toast.LENGTH_SHORT).show()
            return
        }

        // Proceed with chat update (only send changed fields)
        updateChat(
            name = if (nameChanged) name else null,
            description = if (descriptionChanged) description else null,
            avatar = if (avatarChanged) avatarBytes else null
        )
    }

    private fun createChat(name: String, description: String, avatar: ByteArray?) {
        showProgress(true)
        updateProgressText(R.string.creating_chat)

        // Send intent to ConnectionService to create chat
        val intent = Intent(this, ConnectionService::class.java)
        intent.putExtra("command", "mediator_create_chat")
        intent.putExtra("name", name)
        intent.putExtra("description", description)
        avatar?.let { intent.putExtra("avatar", it) }
        startService(intent)

        Log.i(TAG, "Sent create chat request to ConnectionService")
    }

    private fun updateChat(name: String?, description: String?, avatar: ByteArray?) {
        showProgress(true)
        updateProgressText(R.string.saving_changes)

        // Send intent to ConnectionService to update chat info
        val intent = Intent(this, ConnectionService::class.java)
        intent.putExtra("command", "mediator_update_chat_info")
        intent.putExtra("chat_id", chatId)
        name?.let { intent.putExtra("name", it) }
        description?.let { intent.putExtra("description", it) }
        avatar?.let { intent.putExtra("avatar", it) }
        startService(intent)

        Log.i(TAG, "Sent update chat info request to ConnectionService")
    }

    private fun showProgress(show: Boolean) {
        mainHandler.post {
            progressOverlay.visibility = if (show) View.VISIBLE else View.GONE
            actionButton.isEnabled = !show
        }
    }

    private fun updateProgressText(resId: Int) {
        mainHandler.post {
            progressText.setText(resId)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mediatorReceiver)
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
}
