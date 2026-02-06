package com.revertron.mimir

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.File

class AudioPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var currentMessageId: Long = -1
    private var currentChatId: Long = -1
    private var isGroupChat: Boolean = false
    private var pendingPlayback: PendingPlayback? = null

    private data class PendingPlayback(val filePath: String, val fileName: String)

    companion object {
        const val TAG = "AudioPlaybackService"

        const val ACTION_PLAY = "com.revertron.mimir.ACTION_PLAY"
        const val ACTION_PAUSE = "com.revertron.mimir.ACTION_PAUSE"
        const val ACTION_STOP = "com.revertron.mimir.ACTION_STOP"
        const val ACTION_TOGGLE = "com.revertron.mimir.ACTION_TOGGLE"
        const val ACTION_CLOSE = "com.revertron.mimir.CLOSE"

        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_IS_GROUP_CHAT = "is_group_chat"

        const val ACTION_PLAYBACK_STATE_CHANGED = "com.revertron.mimir.ACTION_PLAYBACK_STATE_CHANGED"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_CURRENT_MESSAGE_ID = "current_message_id"
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)

        val player = ExoPlayer.Builder(this).build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "isPlaying changed: $isPlaying")
                broadcastPlaybackState(isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(TAG, "Playback state changed: $playbackState")
                if (playbackState == Player.STATE_ENDED) {
                    Log.d(TAG, "Playback ended")
                    currentMessageId = -1
                    broadcastPlaybackState(false)
                }
            }
        })

        val closeButton = CommandButton.Builder(CommandButton.ICON_STOP)
            .setDisplayName(getString(R.string.close))
            .setCustomIconResId(R.drawable.ic_close)
            .setSessionCommand(SessionCommand(ACTION_CLOSE, Bundle.EMPTY))
            .build()

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(MediaSessionCallback(this))
            .setMediaButtonPreferences(listOf(closeButton))
            .setSessionActivity(getMainPendingIntent(this))
            .build()

        // Set up notification provider
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this).build()
        notificationProvider.setSmallIcon(R.drawable.ic_mannaz_notification)
        setMediaNotificationProvider(notificationProvider)

        // Create a MediaController connected to our own session
        val sessionToken = SessionToken(this, ComponentName(this, AudioPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                Log.d(TAG, "MediaController connected")
                // If there's pending playback, start it now
                pendingPlayback?.let { pending ->
                    playFile(pending.filePath, pending.fileName)
                    pendingPlayback = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect MediaController", e)
            }
        }, MoreExecutors.directExecutor())
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved")
        val player = mediaSession?.player
        if (isPlaybackOngoing) {
            stopPlaybackAndService()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_PLAY, ACTION_TOGGLE -> {
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "Audio"
                val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1)
                val chatId = intent.getLongExtra(EXTRA_CHAT_ID, -1)
                val groupChat = intent.getBooleanExtra(EXTRA_IS_GROUP_CHAT, false)

                if (filePath != null) {
                    val controller = mediaController
                    if (controller != null && controller.isConnected) {
                        if (intent.action == ACTION_TOGGLE && messageId == currentMessageId && controller.mediaItemCount > 0) {
                            // Toggle existing playback
                            if (controller.isPlaying) {
                                controller.pause()
                            } else {
                                controller.play()
                            }
                        } else {
                            // Start new playback
                            currentMessageId = messageId
                            currentChatId = chatId
                            isGroupChat = groupChat
                            playFile(filePath, fileName)
                        }
                    } else {
                        // Controller not ready yet, save for later
                        Log.d(TAG, "Controller not ready, saving pending playback")
                        currentMessageId = messageId
                        currentChatId = chatId
                        isGroupChat = groupChat
                        pendingPlayback = PendingPlayback(filePath, fileName)
                    }
                }
            }
            ACTION_PAUSE -> {
                mediaController?.pause()
            }
            ACTION_STOP -> {
                stopPlaybackAndService()
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    private fun playFile(filePath: String, fileName: String) {
        val controller = mediaController
        if (controller == null || !controller.isConnected) {
            Log.e(TAG, "MediaController not connected")
            pendingPlayback = PendingPlayback(filePath, fileName)
            return
        }

        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "File not found: $filePath")
            return
        }

        val (title, artist, album, albumArt) = extractMediaMetadata(file)

        val metadata = MediaMetadata.Builder()
            .setTitle(title ?: fileName)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkData(albumArt, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(filePath)
            .setUri(file.toURI().toString())
            .setMediaMetadata(metadata)
            .build()

        controller.setMediaItem(mediaItem)
        controller.prepare()
        controller.play()
        Log.d(TAG, "Started playback via MediaController: $fileName")
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaController = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun getMainPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return pendingIntent
    }

    private data class AudioMetadata(
        val title: String?,
        val artist: String?,
        val album: String?,
        val albumArt: ByteArray?
    )

    private fun extractMediaMetadata(file: File): AudioMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val albumArt = retriever.embeddedPicture

            AudioMetadata(title, artist, album, albumArt)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract metadata from ${file.name}", e)
            AudioMetadata(null, null, null, null)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release MediaMetadataRetriever", e)
            }
        }
    }

    private fun broadcastPlaybackState(isPlaying: Boolean) {
        Log.d(TAG, "Broadcasting playback state: isPlaying=$isPlaying, messageId=$currentMessageId")
        val intent = Intent(ACTION_PLAYBACK_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_PLAYING, isPlaying)
            putExtra(EXTRA_CURRENT_MESSAGE_ID, currentMessageId)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private class MediaSessionCallback(val context: Context) : MediaSession.Callback {
        @OptIn(UnstableApi::class)
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val closeCommand = SessionCommand(ACTION_CLOSE, Bundle.EMPTY)
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_PLAY, Bundle.EMPTY))
                .add(closeCommand)
                .build()

            val closeButton = CommandButton.Builder(R.drawable.ic_close)
                .setDisplayName(context.getString(R.string.close))
                .setCustomIconResId(R.drawable.ic_close)
                .setSessionCommand(closeCommand)
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setMediaButtonPreferences(listOf(closeButton))
                .build()
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            Log.d(TAG, "onDisconnected")
            val service = context as? AudioPlaybackService
            service?.stopPlaybackAndService()
            super.onDisconnected(session, controller)
        }

        @OptIn(UnstableApi::class)
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_CLOSE) {
                Log.d(TAG, "Close command received")
                val service = context as? AudioPlaybackService
                service?.stopPlaybackAndService()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
    }

    private fun stopPlaybackAndService() {
        Log.d(TAG, "stopPlaybackAndService")
        mediaController?.stop()
        mediaController?.clearMediaItems()
        currentMessageId = -1
        if (mediaSession != null) {
            removeSession(mediaSession!!)
        }
        broadcastPlaybackState(false)
        stopSelf()
    }
}
