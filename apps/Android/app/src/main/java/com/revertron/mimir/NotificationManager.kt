package com.revertron.mimir

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager.STREAM_NOTIFICATION
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import com.revertron.mimir.NotificationManager.Companion.INCOMING_CALL_NOTIFICATION_ID
import com.revertron.mimir.storage.StorageListener
import org.bouncycastle.util.encoders.Hex


class NotificationManager(val context: Context): StorageListener {

    companion object {
        const val TAG = "NotificationManager"
        const val INCOMING_CALL_NOTIFICATION_ID = 8840
        const val ONGOING_CALL_NOTIFICATION_ID = 8850
        private const val CALLS = "Calls"
        private const val ONGOING_CALLS = "Ongoing calls"

        // Create notification channel for calls
        fun createCallsNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager: NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (notificationManager.getNotificationChannel(CALLS) == null) {
                    val channelName = context.getString(R.string.channel_name_calls)
                    val descriptionText = context.getString(R.string.channel_description_calls)
                    val parentChannel = NotificationChannel(CALLS, channelName, NotificationManager.IMPORTANCE_HIGH).apply {
                        description = descriptionText
                        val defaultRingtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                        setSound(
                            defaultRingtoneUri,
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                    }
                    // Register the channel with the system
                    notificationManager.createNotificationChannel(parentChannel)
                }
            }
        }

        fun createCallOngoingNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager: NotificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                val name = context.getString(R.string.channel_name_calls)
                val descriptionText = context.getString(R.string.channel_description_calls)
                val importance = NotificationManager.IMPORTANCE_DEFAULT
                val channel = NotificationChannel(ONGOING_CALLS, name, importance).apply {
                    description = descriptionText
                    setSound(null, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
                    setShowBadge(true)
                }
                // Register the channel with the system
                notificationManager.createNotificationChannel(channel)
            }
        }

        fun showCallNotification(connectionService: ConnectionService, context: Context, inCall: Boolean, contact: ByteArray) {
            createCallOngoingNotificationChannel(context)
            Log.i(TAG, "showCallNotification inCall: $inCall")

            val storage = (connectionService.application as App).storage
            val contactId = storage.getContactId(contact)
            val name = storage.getContactName(contactId)

            val intent = Intent(context, IncomingCallActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra("pubkey", contact)
            intent.putExtra("name", name)
            if (inCall)
                intent.putExtra("active", true)

            val fullScreenPendingIntent = PendingIntent.getActivity(
                context, if (inCall) 1 else 2,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            var channelId = context.getString(R.string.channel_name_calls)
            if (inCall)
                channelId = ONGOING_CALLS
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_phone_outline_notification)
                .setContentTitle(context.getString(R.string.mimir_call))
                .setContentText(name)
                .setGroup(channelId)
                .apply {
                    if (!inCall) {
                        this.setPriority(NotificationCompat.PRIORITY_HIGH)
                        this.setCategory(NotificationCompat.CATEGORY_CALL)
                        val answerIntent = Intent(context, IncomingCallActivity::class.java)
                            .setAction("answer")
                            .putExtra("command", "call_answer")
                            .putExtra("pubkey", contact)
                            .putExtra("name", name)
                        val declineIntent = Intent(context, IncomingCallActivity::class.java)
                            .setAction("decline")
                            .putExtra("command", "call_decline")
                            .putExtra("pubkey", contact)
                            .putExtra("name", name)

                        this.addAction(
                            R.drawable.ic_phone_outline, context.getString(R.string.call_answer),
                            PendingIntent.getActivity(context, 1, answerIntent, PendingIntent.FLAG_IMMUTABLE)
                        )
                        this.addAction(
                            R.drawable.ic_phone_cancel_outline, context.getString(R.string.call_decline),
                            PendingIntent.getActivity(context, 2, declineIntent, PendingIntent.FLAG_IMMUTABLE)
                        )
                        this.setFullScreenIntent(fullScreenPendingIntent, true)
                    } else {
                        this.setSound(null)
                        this.setContentIntent(fullScreenPendingIntent)
                    }
                }
                .setOngoing(true)
                .build()
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.i("NotificationManager", "Permission for post notifications is not granted")
                // TODO: Consider calling ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            val id = if (inCall)
                ONGOING_CALL_NOTIFICATION_ID
            else
                INCOMING_CALL_NOTIFICATION_ID
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }

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
        if (message?.data == null) {
            return false
        }
        val mes = synchronized(messages) {
            var text = message.getText()
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
        val hashCode = pubkey.contentHashCode()
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

            val hashCode = pubkey.contentHashCode()
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