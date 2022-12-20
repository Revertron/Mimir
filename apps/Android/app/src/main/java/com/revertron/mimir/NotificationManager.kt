package com.revertron.mimir

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import com.revertron.mimir.storage.StorageListener

class NotificationManager(val context: Context): StorageListener {

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val messages = HashMap<Long, String>()

    override fun onContactAdded(id: Long) {

    }

    override fun onContactRemoved(id: Long) {

    }

    override fun onContactChanged(id: Long) {

    }

    override fun onMessageSent(id: Long, contactId: Long, message: String) {

    }

    override fun onMessageDelivered(id: Long, delivered: Boolean) {

    }

    override fun onMessageRead(id: Long, contactId: Long) {
        manager.cancel((contactId.toInt() shl 16))
        synchronized(messages) {
            messages.remove(contactId)
        }
    }

    override fun onMessageReceived(id: Long, contactId: Long, message: String): Boolean {
        val mes = synchronized(messages) {
            val message = if (message.length > 50) {
                message.substring(0, 50)
            } else {
                message
            }
            if (messages.containsKey(contactId)) {
                val old = messages.remove(contactId)!!
                // Prevent it from growing
                if (old.length < 30) {
                    val new = "$old\n$message"
                    messages[contactId] = new
                    new
                } else {
                    messages[contactId] = old
                    old
                }
            } else {
                messages[contactId] = message
                message
            }
        }

        val name = App.app.storage.getContactName(contactId).ifEmpty { context.getString(R.string.unknown_nickname) }
        val pubkey = App.app.storage.getContactPubkey(contactId)
        val notification = createMessageNotification(context, contactId, name, pubkey, mes)
        manager.notify((contactId.toInt() shl 16), notification)
        return true
    }

    private fun createMessageNotification(context: Context, contactId: Long, name:String, pubkey: String, message: String): Notification {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        val channelId = "Messages"
        createNotificationChannels(context, channelId, pubkey, contactId)

        val intent = Intent(context, ChatActivity::class.java).apply {
            //this.flags = Intent.FLAG_ACTIVITY_NEW_TASK/* or Intent.FLAG_ACTIVITY_CLEAR_TASK*/
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

        return NotificationCompat.Builder(context, channelId)
            .setShowWhen(true)
            .setContentTitle(name)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_mannaz_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
    }

    private fun createNotificationChannels(context: Context, channelId: String, pubkey: String, contactId: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = context.getString(R.string.channel_name_messages)
            val descriptionText = context.getString(R.string.channel_description_messages)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val parentChannel = NotificationChannel(channelId, channelName, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(parentChannel)

            val channel = NotificationChannel(pubkey, "Contact $contactId", importance).apply {
                description = "Messages with $contactId"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setConversationId(channelId, pubkey)
                }
            }
            // Register the channel with the system
            notificationManager.createNotificationChannel(channel)
        }
    }
}