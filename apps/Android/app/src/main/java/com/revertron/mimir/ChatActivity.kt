package com.revertron.mimir

import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.media.MediaPlayer
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.util.Log
import android.view.*
import android.view.animation.AnimationUtils
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import androidx.appcompat.widget.*
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.storage.StorageListener
import com.revertron.mimir.ui.Contact
import com.revertron.mimir.ui.MessageAdapter
import io.getstream.avatarview.AvatarView
import org.json.JSONObject
import org.json.JSONException
import org.bouncycastle.util.encoders.Hex

class ChatActivity : BaseActivity(), Toolbar.OnMenuItemClickListener, StorageListener, View.OnClickListener {

    companion object {
        const val TAG = "ChatActivity"
        const val PICK_IMAGE_REQUEST_CODE = 123
    }

    lateinit var contact: Contact
    lateinit var replyPanel: LinearLayoutCompat
    lateinit var replyName: AppCompatTextView
    private lateinit var replyText: AppCompatTextView
    private lateinit var attachmentPanel: ConstraintLayout
    private lateinit var attachmentPreview: AppCompatImageView
    var replyTo = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)

        val pubkey = intent.getByteArrayExtra("pubkey")
        val name = intent.getStringExtra("name")
        
        if (pubkey == null || name == null) {
            Toast.makeText(this, "Ошибка: не переданы данные контакта", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val id = getStorage().getContactId(pubkey)
        contact = Contact(id, pubkey, name, null, 0, false) // По умолчанию отключено

        findViewById<AppCompatTextView>(R.id.title).text = contact.name
        supportActionBar?.title = ""
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val avatar = findViewById<AvatarView>(R.id.avatar)
        val initials = getInitials(contact)
        avatar.avatarInitials = initials
        val avatarColor = getAvatarColor(contact.pubkey)
        avatar.avatarInitialsBackgroundColor = avatarColor
        if (isColorDark(avatarColor)) {
            avatar.avatarInitialsTextColor = 0xFFFFFFFF.toInt()
        } else {
            avatar.avatarInitialsTextColor = 0xFF000000.toInt()
        }

        replyPanel = findViewById(R.id.reply_panel)
        replyPanel.visibility = View.GONE
        replyName = findViewById(R.id.reply_contact_name)
        replyText = findViewById(R.id.reply_text)
        findViewById<AppCompatImageView>(R.id.reply_close).setOnClickListener {
            replyPanel.visibility = View.GONE
            replyTo = 0L
        }

        val editText = findViewById<AppCompatEditText>(R.id.message_edit)
        val sendButton = findViewById<AppCompatImageButton>(R.id.send_button)
        
        // Добавляем TextWatcher для отслеживания изменений в поле ввода
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateSendButton(sendButton, editText.text?.isNotEmpty() == true)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        
        // Инициализация attachmentPanel
        attachmentPanel = findViewById(R.id.attachment)
        attachmentPanel.visibility = View.GONE
        attachmentPreview = findViewById(R.id.attachment_image)
        val attachmentCancel = findViewById<AppCompatImageView>(R.id.attachment_cancel)
        attachmentCancel.setOnClickListener {
            attachmentPreview.setImageDrawable(null)
            attachmentPanel.visibility = View.GONE
        }

        // Инициализация состояния кнопки
        updateSendButton(sendButton, editText.text?.isNotEmpty() == true)
        
        sendButton.setOnClickListener {
            val text: String = editText.text.toString().trim()
            if (text.isNotEmpty()) {
                editText.text?.clear()
                val message = JSONObject().apply {
                    put("text", text)
                }
                sendMessage(contact.pubkey, message.toString(), replyTo)
                replyPanel.visibility = View.GONE
                replyText.text = ""
                replyTo = 0L
            }
        }
        findViewById<AppCompatImageButton>(R.id.attach_button).setOnClickListener {
            selectAndSendPicture()
        }

        val adapter = MessageAdapter(getStorage(), contact.id, multiChat = false, "Me", contact.name, this, onClickOnReply(), onClickOnPicture())
        val recycler = findViewById<RecyclerView>(R.id.messages_list)
        recycler.adapter = adapter
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        getStorage().listeners.add(this)
        setupRecordButton()
    }

    private fun onClickOnReply() = fun(it: View) {
        val id = it.tag as Long
        val recycler = findViewById<RecyclerView>(R.id.messages_list)
        val adapter = recycler.adapter as MessageAdapter
        val position = adapter.getMessageIdPosition(id)
        if (position >= 0) {
            recycler.smoothScrollToPosition(position)
        }
    }

    private fun onClickOnPicture() = fun(it: View) {
        val uri = it.tag as Uri
        val intent = Intent(this, PictureActivity::class.java)
        intent.data = uri
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.menu_contact, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                overridePendingTransition(R.anim.hold_still, R.anim.slide_out_right)
                return true
            }
            R.id.contact_info -> {
                val intent = Intent(this, ContactActivity::class.java)
                intent.putExtra("pubkey", contact.pubkey)
                intent.putExtra("name", contact.name)
                startActivity(intent)
                overridePendingTransition(R.anim.slide_in_right, R.anim.hold_still)
            }
            else -> {
                Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        getStorage().listeners.remove(this)
        recordingHandler.removeCallbacks(updateDuration)
        super.onDestroy()
    }

    override fun finish() {
        try {
            super.finish()
            overridePendingTransition(R.anim.hold_still, R.anim.slide_out_right)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при завершении активности", e)
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        Log.i(TAG, "Clicked on ${item?.itemId}")
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE_REQUEST_CODE && resultCode == RESULT_OK) {
            if (data == null || data.data == null) {
                Log.e(TAG, "Error getting picture")
                return
            }
            val selectedPictureUri = data.data!!
            if (selectedPictureUri.length(this) > PICTURE_MAX_SIZE) {
                Toast.makeText(this, getString(R.string.too_big_picture), Toast.LENGTH_SHORT).show()
                return
            }
            val message = prepareFileForMessage(this, selectedPictureUri)
            Log.i(TAG, "File message for $selectedPictureUri is $message")
            if (message != null) {
                val fileName = message.getString("name")
                val preview = getImagePreview(this, fileName, 512, 80)
                attachmentPreview.setImageBitmap(preview)
                attachmentPanel.visibility = View.VISIBLE
                val fileMessage = JSONObject().apply {
                    put("type", "image")
                    put("file", message)
                    put("text", "Image attachment")
                }
                sendMessage(contact.pubkey, "", replyTo, fileMessage)
            }
        }
    }

    private fun sendMessage(pubkey: ByteArray, text: String, replyTo: Long, attachment: JSONObject? = null) {
        var currentAttachment = attachment
        try {
            // Проверяем, есть ли что отправлять
            if (text.trim().isEmpty() && currentAttachment == null) {
                Log.w(TAG, "Attempt to send empty message")
                Toast.makeText(this, R.string.message_empty_error, Toast.LENGTH_SHORT).show()
                return
            }

            // Проверяем общее состояние соединения
            if (!ConnectionService.isConnected()) {
                Log.w(TAG, "No active connection")
                Toast.makeText(this, R.string.no_connection_with_contact, Toast.LENGTH_SHORT).show()
                return
            }

            // Проверяем вложения
            if (currentAttachment != null) {
                val attachmentType = currentAttachment.optString("type")
                when (attachmentType) {
                    "voice" -> {
                        // Проверяем настройки получателя
                        if (!contact.voiceMessagesEnabled) {
                            Toast.makeText(this, R.string.recipient_voice_messages_disabled, Toast.LENGTH_SHORT).show()
                            audioFile?.delete()
                            audioFile = null
                            // Показываем диалоговое окно с объяснением
                            AlertDialog.Builder(this)
                                .setTitle(R.string.voice_messages_disabled_title)
                                .setMessage(R.string.recipient_voice_messages_disabled_message)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                            return
                        }
                        // Проверяем настройки отправителя
                        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
                        if (!preferences.getBoolean(VOICE_MESSAGES_ENABLED, true)) {
                            Toast.makeText(this, R.string.voice_messages_disabled, Toast.LENGTH_SHORT).show()
                            audioFile?.delete()
                            audioFile = null
                            return
                        }
                    }
                    "image" -> {
                        if (currentAttachment?.has("file") != true || currentAttachment.getJSONObject("file")?.optString("name").isNullOrEmpty()) {
                            Log.e(TAG, "Invalid image attachment")
                            Toast.makeText(this, R.string.invalid_attachment_error, Toast.LENGTH_SHORT).show()
                            return
                        }
                    }
                    else -> {
                        Log.e(TAG, "Unsupported attachment type: $attachmentType")
                        Toast.makeText(this, R.string.unsupported_attachment_error, Toast.LENGTH_SHORT).show()
                        return
                    }
                }
            }

            // Проверяем вложения
            if (currentAttachment != null && currentAttachment.optString("type") == "voice") {
                // Получаем актуальные настройки из базы данных
                val voiceMessagesEnabled = getStorage().readableDatabase.query(
                    "contacts", 
                    arrayOf("voice_messages_enabled"), 
                    "id = ?", 
                    arrayOf(contact.id.toString()), 
                    null, null, null
                ).use { cursor ->
                    cursor.moveToFirst() && cursor.getInt(0) != 0
                }

                // Проверяем настройки получателя
                if (!voiceMessagesEnabled) {
                    // Создаем сообщение с флагом недоставки
                    val message = JSONObject().apply {
                        put("type", "voice")
                        put("file", currentAttachment?.getJSONObject("file"))
                        put("text", "Voice message")
                        put("error", "recipient_voice_messages_disabled")
                    }
                    
                    // Отправляем сообщение с флагом ошибки
                    val intent = Intent(this, ConnectionService::class.java).apply {
                        putExtra("command", "send")
                        putExtra("pubkey", contact.pubkey)
                        putExtra("replyTo", replyTo)
                        putExtra("type", 1)
                        putExtra("message", message.toString())
                    }
                    
                    try {
                        startService(intent)
                        Toast.makeText(this, R.string.recipient_voice_messages_disabled, Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send rejected voice message", e)
                    }
                    
                    audioFile?.delete()
                    audioFile = null
                    currentAttachment = null
                    return
                }
                
                // Проверяем настройки отправителя
                val preferences = PreferenceManager.getDefaultSharedPreferences(this)
                if (!preferences.getBoolean(VOICE_MESSAGES_ENABLED, true)) {
                    Toast.makeText(this, R.string.voice_messages_disabled, Toast.LENGTH_SHORT).show()
                    audioFile?.delete()
                    audioFile = null
                    currentAttachment = null
                    return
                }

                // Добавляем параметр voiceMessagesEnabled в сообщение
                currentAttachment.put("voiceMessagesEnabled", contact.voiceMessagesEnabled)
            }

            // Подготавливаем данные для отправки
            val intent = Intent(this, ConnectionService::class.java).apply {
                putExtra("command", "send")
                putExtra("pubkey", pubkey)
                putExtra("replyTo", replyTo)
                
                if (currentAttachment != null) {
                    putExtra("type", 1)
                    currentAttachment.put("text", text)
                    // Ensure name field is present
                    if (!currentAttachment.has("name")) {
                        val file = currentAttachment.optJSONObject("file")
                        if (file != null && file.has("name")) {
                            currentAttachment.put("name", file.getString("name"))
                        }
                    }
                    val messageJson = currentAttachment.toString()
                    Log.d(TAG, "Sending attachment message: $messageJson")
                    putExtra("message", messageJson)
                    attachmentPanel.visibility = View.GONE
                } else {
                    putExtra("type", 0) // Обычное текстовое сообщение
                    val messageJson = JSONObject().apply {
                        put("text", text)
                    }.toString()
                    Log.d(TAG, "Sending text message: $messageJson")
                    putExtra("message", messageJson)
                }
            }

            // Отправляем сообщение
            Log.d(TAG, "Starting service to send message")
            startService(intent)
            Log.i(TAG, "Message sent to service successfully")
            
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to prepare message JSON", e)
            Toast.makeText(this, R.string.message_prepare_error, Toast.LENGTH_SHORT).show()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to start service", e)
            Toast.makeText(this, R.string.message_send_error, Toast.LENGTH_SHORT).show()
            
            // Планируем повторную отправку через 5 секунд
            Handler(Looper.getMainLooper()).postDelayed({
                sendMessage(pubkey, text, replyTo, currentAttachment)
            }, 5000)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            Toast.makeText(this, R.string.message_send_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectAndSendPicture() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        startActivityForResult(intent, PICK_IMAGE_REQUEST_CODE)
    }

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false
    private lateinit var sendButton: AppCompatImageButton
    private lateinit var recordingProgress: ProgressBar
    private lateinit var recordingControls: ConstraintLayout
    private lateinit var recordingDuration: AppCompatTextView
    private lateinit var slideToCancelText: AppCompatTextView
    private lateinit var cancelRecordButton: AppCompatImageButton
    private var recordingStartTime: Long = 0
    private var initialTouchY: Float = 0f
    private val recordingHandler = Handler(Looper.getMainLooper())
    private val updateDuration = object : Runnable {
        override fun run() {
            if (isRecording) {
                val duration = (System.currentTimeMillis() - recordingStartTime) / 1000
                val minutes = duration / 60
                val seconds = duration % 60
                recordingDuration.text = String.format("%d:%02d", minutes, seconds)
                recordingHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun startRecording() {
        // Если кнопка записи отключена, значит голосовые сообщения недоступны
        if (!sendButton.isEnabled) {
            return
        }

        recordingStartTime = System.currentTimeMillis()
        
        // Проверяем, включены ли голосовые сообщения у текущего пользователя
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        if (!preferences.getBoolean(VOICE_MESSAGES_ENABLED, true)) {
            Toast.makeText(this, R.string.voice_messages_disabled, Toast.LENGTH_SHORT).show()
            return
        }

        // Проверяем, включены ли голосовые сообщения у собеседника
        if (!contact.voiceMessagesEnabled) {
            Toast.makeText(this, R.string.recipient_voice_messages_disabled, Toast.LENGTH_SHORT).show()
            // Показываем диалоговое окно с объяснением
            AlertDialog.Builder(this)
                .setTitle(R.string.voice_messages_disabled_title)
                .setMessage(R.string.recipient_voice_messages_disabled_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 0)
            return
        }

        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(48000)
            setAudioEncodingBitRate(192000)
            setMaxDuration(120000)
            setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    runOnUiThread {
                        Toast.makeText(this@ChatActivity, "Максимальная длительность записи 2 минуты", Toast.LENGTH_SHORT).show()
                    }
                    stopRecording()
                    sendVoiceMessage()
                }
                true
            }
            audioFile = File.createTempFile("voice_${System.currentTimeMillis()}", ".m4a", cacheDir)
            setOutputFile(audioFile?.absolutePath)
            try {
                prepare()
                start()
                isRecording = true
                sendButton.setImageResource(R.drawable.ic_mic)
                recordingControls.apply {
                    visibility = View.VISIBLE
                    bringToFront()
                }
                // Запускаем анимацию для всех стрелок
                val arrowAnimation = AnimationUtils.loadAnimation(this@ChatActivity, R.anim.arrow_up_animation)
                findViewById<AppCompatImageView>(R.id.swipe_arrow1).apply {
                    visibility = View.VISIBLE
                    startAnimation(arrowAnimation)
                }
                findViewById<AppCompatImageView>(R.id.swipe_arrow2).apply {
                    visibility = View.VISIBLE
                    startAnimation(arrowAnimation)
                }
                findViewById<AppCompatImageView>(R.id.swipe_arrow3).apply {
                    visibility = View.VISIBLE
                    startAnimation(arrowAnimation)
                }
                recordingHandler.post(updateDuration)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start recording", e)
                Toast.makeText(this@ChatActivity, R.string.recording_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopRecording() {
        mediaRecorder?.apply {
            try {
                if (isRecording) {
                    stop()
                }
                release()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to stop recording", e)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Failed to stop recording", e)
            }
        }
        mediaRecorder = null
        isRecording = false
        sendButton.setImageResource(R.drawable.ic_mic)
        recordingControls.visibility = View.GONE
        findViewById<AppCompatImageView>(R.id.swipe_arrow1).clearAnimation()
        findViewById<AppCompatImageView>(R.id.swipe_arrow2).clearAnimation()
        findViewById<AppCompatImageView>(R.id.swipe_arrow3).clearAnimation()
        recordingHandler.removeCallbacks(updateDuration)
    }

    private fun sendVoiceMessage() {
        // Проверяем, включены ли голосовые сообщения у получателя
        if (!contact.voiceMessagesEnabled) {
            Toast.makeText(this, R.string.recipient_voice_messages_disabled, Toast.LENGTH_SHORT).show()
            audioFile?.delete()
            audioFile = null
            // Показываем диалоговое окно с объяснением
            AlertDialog.Builder(this)
                .setTitle(R.string.voice_messages_disabled_title)
                .setMessage(R.string.recipient_voice_messages_disabled_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        // Проверяем, включены ли голосовые сообщения у текущего пользователя
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        if (!preferences.getBoolean(VOICE_MESSAGES_ENABLED, true)) {
            Toast.makeText(this, R.string.voice_messages_disabled, Toast.LENGTH_SHORT).show()
            audioFile?.delete()
            audioFile = null
            return
        }

        audioFile?.let { file ->
            val message = prepareFileForMessage(this, Uri.fromFile(file), isVoiceMessage = true)
            if (message != null) {
                val voiceMessage = JSONObject().apply {
                    put("type", "voice")
                    put("duration", (System.currentTimeMillis() - recordingStartTime) / 1000)
                    put("file", message)
                    put("text", "Voice message")
                }
                sendMessage(contact.pubkey, "", replyTo, voiceMessage)
            }
        }
        audioFile = null
    }

    private fun setupRecordButton() {
        sendButton = findViewById(R.id.send_button)
        recordingProgress = findViewById(R.id.recording_progress)
        recordingControls = findViewById(R.id.recording_controls)
        recordingDuration = findViewById(R.id.recording_duration)
        slideToCancelText = findViewById(R.id.slide_to_cancel_text)
        cancelRecordButton = findViewById(R.id.cancel_record_button)
        
        sendButton.setOnClickListener {
            val text: String = findViewById<AppCompatEditText>(R.id.message_edit).text.toString().trim()
            if (text.isNotEmpty()) {
                findViewById<AppCompatEditText>(R.id.message_edit).text?.clear()
                sendMessage(contact.pubkey, text, replyTo)
                replyPanel.visibility = View.GONE
                replyText.text = ""
                replyTo = 0L
            } else {
                sendButton.setImageResource(R.drawable.ic_mic)
            }
        }
        
        sendButton.setOnTouchListener { _, event ->
            if (findViewById<AppCompatEditText>(R.id.message_edit).text?.isEmpty() == true) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialTouchY = event.rawY
                        startRecording()
                        recordingControls.animate()
                            .translationY(-recordingControls.height.toFloat())
                            .setDuration(200)
                            .start()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaY = initialTouchY - event.rawY
                        val progress = (deltaY / 200).coerceIn(0f, 1f)
                        
                        recordingControls.translationY = -recordingControls.height * progress
                        
                        if (deltaY > 100) {
                            cancelRecordButton.setImageResource(R.drawable.ic_delete)
                        } else {
                            cancelRecordButton.setImageResource(R.drawable.ic_close)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val deltaY = initialTouchY - event.rawY
                        if (deltaY > 100) {
                            cancelRecording()
                        } else {
                            val duration = System.currentTimeMillis() - recordingStartTime
                            if (duration > 1000) {
                                stopRecording()
                                sendVoiceMessage()
                            } else {
                                cancelRecording()
                                Toast.makeText(this, R.string.recording_too_short, Toast.LENGTH_SHORT).show()
                            }
                        }
                        recordingControls.animate()
                            .translationY(0f)
                            .setDuration(200)
                            .start()
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
        
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        sendButton.isEnabled = preferences.getBoolean(VOICE_MESSAGES_ENABLED, true)
    }

    private fun cancelRecording() {
        mediaRecorder?.apply {
            try {
                if (isRecording) {
                    stop()
                }
                release()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to stop recording", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error during recording cancellation", e)
            }
        }
        mediaRecorder = null
        isRecording = false
        recordingControls.visibility = View.GONE
        findViewById<AppCompatImageView>(R.id.swipe_arrow1).clearAnimation()
        findViewById<AppCompatImageView>(R.id.swipe_arrow2).clearAnimation()
        findViewById<AppCompatImageView>(R.id.swipe_arrow3).clearAnimation()
        recordingHandler.removeCallbacks(updateDuration)
        audioFile?.delete()
        audioFile = null
        Toast.makeText(this, R.string.recording_canceled, Toast.LENGTH_SHORT).show()
    }

    interface PlaybackCallback {
        fun onPlaybackStateChanged(isPlaying: Boolean)
    }

    fun playVoiceMessage(file: File, callback: PlaybackCallback) {
        if (!file.exists() || file.length() == 0L) {
            Log.e(TAG, "Voice message file does not exist or is empty")
            Toast.makeText(this, R.string.playback_error, Toast.LENGTH_SHORT).show()
            callback.onPlaybackStateChanged(false)
            return
        }

        try {
            val mediaPlayer = MediaPlayer()
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.setOnPreparedListener { mp ->
                try {
                    mp.start()
                    callback.onPlaybackStateChanged(true)
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Error starting media player", e)
                    Toast.makeText(this@ChatActivity, R.string.playback_error, Toast.LENGTH_SHORT).show()
                    mp.release()
                    callback.onPlaybackStateChanged(false)
                }
            }
            mediaPlayer.setOnCompletionListener { mp ->
                mp.release()
                callback.onPlaybackStateChanged(false)
            }
            mediaPlayer.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                Toast.makeText(this@ChatActivity, R.string.playback_error, Toast.LENGTH_SHORT).show()
                mp.release()
                callback.onPlaybackStateChanged(false)
                true
            }
            mediaPlayer.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play voice message", e)
            Toast.makeText(this, R.string.playback_error, Toast.LENGTH_SHORT).show()
            callback.onPlaybackStateChanged(false)
        }
    }

    override fun onMessageSent(id: Long, contactId: Long) {
        runOnUiThread {
            Log.i(TAG, "Message $id sent to $contactId")
            if (contact.id == contactId) {
                val recycler = findViewById<RecyclerView>(R.id.messages_list)
                val adapter = recycler.adapter as MessageAdapter
                adapter.addMessageId(id)
                recycler.smoothScrollToPosition(adapter.itemCount)
            }
        }
    }

    override fun onMessageDelivered(id: Long, delivered: Boolean) {
        Log.i(TAG, "Message $id delivered = $delivered")
        runOnUiThread {
            val recycler = findViewById<RecyclerView>(R.id.messages_list)
            val adapter = recycler.adapter as MessageAdapter
            
            // Получаем информацию о сообщении
            val message = getStorage().getMessage(id)
            if (message != null && message.type == 1 && !delivered) {
                // Для недоставленных голосовых сообщений показываем уведомление
                Toast.makeText(
                    this@ChatActivity, 
                    R.string.recipient_voice_messages_disabled, 
                    Toast.LENGTH_LONG
                ).show()
                
                // Удаляем временный файл голосового сообщения
                try {
                    message.data?.let { data ->
                        val json = JSONObject(String(data))
                        if (json.has("file")) {
                            val file = File(cacheDir, json.getJSONObject("file").getString("name"))
                            if (file.exists()) {
                                file.delete()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting voice message file", e)
                }
            }
            
            adapter.setMessageDelivered(id, delivered)
        }
    }

    override fun onMessageReceived(id: Long, contactId: Long): Boolean {
        Log.i(TAG, "Message $id from $contactId")
        if (contact.id == contactId) {
            runOnUiThread {
                try {
                    // Получаем сообщение из хранилища
                    val message = getStorage().getMessage(id)
                    if (message == null) {
                        Log.e(TAG, "Received message $id but it's null in storage")
                        return@runOnUiThread
                    }

                    // Проверяем тип сообщения
                    when (message.type) {
                        0 -> { // Текстовое сообщение
                            val messageData = message.data
                            if (messageData == null || messageData.isEmpty()) {
                                Log.w(TAG, "Received empty text message $id")
                                return@runOnUiThread
                            }
                            val text = String(messageData, Charsets.UTF_8)
                            if (text.trim().isEmpty()) {
                                Log.w(TAG, "Received empty text content in message $id")
                                return@runOnUiThread
                            }
                        }
                        1 -> { // Сообщение с вложением
                            val messageData = message.data
                            if (messageData == null || messageData.isEmpty()) {
                                Log.w(TAG, "Received empty attachment message $id")
                                return@runOnUiThread
                            }
                            try {
                                val json = JSONObject(String(messageData, Charsets.UTF_8))
                                if (!json.has("type") || !json.has("file")) {
                                    Log.w(TAG, "Invalid attachment format in message $id")
                                    return@runOnUiThread
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse attachment in message $id", e)
                                return@runOnUiThread
                            }
                        }
                        else -> {
                            Log.w(TAG, "Unknown message type ${message.type} in message $id")
                            return@runOnUiThread
                        }
                    }

                    // Добавляем сообщение в список
                    Log.i(TAG, "Adding valid message $id")
                    val recycler = findViewById<RecyclerView>(R.id.messages_list)
                    val adapter = recycler.adapter as MessageAdapter
                    adapter.addMessageId(id)
                    recycler.smoothScrollToPosition(adapter.itemCount)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing received message $id", e)
                }
            }
            return true
        }
        return false
    }

    override fun onClick(view: View) {
        val popup = PopupMenu(this, view, Gravity.TOP or Gravity.END)
        popup.inflate(R.menu.menu_context_message)
        popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_copy -> {
                    val textview = view.findViewById<AppCompatTextView>(R.id.text)
                    val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Mimir message", textview.text)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(applicationContext,R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.menu_reply -> {
                    val id = (view.tag as Long)
                    val message = getStorage().getMessage(id)
                    replyName.text = contact.name
                    replyText.text = message?.getText()
                    replyPanel.visibility = View.VISIBLE
                    replyTo = message?.guid ?: 0L
                    Log.i(TAG, "Replying to guid $replyTo")
                    false
                }
                R.id.menu_forward -> { false }
                R.id.menu_delete -> {
                    showDeleteMessageConfirmDialog(view.tag as Long)
                    true
                }
                else -> {
                    Log.w(TAG, "Not implemented handler for menu item ${it.itemId}")
                    false
                }
            }
        }
        popup.show()
    }

    private fun updateSendButton(button: AppCompatImageButton, hasText: Boolean) {
        if (!this::attachmentPanel.isInitialized) {
            return
        }
        
        // Всегда показываем кнопку отправки, если есть текст или вложение
        if (hasText || attachmentPanel.visibility == View.VISIBLE) {
            button.setImageResource(R.drawable.ic_send_new)
            button.isEnabled = true
        } else {
            // Показываем микрофон только если голосовые сообщения включены у обоих пользователей
            val preferences = PreferenceManager.getDefaultSharedPreferences(this)
            val voiceMessagesEnabled = preferences.getBoolean(VOICE_MESSAGES_ENABLED, true)
            if (voiceMessagesEnabled && contact.voiceMessagesEnabled) {
                button.setImageResource(R.drawable.ic_mic)
                button.isEnabled = true
            } else {
                button.setImageResource(R.drawable.ic_mic)
                button.isEnabled = false
                button.alpha = 0.5f
                button.setOnClickListener {
                    if (!contact.voiceMessagesEnabled) {
                        Toast.makeText(this, R.string.recipient_voice_messages_disabled, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, R.string.voice_messages_disabled, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showDeleteMessageConfirmDialog(messageId: Long) {
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        val builder: AlertDialog.Builder = AlertDialog.Builder(wrapper)
        builder.setTitle(getString(R.string.delete_message_dialog_title))
        builder.setMessage(R.string.delete_message_dialog_text)
        builder.setIcon(R.drawable.ic_delete)
        builder.setPositiveButton(getString(R.string.menu_delete)) { _, _ ->
            (application as App).storage.deleteMessage(messageId)
            val recycler = findViewById<RecyclerView>(R.id.messages_list)
            val adapter = recycler.adapter as MessageAdapter
            adapter.deleteMessageId(messageId)
        }
        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
            dialog.cancel()
        }
        builder.show()
    }
}
