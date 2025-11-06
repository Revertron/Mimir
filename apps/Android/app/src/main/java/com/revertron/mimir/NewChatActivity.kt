package com.revertron.mimir

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.core.graphics.scale
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.ByteArrayOutputStream

/**
 * Activity for creating a new group chat via MediatorClient.
 *
 * Flow:
 * 1. User fills in group name, description, and optionally selects an avatar
 * 2. On "Create Chat" button click:
 *    - Connects to mediator server
 *    - Authenticates with user's key pair
 *    - Calls createChat() with group info
 *    - On success, opens GroupChatActivity with the new chat
 */
class NewChatActivity : BaseActivity() {

    companion object {
        const val TAG = "NewChatActivity"
        private const val MAX_NAME_LENGTH = 20
        private const val MAX_DESCRIPTION_LENGTH = 200
        private const val MAX_AVATAR_SIZE = 200 * 1024 // 200KB
    }

    private lateinit var avatarImage: AppCompatImageView
    private lateinit var nameEdit: AppCompatEditText
    private lateinit var descriptionEdit: AppCompatEditText
    private lateinit var nameCounter: AppCompatTextView
    private lateinit var descriptionCounter: AppCompatTextView
    private lateinit var createButton: AppCompatButton
    private lateinit var progressOverlay: FrameLayout
    private lateinit var progressText: AppCompatTextView

    private var avatarBitmap: Bitmap? = null
    private var avatarBytes: ByteArray? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleAvatarSelection(it) }
    }

    private val mediatorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "ACTION_MEDIATOR_CHAT_CREATED" -> {
                    val chatId = intent.getLongExtra("chat_id", 0)
                    val name = intent.getStringExtra("name")
                    val description = intent.getStringExtra("description")
                    val mediatorAddress = intent.getStringExtra("mediator_address")

                    showProgress(false)
                    Toast.makeText(this@NewChatActivity, R.string.chat_created_successfully, Toast.LENGTH_SHORT).show()

                    // Open the new group chat
                    val chatIntent = Intent(this@NewChatActivity, GroupChatActivity::class.java)
                    chatIntent.putExtra(GroupChatActivity.EXTRA_CHAT_ID, chatId)
                    chatIntent.putExtra(GroupChatActivity.EXTRA_CHAT_NAME, name)
                    chatIntent.putExtra(GroupChatActivity.EXTRA_CHAT_DESCRIPTION, description)
                    chatIntent.putExtra(GroupChatActivity.EXTRA_MEMBER_COUNT, 1)
                    chatIntent.putExtra(GroupChatActivity.EXTRA_IS_OWNER, true)
                    chatIntent.putExtra(GroupChatActivity.EXTRA_MEDIATOR_ADDRESS, mediatorAddress)
                    startActivity(chatIntent, animFromRight.toBundle())
                    finish()
                }
                "ACTION_MEDIATOR_ERROR" -> {
                    val operation = intent.getStringExtra("operation")
                    val error = intent.getStringExtra("error")

                    if (operation == "create_chat") {
                        showProgress(false)
                        val message = error ?: getString(R.string.failed_to_create_chat)
                        Toast.makeText(this@NewChatActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_chat)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupViews()
        setupListeners()
        registerBroadcastReceivers()
    }

    private fun registerBroadcastReceivers() {
        val filter = IntentFilter().apply {
            addAction("ACTION_MEDIATOR_CHAT_CREATED")
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
        createButton = findViewById(R.id.create_button)
        progressOverlay = findViewById(R.id.progress_overlay)
        progressText = findViewById(R.id.progress_text)

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

        // Create button
        createButton.setOnClickListener {
            validateAndCreateChat()
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
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap == null) {
                Toast.makeText(this, R.string.error_loading_image, Toast.LENGTH_SHORT).show()
                return
            }

            // Resize and compress image
            val resized = resizeImage(bitmap, 512, 512)
            val compressed = compressBitmap(resized, MAX_AVATAR_SIZE)

            if (compressed.size > MAX_AVATAR_SIZE) {
                Toast.makeText(this, R.string.avatar_too_large, Toast.LENGTH_SHORT).show()
                return
            }

            avatarBitmap = resized
            avatarBytes = compressed
            avatarImage.setImageBitmap(resized)
            avatarImage.setPadding(0, 0, 0, 0)

            Log.i(TAG, "Avatar selected: ${compressed.size} bytes")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading avatar", e)
            Toast.makeText(this, R.string.error_loading_image, Toast.LENGTH_SHORT).show()
        }
    }

    private fun resizeImage(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }

        val ratio = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()

        return bitmap.scale(newWidth, newHeight)
    }

    private fun compressBitmap(bitmap: Bitmap, maxSize: Int): ByteArray {
        var quality = 90
        var compressed: ByteArray

        do {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            compressed = stream.toByteArray()
            quality -= 10
        } while (compressed.size > maxSize && quality > 10)

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

        if (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_LENGTH) {
            Toast.makeText(this, R.string.group_name_too_long, Toast.LENGTH_SHORT).show()
            nameEdit.requestFocus()
            return
        }

        // Validate description
        if (description.toByteArray(Charsets.UTF_8).size > MAX_DESCRIPTION_LENGTH) {
            Toast.makeText(this, R.string.group_description_too_long, Toast.LENGTH_SHORT).show()
            descriptionEdit.requestFocus()
            return
        }

        // Proceed with chat creation
        createChat(name, description, avatarBytes)
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

    private fun showProgress(show: Boolean) {
        mainHandler.post {
            progressOverlay.visibility = if (show) View.VISIBLE else View.GONE
            createButton.isEnabled = !show
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