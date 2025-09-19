package com.revertron.mimir

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager


private const val TAG = "NetworkStateCallback"

class NetworkStateCallback(val context: Context) : ConnectivityManager.NetworkCallback() {

    private var wifiLock: WifiManager.WifiLock? = null

    override fun onAvailable(network: Network) {
        super.onAvailable(network)
        Log.d(TAG, "onAvailable")
        App.app.networkChanged()

        //val isWifi = isWifiNetwork(network)

        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (preferences.getBoolean("enabled", true)) {
            //if (isWifi) acquireWifiLock()
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
        App.app.networkChanged()
        /*if (isWifiNetwork(network)) {
            releaseWifiLock()
        }*/

        Thread {
            Thread.sleep(1000)
            if (!haveNetwork(context)) {
                val intent = Intent(context, ConnectionService::class.java)
                intent.putExtra("command", "offline")
                try {
                    context.startService(intent)
                } catch (e: IllegalStateException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    }
                }
            }
        }.start()
    }

    private fun isWifiNetwork(network: Network): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(network)          // may be null → ignore
        return caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
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

    /* -------------------------------------------------------- */
    /*  wifi-lock helpers                                       */
    /* -------------------------------------------------------- */
    private fun acquireWifiLock() {
        if (wifiLock == null) {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "$TAG:wifiLock")
                .apply { setReferenceCounted(false) }
        }
        wifiLock?.takeIf { !it.isHeld }?.acquire()
        Log.d(TAG, "Wi-Fi lock acquired")
    }

    private fun releaseWifiLock() {
        wifiLock?.takeIf { it.isHeld }?.release()
        Log.d(TAG, "Wi-Fi lock released")
    }
}