package com.revertron.mimir

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager.STREAM_NOTIFICATION
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import com.revertron.mimir.storage.StorageListener
import org.bouncycastle.util.encoders.Hex


class NotificationManager(val context: Context): StorageListener {

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val messages = HashMap<Long, String>()

    override fun onContactAdded(id: Long) {

    }

    override fun onContactRemoved(id: Long) {

    }

    override fun onContactChanged(id: Long) {

    }

    override fun onMessageSent(id: Long, contactId: Long) {

    }

    override fun onMessageDelivered(id: Long, delivered: Boolean) {

    }

    override fun onMessageRead(id: Long, contactId: Long) {
        manager.cancel((contactId.toInt() shl 16))
        synchronized(messages) {
            messages.remove(contactId)
        }
    }

    override fun onMessageReceived(id: Long, contactId: Long): Boolean {
        val message = App.app.storage.getMessage(id)
        if (message?.message == null) {
            return false
        }
        val mes = synchronized(messages) {
            var text = String(message.message)
            text = if (text.length > 50) {
                text.substring(0, 50)
            } else {
                text
            }
            if (messages.containsKey(contactId)) {
                val old = messages.remove(contactId)!!
                // Prevent it from growing
                if (old.length < 30) {
                    val new = "$old\n$text"
                    messages[contactId] = new
                    new
                } else {
                    messages[contactId] = old
                    old
                }
            } else {
                messages[contactId] = text
                text
            }
        }

        val name = App.app.storage.getContactName(contactId).ifEmpty { context.getString(R.string.unknown_nickname) }
        val pubkey = App.app.storage.getContactPubkey(contactId)
        if (pubkey != null) {
            val notification = createMessageNotification(context, contactId, name, pubkey, mes)
            manager.notify((contactId.toInt() shl 16), notification)
        }
        return true
    }

    private fun createMessageNotification(context: Context, contactId: Long, name: String, pubkey: ByteArray, message: String): Notification {
        createNotificationChannels(context, pubkey, contactId)

        val intent = Intent(context, ChatActivity::class.java).apply {
            putExtra("pubkey", pubkey)
            putExtra("name", name)
        }
        val pendingIntent: PendingIntent? = TaskStackBuilder.create(context).run {
            // Add the intent, which inflates the back stack
            addNextIntentWithParentStack(intent)
            editIntentAt(0)?.putExtra("no_service", true)
            // Get the PendingIntent containing the entire back stack
            getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val (uri, _) = createAudioAttributes(context)
        val hashCode = pubkey.toList().hashCode()
        val channelId = "Messages $hashCode"

        return NotificationCompat.Builder(context, channelId)
            .setShowWhen(true)
            .setContentTitle(name)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_mannaz_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_LIGHTS or Notification.DEFAULT_VIBRATE)
            .setAutoCancel(true)
            .setSound(uri)
            .build()
    }

    private fun createNotificationChannels(context: Context, pubkey: ByteArray, contactId: Long) {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager: NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // First of all, we remove old channels
            val hexString = Hex.toHexString(pubkey)
            notificationManager.deleteNotificationChannel(hexString)

            val (uri, audioAttributes) = createAudioAttributes(context)

            val parentChannelId = "Messages"
            val importance = NotificationManager.IMPORTANCE_HIGH
            if (notificationManager.getNotificationChannel(parentChannelId) == null) {
                val channelName = context.getString(R.string.channel_name_messages)
                val descriptionText = context.getString(R.string.channel_description_messages)
                val parentChannel = NotificationChannel(parentChannelId, channelName, importance).apply {
                    description = descriptionText
                    setSound(uri, audioAttributes)
                }
                // Register the channel with the system
                notificationManager.createNotificationChannel(parentChannel)
            }

            val hashCode = pubkey.toList().hashCode()
            val channelId = "Messages $hashCode"
            val channelName = context.getString(R.string.channel_name_messages_with_contact, contactId)
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                description = context.getString(R.string.channel_description_messages_with_contact, contactId)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setConversationId(parentChannelId, channelId)
                }
                setSound(uri, audioAttributes)
            }
            // Register the channel with the system
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createAudioAttributes(context: Context): Pair<Uri, AudioAttributes> {
        val uri = Uri.parse("android.resource://" + context.packageName + "/" + R.raw.message_alert)
        val audioAttributes = AudioAttributes.Builder()
            .setLegacyStreamType(STREAM_NOTIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        return Pair(uri, audioAttributes)
    }
}