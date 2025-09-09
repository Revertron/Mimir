package com.revertron.mimir

import android.content.Context
import android.content.Intent
import android.net.*
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager


private const val TAG = "NetworkStateCallback"

class NetworkStateCallback(val context: Context) : ConnectivityManager.NetworkCallback() {

    override fun onAvailable(network: Network) {
        super.onAvailable(network)
        Log.d(TAG, "onAvailable")

        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (preferences.getBoolean("enabled", true)) {
            Thread {
                // The message often arrives before the connection is fully established
                Thread.sleep(1000)
                val intent = Intent(context, ConnectionService::class.java)
                intent.putExtra("command", "online")
                try {
                    context.startService(intent)
                } catch (e: IllegalStateException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    }
                }
            }.start()
        }
    }

    override fun onLost(network: Network) {
        super.onLost(network)
        Log.d(TAG, "onLost")
    }

    fun register() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.registerNetworkCallback(request, this)
    }

    fun unregister() {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.unregisterNetworkCallback(this)
    }
}