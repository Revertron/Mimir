package com.revertron.mimir.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import com.revertron.mimir.AudioPlaybackService
import com.revertron.mimir.ConnectionService

class MediaButtonsReceiver: BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Log.i("MediaButtonsReceiver", "Got Intent: $intent, extras: ${intent.extras}")
        if (Intent.ACTION_MEDIA_BUTTON == intent.action) {
            val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_HEADSETHOOK -> {
                        // Try to toggle audio playback first
                        val audioIntent = Intent(ctx, AudioPlaybackService::class.java)
                            .setAction(AudioPlaybackService.ACTION_TOGGLE)
                        ctx.startService(audioIntent)
                    }
                    KeyEvent.KEYCODE_MEDIA_STOP -> {
                        val audioIntent = Intent(ctx, AudioPlaybackService::class.java)
                            .setAction(AudioPlaybackService.ACTION_STOP)
                        ctx.startService(audioIntent)
                    }
                    else -> {
                        // For other keys, handle call answering (original behavior)
                        val callIntent = Intent(ctx, ConnectionService::class.java)
                            .putExtra("command", "call_answer")
                        ctx.startService(callIntent)
                    }
                }
            }
        }
    }
}