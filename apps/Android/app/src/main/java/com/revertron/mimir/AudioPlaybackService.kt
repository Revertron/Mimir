package com.revertron.mimir

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * BroadcastReceiver to handle notification dismissal.
 * Declared in manifest to receive system broadcasts reliably.
 */
class NotificationDismissedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == AudioPlaybackService.ACTION_NOTIFICATION_DISMISSED) {
            Log.d(AudioPlaybackService.TAG, "Notification dismissed, stopping playback")
            val stopIntent = Intent(context, AudioPlaybackService::class.java).apply {
                action = AudioPlaybackService.ACTION_STOP
            }
            context.startService(stopIntent)
        }
    }
}

class AudioPlaybackService : Service() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private var currentMessageId: Long = -1
    private var currentChatId: Long = -1
    private var isGroupChat: Boolean = false
    private var currentFileName: String = ""

    // Progress update handler
    private val handler = Handler(Looper.getMainLooper())
    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.isPlaying) {
                updateNotification()
                handler.postDelayed(this, PROGRESS_UPDATE_INTERVAL)
            }
        }
    }

    companion object {
        const val TAG = "AudioPlaybackService"

        const val ACTION_PLAY = "com.revertron.mimir.ACTION_PLAY"
        const val ACTION_PAUSE = "com.revertron.mimir.ACTION_PAUSE"
        const val ACTION_STOP = "com.revertron.mimir.ACTION_STOP"
        const val ACTION_TOGGLE = "com.revertron.mimir.ACTION_TOGGLE"

        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_IS_GROUP_CHAT = "is_group_chat"

        const val ACTION_PLAYBACK_STATE_CHANGED = "com.revertron.mimir.ACTION_PLAYBACK_STATE_CHANGED"
        const val ACTION_NOTIFICATION_DISMISSED = "com.revertron.mimir.ACTION_NOTIFICATION_DISMISSED"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_CURRENT_MESSAGE_ID = "current_message_id"

        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "audio_playback_channel"
        private const val PROGRESS_UPDATE_INTERVAL = 1000L
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initMediaSession()
        initPlayer()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Got action ${intent?.action}")
        when (intent?.action) {
            ACTION_PLAY -> {
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "Audio"
                val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1)
                val chatId = intent.getLongExtra(EXTRA_CHAT_ID, -1)
                val groupChat = intent.getBooleanExtra(EXTRA_IS_GROUP_CHAT, false)

                if (filePath != null) {
                    if (currentMessageId != messageId) {
                        stopPlayback()
                    }
                    currentMessageId = messageId
                    currentChatId = chatId
                    isGroupChat = groupChat
                    currentFileName = fileName
                    startPlayback(filePath)
                }
            }
            ACTION_PAUSE -> {
                pausePlayback()
            }
            ACTION_STOP -> {
                stopPlayback()
                stopSelf()
            }
            ACTION_TOGGLE -> {
                val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1)
                if (messageId != currentMessageId && messageId != -1L) {
                    val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
                    val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "Audio"
                    val chatId = intent.getLongExtra(EXTRA_CHAT_ID, -1)
                    val groupChat = intent.getBooleanExtra(EXTRA_IS_GROUP_CHAT, false)

                    if (filePath != null) {
                        stopPlayback()
                        currentMessageId = messageId
                        currentChatId = chatId
                        isGroupChat = groupChat
                        currentFileName = fileName
                        startPlayback(filePath)
                    }
                } else {
                    togglePlayback()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name_audio_playback),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description_audio_playback)
                setShowBadge(false)
                setSound(null, null)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    resumePlayback()
                }

                override fun onPause() {
                    pausePlayback()
                }

                override fun onStop() {
                    stopPlayback()
                    stopSelf()
                }
            })
            isActive = true
        }
    }

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            Log.d(TAG, "Player ready, duration: ${duration}ms")
                            updateNotification()
                            broadcastPlaybackState()
                        }
                        Player.STATE_ENDED -> {
                            Log.d(TAG, "Playback ended")
                            stopProgressUpdates()
                            mediaSession?.isActive = false
                            updatePlaybackState()
                            broadcastPlaybackState()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                            }
                            stopSelf()
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Log.d(TAG, "isPlaying changed: $isPlaying")
                    mediaSession?.isActive = isPlaying
                    updatePlaybackState()
                    updateNotification()
                    broadcastPlaybackState()
                    if (isPlaying) {
                        startProgressUpdates()
                    } else {
                        stopProgressUpdates()
                    }
                }
            })
        }
    }

    private fun startPlayback(filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "Audio file not found: $filePath")
                stopSelf()
                return
            }

            player?.let { p ->
                p.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
                p.prepare()
                p.play()
            }

            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Error starting playback", e)
            stopSelf()
        }
    }

    private fun pausePlayback() {
        player?.pause()
    }

    private fun resumePlayback() {
        player?.play()
    }

    private fun togglePlayback() {
        player?.let {
            if (it.isPlaying) {
                it.pause()
            } else {
                it.play()
            }
        }
    }

    private fun stopPlayback() {
        stopProgressUpdates()
        player?.stop()
        player?.clearMediaItems()
        currentMessageId = -1
        mediaSession?.isActive = false
        broadcastPlaybackState()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun startProgressUpdates() {
        handler.removeCallbacks(progressUpdateRunnable)
        handler.post(progressUpdateRunnable)
    }

    private fun stopProgressUpdates() {
        handler.removeCallbacks(progressUpdateRunnable)
    }

    private fun updatePlaybackState() {
        val p = player ?: return
        val state = if (p.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
                .setState(state, p.currentPosition, 1f)
                .build()
        )
    }

    private fun createNotification(): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = Intent(this, AudioPlaybackService::class.java).apply {
            action = ACTION_TOGGLE
            putExtra(EXTRA_MESSAGE_ID, currentMessageId)
        }
        val playPausePendingIntent = PendingIntent.getService(
            this, 1, playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AudioPlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val deleteIntent = Intent(this, NotificationDismissedReceiver::class.java).apply {
            action = ACTION_NOTIFICATION_DISMISSED
        }
        val deletePendingIntent = PendingIntent.getBroadcast(
            this, 3, deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val p = player
        val isPlaying = p?.isPlaying == true
        val playPauseIcon = if (isPlaying) R.drawable.ic_pause_outline else R.drawable.ic_play_outline
        val playPauseTitle = if (isPlaying) getString(R.string.pause) else getString(R.string.play)

        val currentPosition = p?.currentPosition?.toInt() ?: 0
        val duration = p?.duration?.toInt()?.takeIf { it > 0 } ?: 0
        val progressText = "${formatTime(currentPosition)} / ${formatTime(duration)}"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentFileName)
            .setContentText(progressText)
            .setSmallIcon(R.drawable.ic_music_note_outline)
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setProgress(duration, currentPosition, false)
            .addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)
            .addAction(R.drawable.ic_stop_outline, getString(R.string.stop), stopPendingIntent)
            .setOngoing(isPlaying)
            .setDeleteIntent(deletePendingIntent)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun broadcastPlaybackState() {
        val intent = Intent(ACTION_PLAYBACK_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_PLAYING, player?.isPlaying == true)
            putExtra(EXTRA_CURRENT_MESSAGE_ID, currentMessageId)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun formatTime(millis: Int): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        stopProgressUpdates()
        player?.release()
        player = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
