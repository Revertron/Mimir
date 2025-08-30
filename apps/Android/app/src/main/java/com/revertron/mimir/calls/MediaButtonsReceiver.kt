package com.revertron.mimir.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import com.revertron.mimir.ConnectionService

class MediaButtonsReceiver: BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Log.i("MediaButtonsReceiver", "Got Intent: $intent, extras: ${intent.extras}")
        if (Intent.ACTION_MEDIA_BUTTON == intent.action) {
            val intent = Intent(ctx, ConnectionService::class.java)
                .putExtra("command", "call_answer")
            ctx.startService(intent)
            /*val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            Log.i(ConnectionService.Companion.TAG, "KeyEvent: $key")
            if (key?.action == KeyEvent.ACTION_UP && key.keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
                val intent = Intent(ctx, ConnectionService::class.java)
                    .putExtra("command", "call_answer")
                ctx.startService(intent)
            }*/
        }
    }
}