package com.revertron.mimir

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
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
import com.revertron.mimir.storage.StorageListener
import org.bouncycastle.util.encoders.Hex
import androidx.core.net.toUri
import com.revertron.mimir.net.MSG_TYPE_REACTION
import com.revertron.mimir.net.MediatorManager

/**
 * Centralized notification management for Mimir.
 *
 * This class handles all notification creation, channel management, and lifecycle
 * for the Mimir P2P messenger. It consolidates notification logic that was previously
 * scattered across NotificationManager.kt and Utils.kt.
 *
 * Notification Types:
 * - Foreground Service: Persistent notification for P2P connection service
 * - Messages: Chat message notifications with per-contact channels
 * - Calls: Incoming and ongoing audio call notifications
 * - Group Invites: Group chat invitation notifications
 * - App Updates: Update availability notifications
 */
class NotificationHelper(private val context: Context) : StorageListener {

    // ============================================================================
    // CONSTANTS
    // ============================================================================

    companion object {
        private const val TAG = "NotificationHelper"

        // Notification IDs
        const val FOREGROUND_SERVICE_ID = 1
        const val UPDATE_AVAILABLE_ID = 4477
        const val INCOMING_CALL_ID = 8840
        const val ONGOING_CALL_ID = 8850
        const val GROUP_INVITE_BASE_ID = 9000

        // Channel IDs
        private const val CHANNEL_SERVICE = "Foreground Service"
        private const val CHANNEL_UPDATES = "updates"
        private const val CHANNEL_CALLS = "Calls"
        private const val CHANNEL_ONGOING_CALLS = "Ongoing calls"
        private const val CHANNEL_GROUP_INVITES = "GroupInvites"
        private const val CHANNEL_MESSAGES = "Messages"

        // Channel ID prefixes for per-contact/group channels
        private const val CHANNEL_PREFIX_USER = "user_"
        private const val CHANNEL_PREFIX_GROUP = "group_"

        // Migration preferences
        private const val PREFS_NAME = "notification_prefs"
        private const val PREF_CHANNELS_MIGRATED = "channels_migrated_v2"

        // Protected channels that should not be deleted during migration
        private val PROTECTED_CHANNELS = setOf(
            CHANNEL_SERVICE,
            CHANNEL_UPDATES,
            CHANNEL_CALLS,
            CHANNEL_ONGOING_CALLS,
            CHANNEL_GROUP_INVITES,
            CHANNEL_MESSAGES
        )

        // Channel group IDs
        private const val CHANNEL_GROUP_USERS = "users"
        private const val CHANNEL_GROUP_GROUPS = "groups"

        // Message caching limits
        private const val MAX_MESSAGE_PREVIEW_LENGTH = 50
        private const val MAX_CACHED_MESSAGE_LENGTH = 30

        // ============================================================================
        // FOREGROUND SERVICE NOTIFICATIONS
        // ============================================================================

        /**
         * Creates notification for the ConnectionService foreground service.
         * This notification persists while the P2P service is running.
         *
         * @param context Application context
         * @param state Current connection state (Online/Offline)
         * @return Configured notification for foreground service
         */
        fun createForegroundServiceNotification(context: Context, state: State, peerHost: String, cost: Int): Notification {
            createServiceChannel(context)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val text = when (state) {
                State.Offline -> context.getText(R.string.state_offline)
                State.Online -> context.getText(R.string.state_online)
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_SERVICE)
                .setShowWhen(false)
                .setContentTitle(text)
                .setSmallIcon(R.drawable.ic_mannaz_notification)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
            if (peerHost.isNotEmpty()) {
                if (cost > 0) {
                    builder.setContentText("$peerHost ($cost)")
                } else {
                    builder.setContentText(peerHost)
                }
            }
            return builder.build()
        }

        /**
         * Creates notification channel for foreground service.
         * Uses minimum importance to avoid lock screen intrusion.
         */
        private fun createServiceChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (notificationManager.getNotificationChannel(CHANNEL_SERVICE) == null) {
                    val name = context.getString(R.string.channel_name_service)
                    val descriptionText = context.getString(R.string.channel_description_service)
                    val channel = NotificationChannel(CHANNEL_SERVICE, name, NotificationManager.IMPORTANCE_MIN).apply {
                        description = descriptionText
                        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                        setShowBadge(false)
                    }
                    notificationManager.createNotificationChannel(channel)
                }
            }
        }

        // ============================================================================
        // APP UPDATE NOTIFICATIONS
        // ============================================================================

        /**
         * Shows notification when a new app version is available.
         *
         * @param context Application context
         * @param version New version information
         * @param description Update description
         * @param apkPath Path to APK file on update server
         */
        fun showUpdateAvailableNotification(context: Context, version: Version, description: String, apkPath: String, silent: Boolean) {
            createUpdatesChannel(context)

            val intent = Intent(context, UpdateActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("version", version.toString())
                putExtra("desc", description)
                putExtra("apk", "$UPDATE_SERVER$apkPath")
            }

            val pendingIntent = PendingIntent.getActivity(
                context, 5, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val deleteIntent = Intent(context, ConnectionService::class.java).apply {
                putExtra("command", "update_dismissed")
            }
            val deletePendingIntent = PendingIntent.getService(
                context, 6, deleteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.new_version_available, version))
                .setContentText(context.getString(R.string.tap_to_see_what_s_new))
                .setGroup("new_update")
                .setAutoCancel(true)
                .setDeleteIntent(deletePendingIntent)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(silent)
                .build()

            if (hasPostNotificationsPermission(context)) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(UPDATE_AVAILABLE_ID, notification)
            } else {
                Log.i(TAG, "Permission for post notifications is not granted")
            }
        }

        /**
         * Creates notification channel for app updates.
         */
        private fun createUpdatesChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (notificationManager.getNotificationChannel(CHANNEL_UPDATES) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_UPDATES,
                        "App updates",
                        NotificationManager.IMPORTANCE_MIN
                    )
                    notificationManager.createNotificationChannel(channel)
                }
            }
        }

        // ============================================================================
        // CALL NOTIFICATIONS
        // ============================================================================

        /**
         * Initializes notification channels for audio calls.
         * Should be called during app initialization.
         *
         * @param context Application context
         */
        fun createCallChannels(context: Context) {
            createIncomingCallChannel(context)
            createOngoingCallChannel(context)
        }

        /**
         * Creates notification channel for incoming calls.
         * High importance with ringtone sound.
         */
        private fun createIncomingCallChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (notificationManager.getNotificationChannel(CHANNEL_CALLS) == null) {
                    val channelName = context.getString(R.string.channel_name_calls)
                    val descriptionText = context.getString(R.string.channel_description_calls)
                    val channel = NotificationChannel(CHANNEL_CALLS, channelName, NotificationManager.IMPORTANCE_HIGH).apply {
                        description = descriptionText
                        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                        setSound(
                            ringtoneUri,
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                    }
                    notificationManager.createNotificationChannel(channel)
                }
            }
        }

        /**
         * Creates notification channel for ongoing calls.
         * Default importance, no sound.
         */
        private fun createOngoingCallChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (notificationManager.getNotificationChannel(CHANNEL_ONGOING_CALLS) == null) {
                    val name = context.getString(R.string.channel_name_calls)
                    val descriptionText = context.getString(R.string.channel_description_calls)
                    val channel = NotificationChannel(CHANNEL_ONGOING_CALLS, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = descriptionText
                        setSound(null, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
                        setShowBadge(true)
                    }
                    notificationManager.createNotificationChannel(channel)
                }
            }
        }

        /**
         * Shows notification for incoming or ongoing audio call.
         *
         * For incoming calls:
         * - Full screen intent (shows on lock screen)
         * - Answer and Decline action buttons
         * - Repeating ringtone (FLAG_INSISTENT)
         * - High priority
         *
         * For ongoing calls:
         * - No sound
         * - Default priority
         * - Content intent to return to call
         *
         * @param connectionService Connection service instance for storage access
         * @param context Application context
         * @param inCall True for ongoing call notification, false for incoming
         * @param contact Contact's public key
         */
        fun showCallNotification(connectionService: ConnectionService, context: Context, inCall: Boolean, contact: ByteArray) {
            Log.i(TAG, "showCallNotification inCall: $inCall")

            val storage = (connectionService.application as App).storage
            val contactId = storage.getContactId(contact)
            val name = storage.getContactName(contactId)

            val intent = Intent(context, CallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("pubkey", contact)
                putExtra("name", name)
                if (inCall) putExtra("active", true)
            }

            val fullScreenPendingIntent = PendingIntent.getActivity(
                context,
                if (inCall) 1 else 2,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val channelId = if (inCall) CHANNEL_ONGOING_CALLS else CHANNEL_CALLS
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_phone_outline_notification)
                .setContentTitle(context.getString(R.string.mimir_call))
                .setContentText(name)
                .setGroup(channelId)
                .apply {
                    if (!inCall) {
                        // Incoming call configuration
                        this.setPriority(NotificationCompat.PRIORITY_HIGH)
                        this.setCategory(NotificationCompat.CATEGORY_CALL)

                        val answerIntent = Intent(context, CallActivity::class.java).apply {
                            action = "answer"
                            putExtra("command", "call_answer")
                            putExtra("pubkey", contact)
                            putExtra("name", name)
                        }
                        val declineIntent = Intent(context, CallActivity::class.java).apply {
                            action = "decline"
                            putExtra("command", "call_decline")
                            putExtra("pubkey", contact)
                            putExtra("name", name)
                        }

                        this.addAction(
                            R.drawable.ic_phone_outline,
                            context.getString(R.string.call_answer),
                            PendingIntent.getActivity(context, 1, answerIntent, PendingIntent.FLAG_IMMUTABLE)
                        )
                        this.addAction(
                            R.drawable.ic_phone_cancel_outline,
                            context.getString(R.string.call_decline),
                            PendingIntent.getActivity(context, 2, declineIntent, PendingIntent.FLAG_IMMUTABLE)
                        )
                        this.setFullScreenIntent(fullScreenPendingIntent, true)
                    } else {
                        // Ongoing call configuration
                        this.setSound(null)
                        this.setContentIntent(fullScreenPendingIntent)
                    }
                }
                .setOngoing(true)
                .build()

            if (!hasPostNotificationsPermission(context)) {
                Log.i(TAG, "Permission for post notifications is not granted")
                return
            }

            val notificationId = if (inCall) ONGOING_CALL_ID else INCOMING_CALL_ID
            // Only set FLAG_INSISTENT for incoming calls to make them repeat sound/vibration
            // Ongoing calls should be silent
            if (!inCall) {
                notification.flags = notification.flags or Notification.FLAG_INSISTENT
            }

            if (hasPostNotificationsPermission(context)) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(notificationId, notification)
            } else {
                Log.i(TAG, "Permission for post notifications is not granted")
            }
        }

        /**
         * Cancels call notifications.
         *
         * @param context Application context
         * @param incoming True to cancel incoming call notification
         * @param ongoing True to cancel ongoing call notification
         */
        fun cancelCallNotifications(context: Context, incoming: Boolean = true, ongoing: Boolean = true) {
            val notificationManager = NotificationManagerCompat.from(context)
            if (incoming) {
                notificationManager.cancel(INCOMING_CALL_ID)
            }
            if (ongoing) {
                notificationManager.cancel(ONGOING_CALL_ID)
            }
        }

        // ============================================================================
        // GROUP INVITE NOTIFICATIONS
        // ============================================================================

        /**
         * Shows notification when user receives a group chat invitation.
         *
         * @param context Application context
         * @param inviteId Database ID of the invite
         * @param chatId Group chat ID
         * @param fromPubkey Inviter's public key
         * @param timestamp Invite timestamp
         * @param chatName Group chat name
         * @param chatDescription Group chat description
         * @param chatAvatarPath Group chat avatar file path (optional)
         * @param encryptedData Encrypted invitation data
         */
        fun showGroupInviteNotification(
            context: Context,
            inviteId: Long,
            chatId: Long,
            fromPubkey: ByteArray,
            timestamp: Long,
            chatName: String,
            chatDescription: String,
            chatAvatarPath: String?,
            encryptedData: ByteArray
        ) {
            createGroupInvitesChannel(context)

            val intent = Intent(context, GroupInviteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("invite_id", inviteId)
                putExtra("chat_id", chatId)
                putExtra("from_pubkey", fromPubkey)
                putExtra("chat_name", chatName)
                putExtra("chat_description", chatDescription)
                putExtra("chat_avatar_path", chatAvatarPath)
                putExtra("encrypted_data", encryptedData)
                putExtra("timestamp", timestamp)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                inviteId.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Try to get contact name, fall back to hex string if not found
            val storage = App.app.storage
            val contactName = storage.getContactNameByPubkey(fromPubkey)
            val senderDisplay = if (contactName.isNotEmpty()) {
                contactName
            } else {
                Hex.toHexString(fromPubkey).take(8)
            }
            val contentText = context.getString(R.string.invited_by, senderDisplay)

            val notification = NotificationCompat.Builder(context, CHANNEL_GROUP_INVITES)
                .setSmallIcon(R.drawable.ic_mannaz_notification)
                .setContentTitle(context.getString(R.string.group_invite))
                .setContentText("$chatName - $contentText")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setDefaults(Notification.DEFAULT_ALL)
                .build()

            if (!hasPostNotificationsPermission(context)) {
                Log.i(TAG, "Permission for post notifications is not granted")
                return
            }

            // Use unique notification ID based on invite ID to allow multiple concurrent invites
            val notificationId = GROUP_INVITE_BASE_ID + (inviteId % 1000).toInt()
            if (hasPostNotificationsPermission(context)) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(notificationId, notification)
            } else {
                Log.i(TAG, "Permission for post notifications is not granted")
            }
        }

        /**
         * Cancels group invite notification for a specific invite.
         *
         * @param context Application context
         * @param inviteId Invite database ID
         */
        fun cancelGroupInviteNotification(context: Context, inviteId: Long) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notificationId = GROUP_INVITE_BASE_ID + (inviteId % 1000).toInt()
            notificationManager.cancel(notificationId)
        }

        /**
         * Creates notification channel for group invites.
         */
        private fun createGroupInvitesChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (notificationManager.getNotificationChannel(CHANNEL_GROUP_INVITES) == null) {
                    val channelName = "Group Invites"
                    val descriptionText = "Notifications for group chat invitations"
                    val channel = NotificationChannel(CHANNEL_GROUP_INVITES, channelName, NotificationManager.IMPORTANCE_HIGH).apply {
                        description = descriptionText
                        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        setSound(
                            ringtoneUri,
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        setShowBadge(true)
                    }
                    notificationManager.createNotificationChannel(channel)
                }
            }
        }

        // ============================================================================
        // HELPER METHODS
        // ============================================================================

        /**
         * Checks if POST_NOTIFICATIONS permission is granted.
         *
         * @param context Application context
         * @return True if permission is granted
         */
        private fun hasPostNotificationsPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val permission = Manifest.permission.POST_NOTIFICATIONS
                ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        /**
         * Generates unique notification ID for message notifications.
         * Uses bit shifting to encode contact ID into the notification ID.
         *
         * @param contactId Contact database ID
         * @return Unique notification ID for this contact
         */
        private fun getMessageNotificationId(contactId: Long): Int {
            return (contactId.toInt() shl 16)
        }

        /**
         * Creates audio attributes and URI for message notification sound.
         *
         * @param context Application context
         * @return Pair of sound URI and audio attributes
         */
        private fun createMessageAudioAttributes(context: Context): Pair<Uri, AudioAttributes> {
            val uri = "android.resource://${context.packageName}/${R.raw.message_alert}".toUri()
            val audioAttributes = AudioAttributes.Builder()
                .setLegacyStreamType(STREAM_NOTIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            return Pair(uri, audioAttributes)
        }

        /**
         * Cancels message notification for a specific contact.
         *
         * @param context Application context
         * @param contactId Contact database ID
         */
        fun cancelMessageNotification(context: Context, contactId: Long) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(getMessageNotificationId(contactId))
        }

        /**
         * Generates unique notification ID for group chat notifications.
         * Uses different encoding than regular messages to avoid conflicts.
         *
         * @param chatId Group chat ID
         * @return Unique notification ID for this group chat
         */
        private fun getGroupChatNotificationId(chatId: Long): Int {
            // Use high bits to differentiate from contact notifications
            return ((chatId and 0xFFFF).toInt() or 0x10000)
        }

        /**
         * Cancels group chat notification for a specific chat.
         *
         * @param context Application context
         * @param chatId Group chat ID
         */
        fun cancelGroupChatNotification(context: Context, chatId: Long) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(getGroupChatNotificationId(chatId))
        }

        // ============================================================================
        // NOTIFICATION CHANNEL MIGRATION
        // ============================================================================

        /**
         * Migrates notification channels to the new naming scheme.
         * Deletes all existing per-contact/per-group channels (keeping system channels)
         * so they will be recreated with proper names on first notification.
         *
         * Should be called once during app initialization.
         *
         * @param context Application context
         */
        fun migrateNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(PREF_CHANNELS_MIGRATED, false)) {
                return
            }

            Log.i(TAG, "Migrating notification channels to new naming scheme")

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channels = notificationManager.notificationChannels

            var deletedCount = 0
            for (channel in channels) {
                val channelId = channel.id
                // Keep protected system channels
                if (channelId in PROTECTED_CHANNELS) {
                    continue
                }
                // Keep channels that already use the new format
                if (channelId.startsWith(CHANNEL_PREFIX_USER) || channelId.startsWith(CHANNEL_PREFIX_GROUP)) {
                    continue
                }
                // Delete old per-contact/per-group channels
                notificationManager.deleteNotificationChannel(channelId)
                deletedCount++
                Log.d(TAG, "Deleted old channel: $channelId")
            }

            Log.i(TAG, "Migration complete. Deleted $deletedCount old channels.")

            prefs.edit().putBoolean(PREF_CHANNELS_MIGRATED, true).apply()
        }

        /**
         * Gets channel ID for a user (1-on-1 chat).
         *
         * @param pubkey Contact public key
         * @return Channel ID in format "user_{hex_pubkey}"
         */
        fun getUserChannelId(pubkey: ByteArray): String {
            return "$CHANNEL_PREFIX_USER${Hex.toHexString(pubkey)}"
        }

        /**
         * Gets channel ID for a group chat.
         *
         * @param chatId Group chat ID
         * @return Channel ID in format "group_{chatId}"
         */
        fun getGroupChannelId(chatId: Long): String {
            return "$CHANNEL_PREFIX_GROUP$chatId"
        }
    }

    // ============================================================================
    // INSTANCE MEMBERS
    // ============================================================================

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val messageCache = HashMap<Long, String>()
    private val groupMessageCache = HashMap<Long, String>()

    // ============================================================================
    // MESSAGE NOTIFICATIONS (Instance Methods)
    // ============================================================================

    /**
     * Creates notification for a received message.
     * Creates per-contact notification channels on first use.
     *
     * @param contactId Contact database ID
     * @param name Contact name
     * @param pubkey Contact public key
     * @param message Message preview text
     * @return Configured notification
     */
    private fun createMessageNotification(contactId: Long, name: String, pubkey: ByteArray, message: String): Notification {
        createMessageChannels(pubkey, name)

        val intent = Intent(context, ChatActivity::class.java).apply {
            putExtra("pubkey", pubkey)
            putExtra("name", name)
        }

        val pendingIntent = TaskStackBuilder.create(context).run {
            addNextIntentWithParentStack(intent)
            editIntentAt(0)?.putExtra("no_service", true)
            getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val (uri, _) = createMessageAudioAttributes(context)
        val channelId = getUserChannelId(pubkey)

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
            .setGroup(channelId)
            .build()
    }

    /**
     * Creates notification channels for message notifications.
     * Creates a channel group for direct messages and per-contact channels within it.
     *
     * Channel ID format: "user_{hex_pubkey}"
     * Channel name format: "Messages from {contact_name}"
     *
     * @param pubkey Contact public key
     * @param contactName Contact display name
     */
    private fun createMessageChannels(pubkey: ByteArray, contactName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val (uri, audioAttributes) = createMessageAudioAttributes(context)

            // Create channel group for direct messages (safe to call multiple times - it updates if exists)
            val groupName = context.getString(R.string.channel_group_direct_messages)
            val channelGroup = NotificationChannelGroup(CHANNEL_GROUP_USERS, groupName)
            notificationManager.createNotificationChannelGroup(channelGroup)

            // Create per-contact channel with user-friendly name, assigned to the group
            val channelId = getUserChannelId(pubkey)
            val channelName = context.getString(R.string.channel_name_messages_from_user, contactName)
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_description_messages_from_user, contactName)
                group = CHANNEL_GROUP_USERS
                setSound(uri, audioAttributes)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    // ============================================================================
    // STORAGE LISTENER IMPLEMENTATION
    // ============================================================================

    override fun onContactAdded(id: Long) {
        // No action needed
    }

    override fun onContactRemoved(id: Long) {
        // No action needed
    }

    override fun onContactChanged(id: Long) {
        // No action needed
    }

    override fun onMessageSent(id: Long, contactId: Long, type: Int, replyTo: Long) {
        // No action needed
    }

    override fun onMessageDelivered(id: Long, delivered: Boolean) {
        // No action needed
    }

    /**
     * Called when a message is marked as read.
     * Cancels the notification and clears cached message text.
     */
    override fun onMessageRead(id: Long, contactId: Long) {
        cancelMessageNotification(context, contactId)
        synchronized(messageCache) {
            messageCache.remove(contactId)
        }
    }

    /**
     * Called when all messages for a contact are marked as read.
     * Cancels the notification and clears cached message text.
     */
    override fun onAllMessagesRead(contactId: Long) {
        cancelMessageNotification(context, contactId)
        synchronized(messageCache) {
            messageCache.remove(contactId)
        }
    }

    /**
     * Called when a new message is received.
     * Creates and displays notification with message preview.
     * Caches message text for subsequent messages from same contact.
     *
     * @param id Message database ID
     * @param contactId Contact database ID
     * @return True to allow other listeners to process, false to stop propagation
     */
    override fun onMessageReceived(id: Long, contactId: Long, type: Int, replyTo: Long): Boolean {
        val message = App.app.storage.getMessage(id)

        // Skip notification for empty messages or type 2 messages
        if (message?.data == null || message.type == 2 || message.type == MSG_TYPE_REACTION) {
            return false
        }

        // Skip notification if contact is muted
        if (App.app.storage.isContactMuted(contactId)) {
            Log.d(TAG, "Skipping notification for muted contact $contactId")
            return false
        }

        // Build message text with caching for multiple messages
        val messageText = synchronized(messageCache) {
            var text = message.getText(context)

            // Truncate long messages
            text = if (text.length > MAX_MESSAGE_PREVIEW_LENGTH) {
                text.take(MAX_MESSAGE_PREVIEW_LENGTH)
            } else {
                text
            }

            // Append to cached messages or create new cache entry
            if (messageCache.containsKey(contactId)) {
                val cachedText = messageCache.remove(contactId)!!

                // Prevent cache from growing too large
                if (cachedText.length < MAX_CACHED_MESSAGE_LENGTH) {
                    val combinedText = "$cachedText\n$text"
                    messageCache[contactId] = combinedText
                    combinedText
                } else {
                    messageCache[contactId] = cachedText
                    cachedText
                }
            } else {
                messageCache[contactId] = text
                text
            }
        }

        val name = App.app.storage.getContactName(contactId).ifEmpty {
            context.getString(R.string.unknown_nickname)
        }
        val pubkey = App.app.storage.getContactPubkey(contactId)

        if (pubkey != null) {
            val notification = createMessageNotification(contactId, name, pubkey, messageText)
            notificationManager.notify(getMessageNotificationId(contactId), notification)
        }

        return true
    }

    // ============================================================================
    // GROUP CHAT NOTIFICATIONS (Instance Methods)
    // ============================================================================

    /**
     * Creates notification for a received group chat message.
     * Creates per-group notification channels on first use.
     *
     * @param chatId Group chat ID
     * @param chatName Group chat name
     * @param message Message preview text
     * @return Configured notification
     */
    private fun createGroupMessageNotification(chatId: Long, chatName: String, message: String): Notification {
        createGroupMessageChannels(chatId, chatName)

        val mediatorPubkey = MediatorManager.getDefaultMediatorPubkey()

        val intent = Intent(context, GroupChatActivity::class.java).apply {
            putExtra(GroupChatActivity.EXTRA_CHAT_ID, chatId)
            putExtra(GroupChatActivity.EXTRA_CHAT_NAME, chatName)
            putExtra(GroupChatActivity.EXTRA_MEDIATOR_ADDRESS, mediatorPubkey)
        }

        val pendingIntent = TaskStackBuilder.create(context).run {
            addNextIntentWithParentStack(intent)
            editIntentAt(0)?.putExtra("no_service", true)
            getPendingIntent(chatId.toInt(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val (uri, _) = createMessageAudioAttributes(context)
        val channelId = getGroupChannelId(chatId)

        return NotificationCompat.Builder(context, channelId)
            .setShowWhen(true)
            .setContentTitle(chatName)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_mannaz_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_LIGHTS or Notification.DEFAULT_VIBRATE)
            .setAutoCancel(true)
            .setSound(uri)
            .setGroup(channelId)
            .build()
    }

    /**
     * Creates notification channels for group chat message notifications.
     * Creates a channel group for group chats and per-group channels within it.
     *
     * Channel ID format: "group_{chatId}"
     * Channel name format: "Group {group_name}"
     *
     * @param chatId Group chat ID
     * @param groupName Group display name
     */
    private fun createGroupMessageChannels(chatId: Long, groupName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val (uri, audioAttributes) = createMessageAudioAttributes(context)

            // Create channel group for group chats (safe to call multiple times - it updates if exists)
            val groupLabel = context.getString(R.string.channel_group_groups)
            val channelGroup = NotificationChannelGroup(CHANNEL_GROUP_GROUPS, groupLabel)
            notificationManager.createNotificationChannelGroup(channelGroup)

            // Create per-group channel with user-friendly name, assigned to the group
            val channelId = getGroupChannelId(chatId)
            val channelName = context.getString(R.string.channel_name_group_chat, groupName)
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_description_group_chat, groupName)
                group = CHANNEL_GROUP_GROUPS
                setSound(uri, audioAttributes)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Called when a group chat message is marked as read.
     * Cancels the notification and clears cached message text.
     */
    override fun onGroupMessageRead(chatId: Long, id: Long) {
        cancelGroupChatNotification(context, chatId)
        synchronized(groupMessageCache) {
            groupMessageCache.remove(chatId)
        }
    }

    /**
     * Called when all messages in a group chat are marked as read.
     * Cancels the notification and clears cached message text.
     */
    override fun onAllGroupMessagesRead(chatId: Long) {
        cancelGroupChatNotification(context, chatId)
        synchronized(groupMessageCache) {
            groupMessageCache.remove(chatId)
        }
    }

    /**
     * Called when a new group message is received.
     * Creates and displays notification with message preview.
     * Caches message text for subsequent messages from same group.
     *
     * @param chatId Group chat ID
     * @param id Message database ID
     * @param contactId Contact database ID of sender (-1 if unknown)
     * @return True to allow other listeners to process, false to stop propagation
     */
    override fun onGroupMessageReceived(chatId: Long, id: Long, contactId: Long, type: Int, replyTo: Long): Boolean {
        val message = App.app.storage.getGroupMessage(chatId, id)

        // Skip notification for empty messages or type 2 messages
        if (message?.data == null || message.type == 2) {
            return false
        }

        // Get group chat info
        val groupChat = App.app.storage.getGroupChat(chatId)
        if (groupChat == null) {
            Log.w(TAG, "Group chat $chatId not found for notification")
            return false
        }

        // Skip notification if group chat is muted
        if (groupChat.muted) {
            Log.d(TAG, "Skipping notification for muted group chat $chatId")
            return false
        }

        // Get sender name
        val senderName = if (contactId > 0) {
            val nickname = App.app.storage.getGroupMemberNickname(chatId, contactId)
            nickname ?: context.getString(R.string.unknown_nickname)
        } else {
            context.getString(R.string.unknown_nickname)
        }

        // Build message text with caching for multiple messages
        val messageText = synchronized(groupMessageCache) {
            var text = message.getText(context, App.app.storage, chatId)

            // Truncate long messages
            text = if (text.length > MAX_MESSAGE_PREVIEW_LENGTH) {
                text.take(MAX_MESSAGE_PREVIEW_LENGTH)
            } else {
                text
            }

            // Format with sender name for group context
            val formattedText = "$senderName: $text"

            // Append to cached messages or create new cache entry
            if (groupMessageCache.containsKey(chatId)) {
                val cachedText = groupMessageCache.remove(chatId)!!

                // Prevent cache from growing too large
                if (cachedText.length < MAX_CACHED_MESSAGE_LENGTH) {
                    val combinedText = "$cachedText\n$formattedText"
                    groupMessageCache[chatId] = combinedText
                    combinedText
                } else {
                    groupMessageCache[chatId] = cachedText
                    cachedText
                }
            } else {
                groupMessageCache[chatId] = formattedText
                formattedText
            }
        }

        val notification = createGroupMessageNotification(chatId, groupChat.name, messageText)
        notificationManager.notify(getGroupChatNotificationId(chatId), notification)

        return true
    }
}