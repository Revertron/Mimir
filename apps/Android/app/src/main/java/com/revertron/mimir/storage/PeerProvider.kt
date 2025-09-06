package com.revertron.mimir.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlin.text.isNotEmpty
import kotlin.text.lines

class PeerProvider(val context: Context) {
    val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    fun getPeers(): List<String> {
        val useDefaultPeers = prefs.getBoolean("defaultPeers", true)
        if (!useDefaultPeers) {
            val peerList = prefs.getString("peers", "")
            if (peerList != null && peerList.isNotEmpty()) {
                return peerList.lines()
            }
        }
        return listOf(
            "tls://109.176.250.101:65534",
            "tls://37.205.14.171:993",
            "tcp://62.210.85.80:39565",
            "tcp://51.15.204.214:12345",
            "tcp://45.95.202.21:12403",
            "tcp://y.zbin.eu:7743"
        )
    }
}