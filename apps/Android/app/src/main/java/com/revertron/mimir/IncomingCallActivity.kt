package com.revertron.mimir

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.revertron.mimir.ui.Contact
import io.getstream.avatarview.AvatarView
import java.util.Timer //Импортировал 4 нужные мне библеотеки, начиная с этой
import java.util.TimerTask
import android.os.Handler
import android.os.Looper


class IncomingCallActivity: BaseActivity() {

    private lateinit var pubkey: ByteArray
    lateinit var contact: Contact
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var muted = false
    private var active = false
    private var onSpeaker = false
    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var timerTextView: AppCompatTextView
    private var callTimer: Timer? = null
    private var callSeconds: Int = 0
    private val timerHandler = Handler(Looper.getMainLooper())

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action == "ACTION_FINISH_CALL") {
                stopRingbackSound()
                startCallRejectedSound()
                finish()
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
        timerTextView = findViewById(R.id.timerTextView) // Инициализация TextView таймера вместе с проверкой
        //Как я это блять починил?
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



        if (action == "decline") {
            callReact(false)
            finish()
        } else if (action == "answer") {
            callReact(true)
            active = true
            outgoing = true
        }

        val id = getStorage().getContactId(pubkey)
        contact = Contact(id, pubkey, name, null, 0)

        val nameView = findViewById<AppCompatTextView>(R.id.name)
        nameView.text = name

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
            //wakeLock?.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            finish()
        }

        if (!active) {
            findViewById<View>(R.id.in_call_buttons_container).visibility = View.GONE
            if (outgoing) {
                answer.visibility = View.GONE
                spacer.visibility = View.GONE
            }
            if (outgoing) {
                startRingbackSound()
            }
        } else {
            findViewById<View>(R.id.in_call_buttons_container).visibility = View.VISIBLE
            findViewById<View>(R.id.buttons_container).visibility = View.GONE
        }

        if (!active) {
            answer.setOnClickListener {
                callReact(true)
                stopRingbackSound()
                //startCallAcceptedSound()
                findViewById<View>(R.id.in_call_buttons_container).visibility = View.VISIBLE
                findViewById<View>(R.id.buttons_container).visibility = View.GONE
                //registerMediaButton(false)
            }
            reject.setOnClickListener {
                callReact(false)
                stopRingbackSound()
                startCallRejectedSound()
                finish()
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

        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        val broadcastManager = LocalBroadcastManager.getInstance(this)
        broadcastManager
            .registerReceiver(closeReceiver, IntentFilter("ACTION_FINISH_CALL"))
        broadcastManager
            .registerReceiver(inCallReceiver, IntentFilter("ACTION_IN_CALL_START"))
    }

    private fun startRingbackSound() {
        mediaPlayer = MediaPlayer.create(this, R.raw.ring_back_tone).apply {
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
        mediaPlayer = MediaPlayer.create(this, R.raw.accept_call).apply {
            setOnCompletionListener {
                mediaPlayer?.release()
                mediaPlayer = null
            }
            isLooping = false
            start()
        }
    }

    private fun startCallRejectedSound() {
        mediaPlayer = MediaPlayer.create(this, R.raw.decline_call).apply {
            setOnCompletionListener {
                mediaPlayer?.release()
                mediaPlayer = null
            }
            isLooping = false
            start()
        }
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

    private fun startCallTimer() {
        timerTextView.visibility = View.VISIBLE
        callSeconds = 0
        updateTimerText()
        
        callTimer = Timer()
        callTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                callSeconds++
                timerHandler.post { updateTimerText() }
            }
        }, 1000, 1000)
    }

    private fun stopCallTimer() {
        callTimer?.cancel()
        callTimer = null
        timerTextView.visibility = View.GONE
    }

    private fun updateTimerText() {
        val minutes = callSeconds / 60
        val seconds = callSeconds % 60
        timerTextView.text = String.format("%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        stopCallTimer() // Останавливаем таймер при уничтожении активности
        LocalBroadcastManager.getInstance(this).unregisterReceiver(closeReceiver)
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
        startCallTimer() // Запускаем таймер при начале разговора
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
}