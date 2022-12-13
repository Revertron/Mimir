package com.revertron.mimir

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.*

fun createServiceNotification(context: Context, state: State): Notification {
    // Create the NotificationChannel, but only on API 26+ because
    // the NotificationChannel class is new and not in the support library
    val channelId = "Foreground Service"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val name = context.getString(R.string.channel_name_service)
        val descriptionText = context.getString(R.string.channel_description_service)
        val importance = NotificationManager.IMPORTANCE_MIN
        val channel = NotificationChannel(channelId, name, importance).apply {
            description = descriptionText
            setShowBadge(false)
        }
        // Register the channel with the system
        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    val intent = Intent(context, MainActivity::class.java).apply {
        this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    val pendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    val text = when (state) {
        State.Disabled -> context.getText(R.string.state_disabled)
        State.Enabled -> context.getText(R.string.state_enabled)
    }

    return NotificationCompat.Builder(context, channelId)
        .setShowWhen(false)
        .setContentTitle(text)
        .setSmallIcon(R.drawable.ic_mannaz_notification)
        .setContentIntent(pendingIntent)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()
}

fun getYggdrasilAddress(): InetAddress? {
    val interfaces: List<NetworkInterface> = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
    } catch (e: java.lang.Exception) {
        return null
    }

    for (i in interfaces) {
        if (!i.isUp || i.isLoopback) continue

        for (addr in i.inetAddresses) {
            val bytes = addr.address
            if (bytes.size > 4 && (bytes[0] == 0x2.toByte() || bytes[0] == 0x3.toByte())) {
                Log.d("Utils", "Found Ygg IP $addr")
                return addr
            }
        }
    }
    return null
}

fun randomString(length: Int): String {
    val characters = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    return (1..length)
        .map { characters.random() }
        .joinToString("")
}

fun randomBytes(length: Int): ByteArray {
    val buf = ByteArray(length)
    val random = Random(System.currentTimeMillis())
    random.nextBytes(buf)
    return buf
}

fun validPublicKey(text: String): Boolean {
    try {
        val key = Ed25519PublicKeyParameters(Hex.decode(text))
        Log.d(MainActivity.TAG, "Got valid public key $key")
        return true
    } catch (e: IllegalArgumentException) {
        Log.d(MainActivity.TAG, "Wrong public key $text")
        return false
    }
}

enum class State {
    Disabled, Enabled;
}