package com.revertron.mimir.calls

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.revertron.mimir.ConnectionService

class MediaButtonHelper(private val context: Context) {

    companion object {
        const val TAG = "MediaButtonHelper"
    }

    private var mediaSession: MediaSessionCompat? = null
    private var receiver: BroadcastReceiver? = null

    /**
     * true  -> create MediaSession and register the receiver
     * false -> tear everything down
     */
    fun setEnabled(enable: Boolean) {
        if (enable) {
            if (mediaSession == null) {
                // 1. create MediaSession
                mediaSession = MediaSessionCompat(context, "CallSession").apply {
                    setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS)
                    isActive = true
                }

                // 2. create & register *your* receiver
                receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        Log.i(TAG, "Got intent: $intent")
                        if (Intent.ACTION_MEDIA_BUTTON == intent.action) {
                            val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                            if (key?.action == KeyEvent.ACTION_UP &&
                                key.keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
                                handleHeadsetHook()
                            }
                        }
                    }
                }
                val filter = IntentFilter(Intent.ACTION_MEDIA_BUTTON)
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

                // 3. tell the session to dispatch to our receiver
                mediaSession?.setMediaButtonReceiver(
                    PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                            // target our own receiver
                            component = ComponentName(context, receiver!!.javaClass)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
        } else {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null

            receiver?.let { context.unregisterReceiver(it) }
            receiver = null
        }
    }

    private fun handleHeadsetHook() {
        val intent = Intent(context, ConnectionService::class.java).putExtra("command", "call_answer")
        context.startService(intent)
    }
}