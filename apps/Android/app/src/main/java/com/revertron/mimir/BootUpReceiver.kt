package com.revertron.mimir

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootUpReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BootUpReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            Log.w(TAG, "Wrong action: ${intent.action}")
        }
        Log.i(TAG, "Starting service")
        val serviceIntent = Intent(context, ConnectionService::class.java).apply {
            putExtra("command", "start")
        }
        context.startService(serviceIntent)
    }
}