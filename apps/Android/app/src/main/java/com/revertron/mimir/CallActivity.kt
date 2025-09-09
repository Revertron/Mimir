package com.revertron.mimir

import android.Manifest
import android.annotation.SuppressLint
import android.app.ComponentCaller
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.revertron.mimir.net.PeerStatus
import com.revertron.mimir.ui.Contact
import io.getstream.avatarview.AvatarView
import org.bouncycastle.util.encoders.Hex

class CallActivity: BaseActivity() {

    companion object {
        const val TAG = "CallActivity"
    }

    private lateinit var pubkey: ByteArray
    lateinit var contact: Contact
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var muted = false
    private var active = false
    private var onSpeaker = false
    private var callEnded = false
    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null

    private val handler = Handler(Looper.getMainLooper())
    private var startTime = 0L
    private lateinit var timerView: AppCompatTextView

    private val tick = object : Runnable {
        override fun run() {
            val elapsed = System.currentTimeMillis() - startTime
            timerView.text = formatDuration(elapsed)
            handler.postDelayed(this, 1000)
        }
    }

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action == "ACTION_FINISH_CALL") {
                stopRingbackSound()
                startCallRejectedSound()
                callEnded = true
                finishAndRemoveTask()
            }
        }
    }

    private val peerStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action == "ACTION_PEER_STATUS") {
                if (!active) {
                    timerView.visibility = View.VISIBLE
                    val status = intent.getSerializableExtra("status")
                    val from = intent.getStringExtra("contact")
                    if (from != null && contact.pubkey.contentEquals(Hex.decode(from))) {
                        when (status) {
                            PeerStatus.Connecting -> timerView.text = getString(R.string.status_connecting)
                            PeerStatus.Connected -> timerView.text = getString(R.string.status_ringing)
                            PeerStatus.ErrorConnecting -> {
                                timerView.text = getString(R.string.status_error_connecting)
                                connect(this@CallActivity, contact.pubkey)
                            }
                        }
                    }
                }
            }
        }
    }

    private val inCallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action == "ACTION_IN_CALL_START") {
                inCallStart()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        originalOrientation = requestedOrientation

        val current = when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            Configuration.ORIENTATION_PORTRAIT  -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else                                -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        requestedOrientation = current

        setContentView(R.layout.incoming_call_activity)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        pubkey = intent.getByteArrayExtra("pubkey").apply { if (this == null) finish() }!!
        val name = intent.getStringExtra("name").apply { if (this == null) finish() }!!
        var outgoing = intent.getBooleanExtra("outgoing", false)
        active = intent.getBooleanExtra("active", false)
        val action = intent.action

        if (!outgoing && !active) {
            checkAndRequestAudioPermission()
        }

        if (action == "decline") {
            callReact(false)
            callEnded = true
            finishAndRemoveTask()
        } else if (action == "answer") {
            callReact(true)
            active = true
            outgoing = true
        }

        val id = getStorage().getContactId(pubkey)
        contact = Contact(id, pubkey, name, null, 0)

        val nameView = findViewById<AppCompatTextView>(R.id.name)
        nameView.text = name
        timerView = findViewById(R.id.timer)
        timerView.visibility = View.GONE

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

        val answer = findViewById<AppCompatImageButton>(R.id.phone_answer)
        val reject = findViewById<AppCompatImageButton>(R.id.phone_reject)
        val spacer = findViewById<View>(R.id.spacer)
        val hangup = findViewById<AppCompatImageButton>(R.id.phone_hangup)
        hangup.setOnClickListener {
            val intent = Intent(this, ConnectionService::class.java).putExtra("command", "call_hangup")
            startService(intent)
            startCallRejectedSound()
            callEnded = true
            //wakeLock?.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            finishAndRemoveTask()
        }

        if (!active) {
            findViewById<View>(R.id.in_call_buttons_container).visibility = View.GONE
            if (outgoing) {
                answer.visibility = View.GONE
                spacer.visibility = View.GONE
                startRingbackSound()
                LocalBroadcastManager.getInstance(this)
                    .registerReceiver(peerStatusReceiver, IntentFilter("ACTION_PEER_STATUS"))
                startFetchingStatuses(true)
            }
        } else {
            findViewById<View>(R.id.in_call_buttons_container).visibility = View.VISIBLE
            findViewById<View>(R.id.buttons_container).visibility = View.GONE
            startTimer()
        }

        if (!active) {
            answer.setOnClickListener {
                stopRingbackSound()
                startCallAcceptedSound()
                callReact(true)
                findViewById<View>(R.id.in_call_buttons_container).visibility = View.VISIBLE
                findViewById<View>(R.id.buttons_container).visibility = View.GONE
                startTimer()
                //registerMediaButton(false)
            }
            reject.setOnClickListener {
                callReact(false)
                stopRingbackSound()
                startCallRejectedSound()
                callEnded = true
                finishAndRemoveTask()
            }
        } else {
            findViewById<View>(R.id.in_call_buttons_container).visibility = View.VISIBLE
            findViewById<View>(R.id.buttons_container).visibility = View.GONE
        }

        val muteButton = findViewById<AppCompatImageButton>(R.id.mute_button)
        muteButton.setOnClickListener {
            muted = !muted
            muteCall(muted)
            if (muted) {
                muteButton.setImageResource(R.drawable.ic_microphone_outline)
            } else {
                muteButton.setImageResource(R.drawable.ic_microphone_off)
            }
        }
        val speakerButton = findViewById<AppCompatImageButton>(R.id.speakerphone_button)
        speakerButton.setOnClickListener {
            onSpeaker = !onSpeaker
            setSpeaker(onSpeaker)
            if (onSpeaker) {
                speakerButton.setImageResource(R.drawable.ic_volume_medium)
            } else {
                speakerButton.setImageResource(R.drawable.ic_volume_high)
            }
        }

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        powerManager = getSystemService(POWER_SERVICE) as PowerManager

        val broadcastManager = LocalBroadcastManager.getInstance(this)
        broadcastManager
            .registerReceiver(closeReceiver, IntentFilter("ACTION_FINISH_CALL"))
        broadcastManager
            .registerReceiver(inCallReceiver, IntentFilter("ACTION_IN_CALL_START"))
    }

    override fun onNewIntent(intent: Intent, caller: ComponentCaller) {
        Log.i(TAG, "onNewIntent intent=$intent")
        val pubkey = intent.getByteArrayExtra("pubkey")
        if (pubkey != null && !pubkey.contentEquals(contact.pubkey)) {
            Toast.makeText(this, R.string.call_already_ongoing, Toast.LENGTH_LONG).show()
        }
        super.onNewIntent(intent, caller)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (callEnded) {
            super.onBackPressed()
        } else {
            // treat BACK like HOME for an ongoing call
            moveTaskToBack(true)
        }
    }

    private fun startFetchingStatuses(start: Boolean) {
        val intent = Intent(this, ConnectionService::class.java)
        intent.putExtra("command", "peer_statuses")
        intent.putExtra("start", start)
        intent.putExtra("contact", contact.pubkey)
        startService(intent)
    }

    private fun startRingbackSound() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)   // ear-piece / call stream
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        mediaPlayer = MediaPlayer.create(this, R.raw.ring_back_tone, attributes, 0).apply {
            isLooping = true
            start()
        }
    }

    private fun stopRingbackSound() {
        if (mediaPlayer != null) {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null;
        }
    }

    private fun startCallAcceptedSound() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)   // ear-piece / call stream
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        mediaPlayer = MediaPlayer.create(this, R.raw.accept_call, attributes, 0).apply {
            setOnCompletionListener {
                mediaPlayer?.release()
                mediaPlayer = null
            }
            isLooping = false
            start()
        }
    }

    private fun startCallRejectedSound() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)   // ear-piece / call stream
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        mediaPlayer = MediaPlayer.create(this, R.raw.decline_call, attributes, 0).apply {
            setOnCompletionListener {
                mediaPlayer?.release()
                mediaPlayer = null
            }
            isLooping = false
            start()
        }
    }

    fun startTimer() {
        timerView.visibility = View.VISIBLE
        startTime = System.currentTimeMillis()
        handler.post(tick)
    }

    fun stopTimer() {
        handler.removeCallbacks(tick)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onResume() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "mimir:proximity")
            wakeLock?.acquire()
        }
        super.onResume()
    }

    override fun onPause() {
        wakeLock?.release()
        wakeLock = null
        super.onPause()
    }

    override fun onDestroy() {
        stopTimer()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(closeReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(peerStatusReceiver)
        startFetchingStatuses(false)
        audioManager.mode = AudioManager.MODE_NORMAL
        requestedOrientation = originalOrientation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            audioManager.isSpeakerphoneOn = false
        }

        super.onDestroy()
    }

    private fun setSpeaker(speaker: Boolean) {
        if (audioManager.isBluetoothScoOn || audioManager.isBluetoothA2dpOn) {
            // Better don't mess with the channels
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            if (speaker) {
                val speaker = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) {
                    audioManager.setCommunicationDevice(speaker)
                }
            } else {
                val earpiece = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                if (earpiece != null) {
                    audioManager.setCommunicationDevice(earpiece)
                }
            }
        } else {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = speaker
        }
    }

    private fun inCallStart() {
        findViewById<View>(R.id.in_call_buttons_container).visibility = View.VISIBLE
        findViewById<View>(R.id.buttons_container).visibility = View.GONE
        stopRingbackSound()
        startCallAcceptedSound()
        startTimer()
    }

    private fun callReact(answer: Boolean) {
        val action = if (answer) { "call_answer" } else { "call_decline" }
        val intent = Intent(this, ConnectionService::class.java)
            .putExtra("command", action)
            .putExtra("pubkey", pubkey)
        startService(intent)
    }

    private fun muteCall(mute: Boolean) {
        val intent = Intent(this, ConnectionService::class.java)
            .putExtra("command", "call_mute")
            .putExtra("mute", mute)
        startService(intent)
    }

    private fun checkAndRequestAudioPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                // Already have that permission
            }

            /*shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                // If the user already declined such requests we need to show some text in dialog, rationale
            }*/

            else -> {
                // Ask for microphone permission
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }


    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (!isGranted) {
                Toast.makeText(this, R.string.toast_no_permission, Toast.LENGTH_LONG).show()
            }
        }
}