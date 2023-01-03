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
import com.revertron.mimir.ui.Contact
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.DecoderException
import org.bouncycastle.util.encoders.Hex
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.*
import kotlin.math.abs

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

fun isSubnetYggdrasilAddress(address: InetAddress): Boolean {
    return address.address[0] == 0x3.toByte()
}

fun isAddressFromSubnet(address: InetAddress, subnet: InetAddress): Boolean {
    for (b in 1..7) {
        if (address.address[b] != subnet.address[b]) {
            return false
        }
    }
    return true
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
    } catch (e: DecoderException) {
        Log.d(MainActivity.TAG, "Wrong public key $text")
        return false
    }
}

/**
 * Gets current time in seconds in UTC timezone
 */
fun getUtcTime(): Long {
    val calendar = Calendar.getInstance()
    val offset = calendar.timeZone.rawOffset
    return (calendar.timeInMillis - offset) / 1000
}

fun isColorDark(color: Int): Boolean {
    val r = ((color shr 16) and 0xff) / 255.0
    val g = ((color shr 8) and 0xff) / 255.0
    val b = (color and 0xff) / 255.0
    val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
    return luminance < 0.5
}

fun getAvatarColor(pubkey: ByteArray): Int {
    val hashCode = pubkey.toList().hashCode()
    return darkColors[abs(hashCode) % darkColors.size].toInt()
}

private val darkColors = arrayOf(
    0xFF2F4F4F, // Dark slate gray
    0xFF4682B4, // Steel blue
    0xFF556B2F, // Dark olive green
    0xFFBDB76B, // Dark khaki
    0xFF8FBC8F, // Dark sea green
    0xFF66CDAA, // Medium aquamarine
    0xFF0000CD, // Medium blue
    0xFF9370DB, // Medium purple
    0xFF3CB371, // Medium sea green
    0xFF7B68EE, // Medium slate blue
    0xFF00FA9A, // Medium spring green
    0xFF48D1CC, // Medium turquoise
    0xFF6B8E23, // Olive drab
    0xFF98FB98, // Pale green
    0xFFAFEEEE, // Pale turquoise
    0xFFB8860B, // Dark goldenrod
    0xFF006400, // Dark green
    0xFFA9A9A9, // Dark grey
    0xFFFF8C00, // Dark orange
    0xFF9932CC, // Dark orchid
    0xFFE9967A, // Dark salmon
    0xFF00CED1, // Dark turquoise
    0xFF9400D3, // Dark violet
    0xFF00BFFF, // Deep sky blue
    0xFF696969, // Dim gray
    0xFF228B22, // Forest green
    0xFFFFD700, // Gold
    0xFFADFF2F, // Green yellow
    0xFFADD8E6, // Light blue
    0xFF90EE90  // Light green
)

fun getInitials(contact: Contact): String {
    val name = contact.name.trim()
    if (name.isEmpty() || name.length < 2) {
        return Hex.toHexString(contact.pubkey, 0, 1)
    }

    if (name.length == 2) {
        return name
    }

    if (name.contains(" ")) {
        val pos = name.indexOf(" ") + 1
        return name.substring(0, 1) + name.substring(pos, pos + 1)
    }

    return name.substring(0, 2)
}

enum class State {
    Disabled, Enabled;
}