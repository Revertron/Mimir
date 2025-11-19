package com.revertron.mimir.storage

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDoneException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.util.Log
import androidx.core.database.getBlobOrNull
import androidx.core.database.getStringOrNull
import com.revertron.mimir.NotificationHelper
import com.revertron.mimir.R
import com.revertron.mimir.formatDuration
import com.revertron.mimir.getImageExtensionOrNull
import com.revertron.mimir.getUtcTime
import com.revertron.mimir.loadRoundedAvatar
import com.revertron.mimir.randomString
import com.revertron.mimir.ui.Contact
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.KeyGenerationParameters
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.Date
import java.util.Random

class SqlStorage(val context: Context): SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val TAG = "SqlStorage"
        // If we change the database schema, we must increment the database version.
        const val DATABASE_VERSION = 13
        const val DATABASE_NAME = "data.db"
        const val CREATE_ACCOUNTS = "CREATE TABLE accounts (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, privkey TEXT, pubkey TEXT, client INTEGER, info TEXT, avatar TEXT, updated INTEGER)"
        const val CREATE_CONTACTS = "CREATE TABLE contacts (id INTEGER PRIMARY KEY AUTOINCREMENT, pubkey BLOB, name TEXT, info TEXT, avatar TEXT, updated INTEGER, renamed BOOL, last_seen INTEGER)"
        const val CREATE_IPS = "CREATE TABLE ips (id INTEGER, client INTEGER, address TEXT, port INTEGER DEFAULT 5050, priority INTEGER DEFAULT 3, expiration INTEGER DEFAULT 3600)"
        const val CREATE_MESSAGES = "CREATE TABLE messages (id INTEGER PRIMARY KEY, contact INTEGER, guid INTEGER, replyTo INTEGER, incoming BOOL, delivered BOOL, read BOOL, time INTEGER, edit INTEGER, type INTEGER, message BLOB)"

        // Group chat tables (version 8+)
        const val CREATE_GROUP_CHATS = "CREATE TABLE group_chats (chat_id INTEGER PRIMARY KEY, name TEXT NOT NULL, description TEXT, avatar TEXT, mediator_pubkey BLOB NOT NULL, owner_pubkey BLOB NOT NULL, shared_key BLOB NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, last_message_time INTEGER DEFAULT 0, unread_count INTEGER DEFAULT 0, muted BOOL DEFAULT 0)"
        const val CREATE_GROUP_INVITES = "CREATE TABLE group_invites (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL, from_pubkey BLOB NOT NULL, timestamp INTEGER NOT NULL, chat_name TEXT NOT NULL, chat_description TEXT, chat_avatar TEXT, encrypted_data BLOB NOT NULL, status INTEGER DEFAULT 0)"

        // Reactions table (version 12+)
        // Stores reactions for both personal and group chat messages
        // For personal chats: message_guid is the guid from messages table, chat_id is NULL
        // For group chats: message_guid is the guid from messages_<chatId> table, chat_id is the group chat ID
        const val CREATE_REACTIONS = "CREATE TABLE reactions (id INTEGER PRIMARY KEY AUTOINCREMENT, message_guid INTEGER NOT NULL, chat_id INTEGER, reactor_pubkey BLOB NOT NULL, emoji TEXT NOT NULL, timestamp INTEGER NOT NULL, UNIQUE(message_guid, chat_id, reactor_pubkey, emoji))"

        // Pending reactions table (version 13+)
        // Stores reactions that failed to send and need to be retried
        // contact_pubkey is the recipient for personal chats, NULL for group chats
        const val CREATE_PENDING_REACTIONS = "CREATE TABLE pending_reactions (id INTEGER PRIMARY KEY AUTOINCREMENT, message_guid INTEGER NOT NULL, chat_id INTEGER, contact_pubkey BLOB, emoji TEXT NOT NULL, add_reaction BOOL NOT NULL, timestamp INTEGER NOT NULL)"
    }

    data class Message(
        val id: Long,
        val contact: Long,
        val guid: Long,
        val replyTo: Long,
        val incoming: Boolean,
        var delivered: Boolean,
        val read: Boolean,
        val time: Long,
        val edit: Long,
        val type: Int,
        val data: ByteArray?
    ) {
        fun getText(context: Context): String {
            return if (data != null) {
                when (type) {
                    1 -> {
                        val json = JSONObject(String(data))
                        val text = json.getString("text")
                        if (text.isEmpty()) {
                            json.getString("name")
                        } else {
                            text
                        }
                    }
                    2 -> {
                        val callDuration = edit - time
                        val text = formatDuration(callDuration)
                        context.getString(R.string.audio_call_item, text)
                    }
                    else -> {
                        String(data)
                    }
                }
            } else {
                "<Empty>"
            }
        }
    }

    val listeners = mutableListOf<StorageListener>()
    private val notificationManager = NotificationHelper(context)
    @SuppressLint("HardwareIds")
    private val androidId: Int = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.hashCode() ?: 0
    private var myPublicKey: ByteArray? = null

    init {
        // Register NotificationHelper as a listener to receive storage events
        listeners.add(notificationManager)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_ACCOUNTS)
        db.execSQL(CREATE_CONTACTS)
        db.execSQL(CREATE_IPS)
        db.execSQL(CREATE_MESSAGES)
        db.execSQL(CREATE_GROUP_CHATS)
        db.execSQL(CREATE_GROUP_INVITES)
        db.execSQL(CREATE_REACTIONS)
        db.execSQL(CREATE_PENDING_REACTIONS)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "Upgrading from $oldVersion to $newVersion")

        if (newVersion > oldVersion && newVersion == 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN read BOOL DEFAULT 1")
        }

        if (newVersion > oldVersion && newVersion == 3) {
            val time = getUtcTime()
            db.execSQL("ALTER TABLE accounts ADD COLUMN info TEXT")
            db.execSQL("ALTER TABLE accounts ADD COLUMN avatar TEXT")
            db.execSQL("ALTER TABLE accounts ADD COLUMN updated INTEGER DEFAULT $time")

            db.execSQL("ALTER TABLE contacts ADD COLUMN info TEXT")
            db.execSQL("ALTER TABLE contacts ADD COLUMN avatar TEXT")
            db.execSQL("ALTER TABLE contacts ADD COLUMN updated INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE contacts ADD COLUMN redacted BOOL DEFAULT 0")
        }

        if (newVersion > oldVersion && newVersion == 4) {
            db.execSQL("ALTER TABLE ips ADD COLUMN port INTEGER DEFAULT 5050")
            db.execSQL("ALTER TABLE ips ADD COLUMN priority INTEGER DEFAULT 3")
        }

        if (newVersion > oldVersion && newVersion == 5) {
            migrateAccountsToBlob(db)
            migrateContactsToBlob(db)
            migrateMessagesToBlob(db)
        }

        if (newVersion > oldVersion && newVersion == 6) {
            db.execSQL("ALTER TABLE messages ADD COLUMN guid INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN replyTo INTEGER DEFAULT 0")
            val columns = arrayOf("id", "time", "message")
            val cursor = db.query("messages", columns, null, null, null, null, "id", null)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val time = cursor.getLong(1)
                val data = cursor.getBlob(2)
                val guid = generateGuid(time, data)
                val values = ContentValues().apply {
                    put("guid", guid)
                }
                db.update("messages", values, "id = ?", arrayOf("$id"))
            }
            cursor.close()
        }

        if (newVersion > oldVersion && newVersion == 7) {
            db.execSQL("ALTER TABLE messages ADD COLUMN edit INTEGER DEFAULT 0")
        }

        if (newVersion > oldVersion && oldVersion == 7) {
            // Add group chat support
            db.execSQL(CREATE_GROUP_CHATS)
            db.execSQL(CREATE_GROUP_INVITES)
        }

        if (newVersion > oldVersion && newVersion == 9) {
            migrateGroupMessageIncomingColumn(db)
        }

        if (newVersion > oldVersion && newVersion == 10) {
            addOnlineColumnToMembersTables(db)
        }

        if (newVersion > oldVersion && newVersion > 10) {
            migrateGroupMessageReplyToColumn(db)
        }

        if (oldVersion < 12 && newVersion >= 12) {
            // Add reactions table
            db.execSQL(CREATE_REACTIONS)
        }

        if (oldVersion < 13 && newVersion >= 13) {
            // Add pending reactions table
            db.execSQL(CREATE_PENDING_REACTIONS)
        }
    }

    private fun migrateGroupMessageIncomingColumn(db: SQLiteDatabase) {
        val myPubkeyHex = db.query("accounts", arrayOf("pubkey"), null, null, null, null, "id", "1")
            .use { if (it.moveToFirst()) Hex.toHexString(it.getBlob(0)) else null }

        db.query("group_chats", arrayOf("chat_id"), null, null, null, null, null).use { chats ->
            while (chats.moveToNext()) {
                val chatId = chats.getLong(0)
                val messagesTable = "messages_$chatId"

                if (!hasColumn(db, messagesTable, "incoming")) {
                    try {
                        db.execSQL("ALTER TABLE $messagesTable ADD COLUMN incoming BOOL DEFAULT 1")
                    } catch (ignored: SQLiteException) {
                        continue        // column already there or table missing
                    }

                    if (myPubkeyHex != null) {
                        val membersTable = "members_$chatId"
                        val myMemberId = db.query(membersTable, arrayOf("id"),
                            "pubkey = ?", arrayOf(myPubkeyHex), null, null, null, "1"
                        ).use { if (it.moveToFirst()) it.getLong(0) else null }

                        if (myMemberId != null) {
                            db.execSQL("UPDATE $messagesTable SET incoming = 0 WHERE senderId = $myMemberId")
                        }
                    }
                }
            }
        }
    }

    private fun migrateGroupMessageReplyToColumn(db: SQLiteDatabase) {
        val myPubkeyHex = db.query("accounts", arrayOf("pubkey"), null, null, null, null, "id", "1")
            .use { if (it.moveToFirst()) Hex.toHexString(it.getBlob(0)) else null }

        db.query("group_chats", arrayOf("chat_id"), null, null, null, null, null).use { chats ->
            while (chats.moveToNext()) {
                val chatId = chats.getLong(0)
                val messagesTable = "messages_$chatId"

                if (!hasColumn(db, messagesTable, "replyTo")) {
                    try {
                        db.execSQL("ALTER TABLE $messagesTable ADD COLUMN replyTo INTEGER DEFAULT 0")
                    } catch (ignored: SQLiteException) {
                        continue        // column already there or table missing
                    }
                }
            }
        }
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { info ->
            while (info.moveToNext()) if (info.getString(1) == column) return true
            false
        }

    private fun addOnlineColumnToMembersTables(db: SQLiteDatabase) {
        db.query("group_chats", arrayOf("chat_id"), null, null, null, null, null).use { chats ->
            while (chats.moveToNext()) {
                val chatId = chats.getLong(0)
                val membersTable = "members_$chatId"

                if (!hasColumn(db, membersTable, "online")) {
                    try {
                        db.execSQL("ALTER TABLE $membersTable ADD COLUMN online BOOL DEFAULT 0")
                        Log.i(TAG, "Added 'online' column to $membersTable")
                    } catch (e: SQLiteException) {
                        Log.w(TAG, "Failed to add 'online' column to $membersTable", e)
                    }
                }
            }
        }
    }

    private fun migrateAccountsToBlob(db: SQLiteDatabase) {
        // Changing pubkey in "contacts" from TEXT to BLOB
        db.execSQL("CREATE TABLE a (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, privkey BLOB, pubkey BLOB, client INTEGER, info TEXT, avatar TEXT, updated INTEGER)")
        val columns = arrayOf("id", "name", "privkey", "pubkey", "client", "info", "avatar", "updated")
        val cursor = db.query("accounts", columns, null, null, null, null, "id", null)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            val name = cursor.getString(1)
            val privkey = Hex.decode(cursor.getString(2))
            val pubkey = Hex.decode(cursor.getString(3))
            val client = cursor.getInt(4)
            val info = cursor.getString(5)
            val avatar = cursor.getString(6)
            var updated = cursor.getLong(7)
            if (updated == 0L) {
                updated = getUtcTime()
            }

            val values = ContentValues().apply {
                put("id", id)
                put("name", name)
                put("privkey", privkey)
                put("pubkey", pubkey)
                put("client", client)
                put("info", info)
                put("avatar", avatar)
                put("updated", updated)
            }
            db.insert("a", null, values)
        }
        cursor.close()
        db.execSQL("DROP TABLE accounts")
        db.execSQL("ALTER TABLE a RENAME TO accounts")
    }

    private fun migrateContactsToBlob(db: SQLiteDatabase) {
        // Changing pubkey in "contacts" from TEXT to BLOB
        db.execSQL("CREATE TABLE c (id INTEGER PRIMARY KEY AUTOINCREMENT, pubkey BLOB, name TEXT, info TEXT, avatar TEXT, updated INTEGER, renamed BOOL, last_seen INTEGER)")
        val columns = arrayOf("id", "pubkey", "name", "info", "avatar", "updated", "redacted")
        val cursor = db.query("contacts", columns, null, null, null, null, "id", null)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            val pubkey = cursor.getString(1)
            val name = cursor.getString(2)
            val info = cursor.getString(3)
            val avatar = cursor.getString(4)
            val updated = cursor.getLong(5)
            val redacted = cursor.getInt(6) != 0

            val values = ContentValues().apply {
                put("id", id)
                put("pubkey", Hex.decode(pubkey))
                put("name", name)
                put("info", info)
                put("avatar", avatar)
                put("updated", updated)
                put("renamed", redacted)
                put("last_seen", 0L)
            }
            db.insert("c", null, values)
        }
        cursor.close()
        db.execSQL("DROP TABLE contacts")
        db.execSQL("ALTER TABLE c RENAME TO contacts")
    }

    private fun migrateMessagesToBlob(db: SQLiteDatabase) {
        // Community agreed that we can just "move fast, break things" this time
        db.execSQL("DROP TABLE messages")
        db.execSQL(CREATE_MESSAGES)
    }

    fun cleanUp() {
        //writableDatabase.execSQL("DELETE FROM ips")
        removeExpiredAddresses()
        writableDatabase.execSQL("VACUUM")

        /*if (deleteAllGroupChatsAndReset()) {
            Log.i(TAG, "Group chats reset successfully")
        }*/

        val columns = arrayOf("id", "name", "privkey", "pubkey", "client", "info", "avatar", "updated")
        val cursor = readableDatabase.query("accounts", columns, null, null, null, null, "id", null)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            val name = cursor.getString(1)
            val _privkey = cursor.getBlob(2)
            val pubkey = cursor.getBlob(3)
            val client = cursor.getInt(4)
            val info = cursor.getString(5)
            val avatar = cursor.getString(6)
            val updated = cursor.getLong(7)

            Log.i(TAG, "$id $name, ${Hex.toHexString(pubkey)}, $client, $info, $avatar, $updated")
        }
        cursor.close()
    }

    fun addContact(pubkey: ByteArray, name: String): Long {
        val id = getContactId(pubkey)
        if (id > 0) {
            return id
        }
        val values = ContentValues().apply {
            put("pubkey", pubkey)
            put("name", name)
        }
        return this.writableDatabase.insert("contacts", null, values).also {
            for (listener in listeners) {
                listener.onContactAdded(it)
            }
            notificationManager.onContactAdded(it)
        }
    }

    fun saveIp(pubkey: ByteArray, address: String, port: Short, clientId: Int, priority: Int, expiration: Long): Boolean {
        // First we get numeric id for this contact
        var id = getContactId(pubkey)
        return if (id >= 0) {
            //Log.i(TAG, "Found contact id $id")
            saveIp(id, address, port, clientId, priority, expiration)
        } else {
            id = addContact(pubkey, "")
            //Log.i(TAG, "Created contact id $id")
            saveIp(id, address, port, clientId, priority, expiration)
        }
    }

    private fun saveIp(id: Long, address: String, port: Short, clientId: Int, priority: Int, expiration: Long): Boolean {
        var values = ContentValues().apply {
            put("address", address)
            put("expiration", expiration)
            put("port", port)
            put("priority", priority)
        }
        if (writableDatabase.update("ips", values, "id = ? AND (client = ? OR client = 0)", arrayOf("$id", "$clientId")) > 0) {
            Log.i(TAG, "Updated $address for contact $id and client $clientId")
            return true
        }

        values = ContentValues().apply {
            put("id", id)
            put("client", clientId)
            put("address", address)
            put("expiration", expiration) // In seconds
            put("port", port)
            put("priority", priority)
        }
        return this.writableDatabase.insert("ips", null, values) >= 0
    }

    fun renameContact(contactId: Long, name: String, renamed: Boolean) {
        val cursor = this.readableDatabase.query("contacts", arrayOf("renamed"), "id = ?", arrayOf("$contactId"), null, null, null)
        val renamedLocally = if (cursor.moveToNext()) {
            cursor.getInt(0) != 0
        } else {
            false
        }
        cursor.close()
        if (renamedLocally && !renamed) {
            return
        }
        val values = ContentValues().apply {
            put("name", name)
            put("updated", getUtcTime())
            put("renamed", renamed)
        }
        writableDatabase.update("contacts", values, "id = ?", arrayOf("$contactId"))
    }

    fun updateContactInfo(contactId: Long, info: String) {
        val values = ContentValues().apply {
            put("info", info)
            put("updated", getUtcTime())
        }
        writableDatabase.update("contacts", values, "id = ?", arrayOf("$contactId"))
    }

    fun updateContactAvatar(contactId: Long, avatar: ByteArray?) {
        val cursor = this.readableDatabase.query("contacts", arrayOf("avatar"), "id = ?", arrayOf("$contactId"), null, null, null)
        val curFileName = if (cursor.moveToNext()) {
            cursor.getString(0)
        } else {
            ""
        }
        cursor.close()

        // Contact didn't have avatar until this time, will save new
        val avatarsDir = File(this.context.filesDir, "avatars")
        if (!avatarsDir.exists()) {
            avatarsDir.mkdirs()
        }

        if (curFileName != null && curFileName.isNotEmpty()) {
            val oldFile = File(avatarsDir, curFileName)
            oldFile.delete()
            if (avatar == null || avatar.isEmpty()) {
                val values = ContentValues().apply {
                    put("avatar", "")
                    put("updated", getUtcTime())
                }
                writableDatabase.update("contacts", values, "id = ?", arrayOf("$contactId"))
                return
            }
        }
        if (avatar == null) return

        val fileName = randomString(16)
        val ext = getImageExtensionOrNull(avatar) ?: return
        val fullName = "$fileName.$ext"
        val outputFile = File(avatarsDir, fullName)
        outputFile.outputStream().use { it.write(avatar) }

        val values = ContentValues().apply {
            put("avatar", fullName)
            put("updated", getUtcTime())
        }
        writableDatabase.update("contacts", values, "id = ?", arrayOf("$contactId"))
    }

    fun addMessage(contact: ByteArray, guid: Long, replyTo: Long, incoming: Boolean, delivered: Boolean, sendTime: Long, editTime: Long, type: Int, message: ByteArray): Long {
        var id = getContactId(contact)
        if (id <= 0) {
            id = addContact(contact, "")
        }
        var guid = guid
        if (guid == 0L) {
            guid = generateGuid(sendTime, message)
        }
        val values = ContentValues().apply {
            put("contact", id)
            put("guid", guid)
            put("replyTo", replyTo)
            put("incoming", incoming)
            put("delivered", delivered)
            put("time", sendTime)
            put("edit", editTime)
            put("type", type)
            put("message", message)
            put("read", !incoming || type == 2)
        }
        return this.writableDatabase.insert("messages", null, values).also {
            var processed = false
            for (listener in listeners) {
                if (!incoming) {
                    listener.onMessageSent(it, id)
                } else {
                    processed = processed or listener.onMessageReceived(it, id)
                }
            }
            if (!incoming) {
                notificationManager.onMessageSent(it, id)
            } else {
                if (!processed) {
                    notificationManager.onMessageReceived(it, id)
                }
            }
        }
    }

    fun setMessageDelivered(to: ByteArray, guid: Long, delivered: Boolean) {
        var contact = getContactId(to)
        if (contact <= 0) {
            contact = addContact(to, "")
        }
        val values = ContentValues().apply {
            put("delivered", delivered)
        }
        if (this.writableDatabase.update("messages", values, "guid = ? AND contact = ?", arrayOf("$guid", "$contact")) > 0) {
            val id = getMessageIdByGuid(guid)
            if (id != null) {
                Log.i(TAG, "Message $id with guid $guid delivered = $delivered")
                for (listener in listeners) {
                    listener.onMessageDelivered(id, delivered)
                }
                notificationManager.onMessageDelivered(id, delivered)
            }
        }
    }

    fun setMessageRead(contactId: Long, id: Long, read: Boolean) {
        val values = ContentValues().apply {
            put("read", read)
        }
        if (this.writableDatabase.update("messages", values, "id = ? AND contact = ?", arrayOf("$id", "$contactId")) > 0) {
            if (read) {
                notificationManager.onMessageRead(id, contactId)
            }
        }
    }

    fun setGroupMessageRead(chatId: Long, id: Long, read: Boolean) {
        val messagesTable = "messages_$chatId"

        // Check if message was previously unread
        val wasUnread = try {
            val cursor = readableDatabase.query(
                messagesTable,
                arrayOf("read", "incoming"),
                "id = ?",
                arrayOf("$id"),
                null, null, null
            )
            val result = if (cursor.moveToNext()) {
                val currentlyRead = cursor.getInt(0) != 0
                val isIncoming = cursor.getInt(1) != 0
                !currentlyRead && isIncoming
            } else {
                false
            }
            cursor.close()
            result
        } catch (e: Exception) {
            false
        }

        val values = ContentValues().apply {
            put("read", read)
        }
        if (this.writableDatabase.update(messagesTable, values, "id = ?", arrayOf("$id")) > 0) {
            if (read) {
                // Decrement unread count if marking as read and it was previously unread
                if (wasUnread) {
                    decrementGroupUnreadCount(chatId)
                }
                notificationManager.onGroupMessageRead(chatId, id)
            }
        }
    }

    fun getMessageIds(userId: Long): List<Pair<Long, Boolean>> {
        val list = mutableListOf<Pair<Long, Boolean>>()
        val cursor = readableDatabase.query("messages", arrayOf("id", "incoming"), "contact = ?", arrayOf("$userId"), null, null, "id")
        while (cursor.moveToNext()) {
            list.add(cursor.getLong(0) to (cursor.getInt(1) != 0))
        }
        cursor.close()
        return list
    }

    /**
     * Gets message IDs for a group chat from messages_* table.
     */
    fun getGroupMessageIds(chatId: Long): List<Pair<Long, Boolean>> {
        val messagesTable = "messages_$chatId"
        val list = mutableListOf<Pair<Long, Boolean>>()

        try {
            val cursor = readableDatabase.query(messagesTable, arrayOf("id", "incoming"), null, null, null, null, "id")
            while (cursor.moveToNext()) {
                list.add(cursor.getLong(0) to (cursor.getInt(1) != 0))
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting group message IDs for chat $chatId", e)
        }
        return list
    }

    private fun getUnreadCount(userId: Long): Int {
        val db = this.readableDatabase
        val cursor =
            db.query("messages", arrayOf("count(read)"), "contact = ? AND read = 0", arrayOf("$userId"), null, null, null)
        val count = if (cursor.moveToNext()) {
            cursor.getInt(0)
        } else {
            -1
        }
        cursor.close()
        return count
    }

    private fun getLastMessageDelivered(userId: Long): Boolean? {
        val db = this.readableDatabase
        val cursor =
            db.query("messages", arrayOf("delivered"), "contact = ? AND incoming = 0", arrayOf("$userId"), null, null, "id DESC", "1")
        val result = if (cursor.moveToNext()) {
            cursor.getInt(0) > 0
        } else {
            null
        }
        cursor.close()
        return result
    }

    fun getUnsentMessages(contact: ByteArray): List<Long> {
        val result = mutableListOf<Long>()
        val contactId = getContactId(contact)
        if (contactId < 0) {
            return result
        }
        val db = this.readableDatabase
        val cursor = db.query("messages", arrayOf("id"), "incoming = 0 AND delivered = 0 AND contact = ?", arrayOf("$contactId"), null, null, "id")
        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            result.add(id)
        }
        cursor.close()
        return result
    }

    fun getContactsWithUnsentMessages(): HashSet<ByteArray> {
        val result = HashSet<ByteArray>(5)
        val buf = HashSet<Long>(5)
        val db = this.readableDatabase
        val cursor = db.query("messages", arrayOf("contact"), "incoming = 0 AND delivered = 0", null, null, null, "id")
        while (cursor.moveToNext()) {
            val contact = cursor.getLong(0)
            if (!buf.contains(contact)) {
                getContactPubkey(contact)?.apply {
                    buf.add(contact)
                    result.add(this)
                }
            }
        }
        cursor.close()
        return result
    }

    private fun getLastMessage(userId: Long): Message? {
        var result: Message? = null
        val columns = arrayOf("id", "contact", "guid", "replyTo", "incoming", "delivered", "read", "time", "edit", "type", "message")
        val cursor = readableDatabase.query("messages", columns, "contact = ?", arrayOf("$userId"), null, null, "id DESC", "1")
        if (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            val contactId = cursor.getLong(1)
            val guid = cursor.getLong(2)
            val replyTo = cursor.getLong(3)
            val incoming = cursor.getInt(4) != 0
            val delivered = cursor.getInt(5) != 0
            val read = cursor.getInt(6) != 0
            val time = cursor.getLong(7)
            val edit = cursor.getLong(8)
            val type = cursor.getInt(9)
            val message = cursor.getBlob(10)
            //Log.i(TAG, "$messageId: $guid")
            result = Message(id, contactId, guid, replyTo, incoming, delivered, read, time, edit, type, message)
        }
        cursor.close()
        return result
    }

    fun getLastGroupMessage(chatId: Long): Message? {
        val messagesTable = "messages_$chatId"
        var result: Message? = null

        try {
            val cursor = readableDatabase.query(
                messagesTable,
                arrayOf("id", "guid", "senderId", "incoming", "timestamp", "type", "data", "delivered", "read", "replyTo"),
                "system = 0", null, null, null,
                "id DESC",
                "1"
            )

            if (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val guid = cursor.getLong(1)
                val senderId = cursor.getLong(2)
                val incoming = cursor.getInt(3) != 0
                val timestamp = cursor.getLong(4)
                val type = cursor.getInt(5)
                val data = cursor.getBlobOrNull(6)
                val delivered = cursor.getInt(7) != 0
                val read = cursor.getInt(8) != 0
                val replyTo = cursor.getLong(9)

                result = Message(
                    id = id,
                    contact = senderId,
                    guid = guid,
                    replyTo = replyTo,
                    incoming = incoming,
                    delivered = delivered,
                    read = read,
                    time = timestamp,
                    edit = 0L,
                    type = type,
                    data = data
                )
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting last message for chat $chatId", e)
        }

        return result
    }

    fun getMessage(messageId: Long, byGuid: Boolean = false): Message? {
        val pair = if (byGuid) {
            Pair("id", "guid = ?")
        } else {
            Pair("guid", "id = ?")
        }
        var result: Message? = null
        val columns = arrayOf("contact", pair.first, "replyTo", "incoming", "delivered", "read", "time", "edit", "type", "message")
        val cursor = readableDatabase.query("messages", columns, pair.second, arrayOf("$messageId"), null, null, null, "1")
        if (cursor.moveToNext()) {
            val contactId = cursor.getLong(0)
            val idOrGuid = cursor.getLong(1)
            val replyTo = cursor.getLong(2)
            val incoming = cursor.getInt(3) != 0
            val delivered = cursor.getInt(4) != 0
            val read = cursor.getInt(5) != 0
            val time = cursor.getLong(6)
            val edit = cursor.getLong(7)
            val type = cursor.getInt(8)
            val message = cursor.getBlobOrNull(9)
            //Log.i(TAG, "$messageId: $guid")
            result = if (byGuid) {
                Message(idOrGuid, contactId, messageId, replyTo, incoming, delivered, read, time, edit, type, message)
            } else {
                Message(messageId, contactId, idOrGuid, replyTo, incoming, delivered, read, time, edit, type, message)
            }
        }
        cursor.close()
        return result
    }

    /**
     * Gets a group message from messages_* table and converts it to Message format.
     * Used by MessageAdapter for group chats.
     */
    fun getGroupMessage(chatId: Long, messageId: Long, byGuid: Boolean = false): Message? {
        val messagesTable = "messages_$chatId"
        var result: Message? = null

        val selection = if (byGuid) {
            "guid = ?"
        } else {
            "id = ?"
        }

        try {
            val cursor = readableDatabase.query(
                messagesTable,
                arrayOf("id", "guid", "senderId", "incoming", "timestamp", "type", "data", "delivered", "read", "replyTo"),
                selection,
                arrayOf("$messageId"),
                null, null, null, "1"
            )

            if (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val guid = cursor.getLong(1)
                val senderId = cursor.getLong(2)
                val incoming = cursor.getInt(3) != 0
                val timestamp = cursor.getLong(4)
                val type = cursor.getInt(5)
                val data = cursor.getBlobOrNull(6)
                val delivered = cursor.getInt(7) != 0
                val read = cursor.getInt(8) != 0
                val replyTo = cursor.getLong(9)

                result = Message(
                    id = id,
                    contact = senderId,
                    guid = guid,
                    replyTo = replyTo,
                    incoming = incoming,
                    delivered = delivered,
                    read = read,
                    time = timestamp,
                    edit = 0L,
                    type = type,
                    data = data
                )
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting group message $messageId from chat $chatId", e)
        }

        return result
    }

    fun deleteGroupMessage(chatId: Long, messageId: Long): Boolean {
        val messagesTable = "messages_$chatId"
        return this.writableDatabase.delete(messagesTable, "id = ?", arrayOf(messageId.toString())) > 0
    }

    fun getMessageIdByGuid(guid: Long): Long? {
        val db = this.readableDatabase
        val statement = db.compileStatement("SELECT id FROM messages WHERE guid=? LIMIT 1")
        statement.bindLong(1, guid)
        val result = try {
            statement.simpleQueryForLong()
        } catch (e: SQLiteDoneException) {
            statement.close()
            return null
        }
        statement.close()
        return result
    }

    fun getGroupMessageIdByGuid(guid: Long, chatId: Long): Long? {
        val messagesTable = "messages_$chatId"
        val db = this.readableDatabase
        val statement = db.compileStatement("SELECT id FROM $messagesTable WHERE guid=? LIMIT 1")
        statement.bindLong(1, guid)
        val result = try {
            statement.simpleQueryForLong()
        } catch (e: SQLiteDoneException) {
            statement.close()
            return null
        }
        statement.close()
        return result
    }

    fun getContactId(pubkey: ByteArray): Long {
        val db = this.readableDatabase
        val statement = db.compileStatement("SELECT id FROM contacts WHERE pubkey=? LIMIT 1")
        statement.bindBlob(1, pubkey)
        return try {
            statement.simpleQueryForLong()
        } catch (e: SQLiteDoneException) {
            -1
        }
    }

    fun getContactPubkey(id: Long): ByteArray? {
        val db = this.readableDatabase
        val cursor =
            db.query("contacts", arrayOf("pubkey"), "id = ?", arrayOf(id.toString()), null, null, null)
        val pubkey = if (cursor.moveToNext()) {
            cursor.getBlob(0)
        } else {
            null
        }
        cursor.close()
        return pubkey
    }

    fun getMemberPubkey(id: Long, chatId: Long): ByteArray? {
        val membersTable = "members_$chatId"
        val list = mutableListOf<GroupMemberInfo>()

        val cursor = readableDatabase.query(
            membersTable,
            arrayOf("pubkey"),
            "id = ?", arrayOf(id.toString()), null, null,
            null
        )

        if (cursor.moveToNext()) {
            val pubkeyHex = cursor.getString(0)
            cursor.close()
            return Hex.decode(pubkeyHex)
        }
        cursor.close()
        return null
    }

    fun getMemberInfo(id: Long, chatId: Long, size: Int = 48, corners: Int = 6): Pair<String, Drawable?>? {
        val membersTable = "members_$chatId"

        val cursor = readableDatabase.query(
            membersTable,
            arrayOf("pubkey", "nickname"),
            "id = ?", arrayOf(id.toString()), null, null,
            null
        )

        val (pubkey, nickname) = if (cursor.moveToNext()) {
            val pubkeyHex = cursor.getString(0)
            Hex.decode(pubkeyHex) to cursor.getString(1)
        } else {
            cursor.close()
            return null
        }
        cursor.close()

        val avatar = getGroupMemberAvatar(chatId, pubkey, size, corners)

        return nickname to avatar
    }

    fun getContactName(id: Long): String {
        val db = this.readableDatabase
        val cursor =
            db.query("contacts", arrayOf("name"), "id = ?", arrayOf(id.toString()), null, null, null)
        val name = if (cursor.moveToNext()) {
            cursor.getString(0)
        } else {
            ""
        }
        cursor.close()
        return name
    }

    /**
     * Gets contact name by public key.
     * Returns empty string if contact is not found.
     */
    fun getContactNameByPubkey(pubkey: ByteArray): String {
        return try {
            val contactId = getContactId(pubkey)
            if (contactId > 0) {
                getContactName(contactId)
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting contact name by pubkey", e)
            ""
        }
    }

    fun getContactAvatar(id: Long, size: Int = 32, corners: Int = 6): Drawable? {
        val db = this.readableDatabase
        val cursor = db.query("contacts", arrayOf("avatar"), "id = ?", arrayOf(id.toString()), null, null, null)
        while (cursor.moveToNext()) {
            val avatar = cursor.getStringOrNull(0)
            cursor.close()
            return if (!avatar.isNullOrEmpty()) {
                loadRoundedAvatar(context, avatar, size, corners)
            } else {
                null
            }
        }
        cursor.close()
        return null
    }

    fun getContactList(): List<Contact> {
        val list = mutableListOf<Contact>()
        val db = this.readableDatabase
        val cursor = db.query("contacts", arrayOf("id", "pubkey", "name", "avatar"), "", emptyArray(), null, null, "name", "")
        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            val pubkey = cursor.getBlob(1)
            val name = cursor.getString(2)
            val avatar = cursor.getStringOrNull(3)
            val drawable = if (!avatar.isNullOrEmpty()) {
                loadRoundedAvatar(context, avatar)
            } else {
                null
            }
            list.add(Contact(id, pubkey, name, null, 0, drawable))
        }
        cursor.close()
        for (c in list) {
            c.unread = getUnreadCount(c.id)
            c.lastMessage = getLastMessage(c.id)
        }
        list.sortByDescending { it.lastMessage?.time }

        return list
    }

    fun getContactPeers(pubkey: ByteArray): List<Peer> {
        // First we get numeric id for this contact
        val id = getContactId(pubkey)
        if (id >= 0) {
            val curTime = getUtcTime()
            val list = mutableListOf<Peer>()
            val cursor = this.readableDatabase.query("ips", arrayOf("address", "port", "client", "priority", "expiration"), "id = ?",
                arrayOf(id.toString()), null, null, null, null)
            while (cursor.moveToNext()) {
                val address = cursor.getString(0)
                // TODO remove port from everywhere
                val port = cursor.getShort(1)
                val client = cursor.getInt(2)
                val priority = cursor.getInt(3)
                val expiration = cursor.getLong(4)
                if (curTime <= expiration) {
                    list.add(Peer(address, client, priority, expiration))
                }
            }
            Log.i(TAG, "Local ips: $list")
            cursor.close()
            return list
        }
        return emptyList()
    }

    /**
     * Deletes every IP record whose expiration time has passed.
     * @return number of rows removed
     */
    fun removeExpiredAddresses(): Int {
        val now = getUtcTime()
        return writableDatabase.delete("ips", "expiration < ?", arrayOf(now.toString()))
    }

    /**
     * Usually deleted dead peer address
     */
    fun removeContactPeer(address: String) {
        writableDatabase.delete("ips", "address = ?", arrayOf(address))
    }

    /**
     * Returns the public keys of every contact that currently has
     * NO un-expired rows in the ips table.
     */
    fun getContactsWithoutValidAddresses(): List<ByteArray> {
        val now = getUtcTime()

        // All contacts that DO have at least one valid IP
        val withValid = mutableSetOf<Long>()
        readableDatabase.rawQuery("SELECT DISTINCT id FROM ips WHERE expiration >= ?", arrayOf(now.toString())).use { c ->
            while (c.moveToNext()) withValid.add(c.getLong(0))
        }

        // All contacts minus the ones above → the ones without any valid IP
        val missing = mutableListOf<ByteArray>()
        readableDatabase.rawQuery("SELECT id, pubkey FROM contacts", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)          // numeric rowid
                if (id !in withValid) {
                    val blob = c.getBlob(1)    // pubkey byte array
                    missing.add(blob)
                }
            }
        }
        return missing
    }

    fun getAccountInfo(id: Int, ifUpdatedSince: Long): AccountInfo? {
        val db = this.readableDatabase
        val columns = arrayOf("name", "info", "avatar", "privkey", "pubkey", "client", "updated")
        val cursor = db.query("accounts", columns, "id = ? AND updated > ?", arrayOf(id.toString(), ifUpdatedSince.toString()), null, null, null)
        if (cursor.moveToNext()) {
            val name = cursor.getString(0)
            val info = cursor.getString(1) ?: ""
            val avatar = cursor.getString(2) ?: ""
            val privkey = cursor.getBlob(3)
            val pubkey = cursor.getBlob(4)
            val clientId = cursor.getInt(5) xor androidId
            val updated = cursor.getLong(6)

            val priv = Ed25519PrivateKeyParameters(privkey)
            val pub = Ed25519PublicKeyParameters(pubkey)
            cursor.close()
            myPublicKey = pubkey
            Log.i(TAG, "getAccountInfo: Found account '$name' with pubkey ${Hex.toHexString(pubkey).take(8)}..., updated=$updated")
            return AccountInfo(name, info, avatar, updated, clientId, AsymmetricCipherKeyPair(pub, priv))
        }
        cursor.close()
        Log.w(TAG, "getAccountInfo: Didn't find account info $id, or it didn't change since ${Date(ifUpdatedSince * 1000)} (query: id=$id AND updated > $ifUpdatedSince)")
        return null
    }

    fun generateNewAccount(): AccountInfo {
        val gen = Ed25519KeyPairGenerator()
        gen.init(KeyGenerationParameters(SecureRandom(), 256))
        val pair = gen.generateKeyPair()
        val pub = (pair.public as Ed25519PublicKeyParameters).encoded
        val priv = (pair.private as Ed25519PrivateKeyParameters).encoded
        val clientId = Random(System.currentTimeMillis()).run { nextInt() }
        val utcTime = getUtcTime()
        val values = ContentValues().apply {
            put("name", "")
            put("privkey", priv)
            put("pubkey", pub)
            put("client", clientId)
            put("updated", utcTime)
        }
        this.writableDatabase.insert("accounts", null, values)
        return AccountInfo("", "", "", utcTime, clientId, pair)
    }

    fun updateName(id: Int, name: String): Boolean {
        val utcTime = getUtcTime()
        val values = ContentValues().apply {
            put("name", name)
            put("updated", utcTime)
        }
        return this.writableDatabase.update("accounts", values, "id = ?", arrayOf("$id")) > 0
    }

    fun updateAvatar(id: Int, path: String): Boolean {
        val utcTime = getUtcTime()
        val values = ContentValues().apply {
            put("avatar", path)
            put("updated", utcTime)
        }
        return this.writableDatabase.update("accounts", values, "id = ?", arrayOf("$id")) > 0
    }

    fun getContactUpdateTime(pubkey: ByteArray): Long {
        val contactId = getContactId(pubkey)
        val cursor = this.readableDatabase.query("contacts", arrayOf("updated"), "id = ?", arrayOf("$contactId"), null, null, null)
        var updated = 0L
        while (cursor.moveToNext()) {
            updated = cursor.getLong(0)
        }
        cursor.close()
        return updated
    }

    fun removeContactAndChat(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // 1. Get contact avatar filename BEFORE deletion
            val avatarPath = db.query("contacts", arrayOf("avatar"),
                "id = ?", arrayOf("$id"), null, null, null).use {
                if (it.moveToNext()) it.getStringOrNull(0) else null
            }

            // 2. Get message attachment filenames BEFORE deletion
            val attachmentFiles = mutableListOf<String>()
            db.query("messages", arrayOf("message"),
                "contact = ? AND type = 1", arrayOf("$id"), null, null, null).use {
                while (it.moveToNext()) {
                    try {
                        val data = it.getBlob(0)
                        val json = JSONObject(String(data, Charsets.UTF_8))
                        if (json.has("name")) {
                            attachmentFiles.add(json.getString("name"))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse message attachment: ${e.message}")
                    }
                }
            }

            // 3. Delete from database
            if (db.delete("contacts", "id = ?", arrayOf("$id")) > 0) {
                db.delete("messages", "contact = ?", arrayOf("$id"))
                db.delete("ips", "id = ?", arrayOf("$id"))
            }

            db.setTransactionSuccessful()

            // 4. Delete files (AFTER transaction succeeds)
            deleteContactChatFiles(avatarPath, attachmentFiles)
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Deletes all files associated with a contact's chat.
     * Called after successful database deletion.
     */
    private fun deleteContactChatFiles(
        avatarPath: String?,
        attachmentFiles: List<String>
    ) {
        try {
            // Delete contact avatar
            if (avatarPath != null && avatarPath.isNotEmpty()) {
                val avatarsDir = File(context.filesDir, "avatars")
                File(avatarsDir, avatarPath).delete()
            }

            // Delete message attachments
            if (attachmentFiles.isNotEmpty()) {
                val filesDir = File(context.filesDir, "files")
                val cacheDir = File(context.cacheDir, "files")
                for (fileName in attachmentFiles) {
                    File(filesDir, fileName).delete()
                    File(cacheDir, fileName).delete() // Delete cached thumbnails too
                }
            }

            Log.i(TAG, "Deleted ${attachmentFiles.size} attachments and avatar for contact chat")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting contact chat files", e)
        }
    }

    fun deleteMessage(messageId: Long) {
        writableDatabase.delete("messages", "id=?", arrayOf("$messageId"))
    }

    fun generateGuid(time: Long, data: ByteArray): Long {
        return (data.contentHashCode().toLong() shl 32) xor time
    }

    // ============ Group Chat Methods ============

    /**
     * Creates per-chat tables for members and messages following mediator pattern.
     * Tables: members_{chatId}, messages_{chatId}
     */
    private fun createGroupChatTables(db: SQLiteDatabase, chatId: Long) {
        val membersTable = "members_$chatId"
        val messagesTable = "messages_$chatId"

        // Members table: similar to mediator's users-{id} table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $membersTable (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pubkey BLOB,
                nickname TEXT,
                info TEXT,
                avatar TEXT,
                permissions INTEGER NOT NULL DEFAULT 16,
                joined_at INTEGER NOT NULL,
                updated INTEGER NOT NULL DEFAULT 0,
                banned BOOL DEFAULT 0,
                online BOOL DEFAULT 0
            )
        """.trimIndent())

        // Messages table: stores metadata, actual encrypted blobs stored inline
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $messagesTable (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                msg_id INTEGER,
                guid INTEGER NOT NULL,
                senderId INTEGER NOT NULL,
                incoming BOOL DEFAULT 1,
                timestamp INTEGER NOT NULL,
                type INTEGER DEFAULT 1,
                system BOOL DEFAULT 0,
                data BLOB,
                delivered BOOL DEFAULT 0,
                read BOOL DEFAULT 0,
                replyTo INTEGER DEFAULT 0
            )
        """.trimIndent())

        // Create index on msg_id for efficient MAX() queries during sync
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${messagesTable}_msg_id ON $messagesTable(msg_id)")
        // Create index on guid for efficient deduplication lookups
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${messagesTable}_guid ON $messagesTable(guid)")

        Log.i(TAG, "Created per-chat tables and indexes for chat $chatId")
    }

    /**
     * Drops per-chat tables when leaving/deleting a chat.
     */
    private fun dropGroupChatTables(db: SQLiteDatabase, chatId: Long) {
        try {
            db.execSQL("DROP TABLE IF EXISTS members_$chatId")
            db.execSQL("DROP TABLE IF EXISTS messages_$chatId")
            Log.i(TAG, "Dropped per-chat tables for chat $chatId")
        } catch (e: Exception) {
            Log.e(TAG, "Error dropping tables for chat $chatId", e)
        }
    }

    /**
     * Saves or updates a group chat in the database.
     */
    fun saveGroupChat(chatId: Long, name: String, description: String?, avatarPath: String?, mediatorPubkey: ByteArray, ownerPubkey: ByteArray, sharedKey: ByteArray): Boolean {
        val now = getUtcTime()

        // Check if chat already exists
        val existing = getGroupChat(chatId)
        val values = ContentValues().apply {
            put("chat_id", chatId)
            put("name", name)
            put("description", description)
            put("avatar", avatarPath)
            put("mediator_pubkey", mediatorPubkey)
            put("owner_pubkey", ownerPubkey)
            put("shared_key", sharedKey)
            put("updated_at", now)
            if (existing == null) {
                put("created_at", now)
            }
        }

        return if (existing == null) {
            // Create new chat and its tables
            writableDatabase.insert("group_chats", null, values).also { result ->
                if (result > 0) {
                    createGroupChatTables(writableDatabase, chatId)
                    for (listener in listeners) {
                        listener.onGroupChatChanged(chatId)
                    }
                }
            } > 0
        } else {
            // Update existing chat
            writableDatabase.update("group_chats", values, "chat_id = ?", arrayOf(chatId.toLong().toString())).also {
                if (it > 0) {
                    for (listener in listeners) {
                        listener.onGroupChatChanged(chatId)
                    }
                }
            } > 0
        }
    }

    /**
     * Retrieves a group chat by ID.
     */
    fun getGroupChat(chatId: Long): GroupChatInfo? {
        val cursor = readableDatabase.query(
            "group_chats",
            arrayOf("chat_id", "name", "description", "avatar", "mediator_pubkey", "owner_pubkey", "shared_key",
                "created_at", "updated_at", "last_message_time", "unread_count", "muted"),
            "chat_id = ?",
            arrayOf(chatId.toString()),
            null, null, null
        )

        var result: GroupChatInfo? = null
        if (cursor.moveToNext()) {
            result = GroupChatInfo(
                chatId = cursor.getLong(0),
                name = cursor.getString(1),
                description = cursor.getStringOrNull(2),
                avatarPath = cursor.getStringOrNull(3),
                mediatorPubkey = cursor.getBlob(4),
                ownerPubkey = cursor.getBlob(5),
                sharedKey = cursor.getBlob(6),
                createdAt = cursor.getLong(7),
                updatedAt = cursor.getLong(8),
                lastMessageTime = cursor.getLong(9),
                unreadCount = cursor.getInt(10),
                muted = cursor.getInt(11) != 0
            )
        }
        cursor.close()
        return result
    }

    /**
     * Gets all group chats for the current user.
     */
    fun getGroupChatList(): List<GroupChatInfo> {
        val list = mutableListOf<GroupChatInfo>()
        val cursor = readableDatabase.query(
            "group_chats",
            arrayOf("chat_id", "name", "description", "avatar", "mediator_pubkey", "owner_pubkey", "shared_key",
                "created_at", "updated_at", "last_message_time", "unread_count", "muted"),
            null, null, null, null,
            "last_message_time DESC"
        )

        while (cursor.moveToNext()) {
            list.add(GroupChatInfo(
                chatId = cursor.getLong(0),
                name = cursor.getString(1),
                description = cursor.getStringOrNull(2),
                avatarPath = cursor.getStringOrNull(3),
                mediatorPubkey = cursor.getBlob(4),
                ownerPubkey = cursor.getBlob(5),
                sharedKey = cursor.getBlob(6),
                createdAt = cursor.getLong(7),
                updatedAt = cursor.getLong(8),
                lastMessageTime = cursor.getLong(9),
                unreadCount = cursor.getInt(10),
                muted = cursor.getInt(11) != 0
            ))
        }
        cursor.close()
        return list
    }

    /**
     * Gets count of members of a particular chat
     */
    fun getGroupChatMembersCount(chatId: Long): Int {
        val messagesTable = "members_$chatId"

        return try {
            val cursor = readableDatabase.rawQuery(
                "SELECT COUNT(id) FROM $messagesTable WHERE banned == 0",
                null
            )
            val count = if (cursor.moveToNext() && !cursor.isNull(0)) {
                cursor.getInt(0)
            } else {
                0
            }
            cursor.close()
            count
        } catch (e: Exception) {
            Log.e(TAG, "Error getting members count for chat $chatId", e)
            0
        }
    }

    /**
     * Sets the muted status for a group chat
     */
    fun setGroupChatMuted(chatId: Long, muted: Boolean): Boolean {
        return try {
            val values = ContentValues().apply {
                put("muted", if (muted) 1 else 0)
            }
            val rowsUpdated = writableDatabase.update(
                "group_chats",
                values,
                "chat_id = ?",
                arrayOf(chatId.toString())
            )
            if (rowsUpdated > 0) {
                Log.d(TAG, "Group chat $chatId muted status updated to $muted")
                true
            } else {
                Log.w(TAG, "Failed to update muted status for group chat $chatId")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating muted status for group chat $chatId", e)
            false
        }
    }

    /**
     * Increments the unread message count for a group chat.
     */
    private fun incrementGroupUnreadCount(chatId: Long) {
        try {
            writableDatabase.execSQL(
                "UPDATE group_chats SET unread_count = unread_count + 1 WHERE chat_id = ?",
                arrayOf(chatId.toString())
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error incrementing unread count for chat $chatId", e)
        }
    }

    /**
     * Decrements the unread message count for a group chat.
     * Ensures count doesn't go below 0.
     */
    private fun decrementGroupUnreadCount(chatId: Long) {
        try {
            writableDatabase.execSQL(
                "UPDATE group_chats SET unread_count = MAX(0, unread_count - 1) WHERE chat_id = ?",
                arrayOf(chatId.toString())
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error decrementing unread count for chat $chatId", e)
        }
    }

    /**
     * Resets the unread message count for a group chat to 0.
     */
    fun resetGroupUnreadCount(chatId: Long) {
        try {
            val values = ContentValues().apply {
                put("unread_count", 0)
            }
            writableDatabase.update(
                "group_chats",
                values,
                "chat_id = ?",
                arrayOf(chatId.toString())
            )
            Log.d(TAG, "Reset unread count for chat $chatId")
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting unread count for chat $chatId", e)
        }
    }

    /**
     * Gets all unique mediator public keys from saved group chats.
     * Returns a set of mediator pubkeys (as ByteArray).
     */
    fun getKnownMediators(): Set<ByteArray> {
        val mediators = mutableSetOf<ByteArray>()
        val cursor = readableDatabase.query(
            "group_chats",
            arrayOf("DISTINCT mediator_pubkey"),
            null, null, null, null, null
        )

        while (cursor.moveToNext()) {
            val mediatorPubkey = cursor.getBlob(0)
            if (mediatorPubkey != null && mediatorPubkey.isNotEmpty()) {
                mediators.add(mediatorPubkey)
            }
        }
        cursor.close()
        return mediators
    }

    /**
     * Load avatar for a group chat
     */
    fun getGroupChatAvatar(chatId: Long, size: Int = 32, corners: Int = 6): Drawable? {
        val db = this.readableDatabase
        val cursor = db.query(
            "group_chats",
            arrayOf("avatar"),
            "chat_id = ?",
            arrayOf(chatId.toLong().toString()),
            null,
            null,
            null
        )
        if (cursor.moveToFirst()) {
            val avatar = cursor.getStringOrNull(0)
            cursor.close()
            return if (!avatar.isNullOrEmpty()) {
                loadRoundedAvatar(context, avatar, size, corners)
            } else {
                null
            }
        }
        cursor.close()
        return null
    }

    /**
     * Updates or sets the avatar for a group chat
     */
    fun updateGroupChatAvatar(chatId: Long, avatar: ByteArray?): String? {
        val cursor = this.readableDatabase.query(
            "group_chats",
            arrayOf("avatar"),
            "chat_id = ?",
            arrayOf(chatId.toLong().toString()),
            null,
            null,
            null
        )
        val curFileName = if (cursor.moveToNext()) {
            cursor.getStringOrNull(0)
        } else {
            null
        }
        cursor.close()

        // Create avatars directory if it doesn't exist
        val avatarsDir = File(this.context.filesDir, "avatars")
        if (!avatarsDir.exists()) {
            avatarsDir.mkdirs()
        }

        // Delete old avatar file if it exists
        if (curFileName != null && curFileName.isNotEmpty()) {
            val oldFile = File(avatarsDir, curFileName)
            oldFile.delete()
            if (avatar == null || avatar.isEmpty()) {
                val values = ContentValues().apply {
                    put("avatar", null as String?)
                    put("updated_at", getUtcTime())
                }
                writableDatabase.update("group_chats", values, "chat_id = ?", arrayOf(chatId.toLong().toString()))
                return null
            }
        }
        if (avatar == null || avatar.isEmpty()) return null

        // Save new avatar to disk
        val fileName = randomString(16)
        val ext = getImageExtensionOrNull(avatar) ?: return null
        val fullName = "$fileName.$ext"
        val outputFile = File(avatarsDir, fullName)
        outputFile.outputStream().use { it.write(avatar) }

        // Update database with new avatar path
        val values = ContentValues().apply {
            put("avatar", fullName)
            put("updated_at", getUtcTime())
        }
        writableDatabase.update("group_chats", values, "chat_id = ?", arrayOf(chatId.toLong().toString()))

        return fullName
    }

    /**
     * Deletes a group chat and all associated data.
     */
    fun deleteGroupChat(chatId: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // 1. Get chat avatar filename BEFORE deletion
            val chatAvatarPath = db.query("group_chats", arrayOf("avatar"),
                "chat_id = ?", arrayOf(chatId.toString()), null, null, null).use {
                if (it.moveToNext()) it.getStringOrNull(0) else null
            }

            // 2. Get invite avatar filenames BEFORE deletion
            val inviteAvatarPaths = mutableListOf<String>()
            db.query("group_invites", arrayOf("chat_avatar"),
                "chat_id = ?", arrayOf(chatId.toString()), null, null, null).use {
                while (it.moveToNext()) {
                    it.getStringOrNull(0)?.let { path -> inviteAvatarPaths.add(path) }
                }
            }

            // 3. Get message attachment filenames BEFORE dropping table
            val messagesTable = "messages_$chatId"
            val attachmentFiles = mutableListOf<String>()
            try {
                db.query(messagesTable, arrayOf("data"),
                    "type = 1", null, null, null, null).use {
                    while (it.moveToNext()) {
                        try {
                            val data = it.getBlob(0)
                            val json = JSONObject(String(data, Charsets.UTF_8))
                            if (json.has("name")) {
                                attachmentFiles.add(json.getString("name"))
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse message attachment: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to retrieve attachments for chat $chatId: ${e.message}")
            }

            // 4. Drop per-chat tables
            dropGroupChatTables(db, chatId)

            // 5. Delete from group_chats table
            val result = db.delete("group_chats", "chat_id = ?",
                arrayOf(chatId.toString())) > 0

            // 6. Delete from group_invites table
            db.delete("group_invites", "chat_id = ?", arrayOf(chatId.toString()))

            db.setTransactionSuccessful()

            // 7. Delete files (AFTER transaction succeeds)
            deleteGroupChatFiles(chatId, chatAvatarPath, inviteAvatarPaths, attachmentFiles)

            return result
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Deletes all files associated with a group chat.
     * Called after successful database deletion.
     */
    private fun deleteGroupChatFiles(
        chatId: Long,
        chatAvatarPath: String?,
        inviteAvatarPaths: List<String>,
        attachmentFiles: List<String>
    ) {
        try {
            // Delete chat avatar
            if (chatAvatarPath != null && chatAvatarPath.isNotEmpty()) {
                val avatarsDir = File(context.filesDir, "avatars")
                File(avatarsDir, chatAvatarPath).delete()
            }

            // Delete invite avatars
            if (inviteAvatarPaths.isNotEmpty()) {
                val avatarsDir = File(context.filesDir, "avatars")
                for (path in inviteAvatarPaths) {
                    if (path.isNotEmpty()) {
                        File(avatarsDir, path).delete()
                    }
                }
            }

            // Delete member avatars directory
            val memberAvatarsDir = File(context.filesDir, "avatars_$chatId")
            if (memberAvatarsDir.exists()) {
                memberAvatarsDir.deleteRecursively()
            }

            // Delete message attachments
            if (attachmentFiles.isNotEmpty()) {
                val filesDir = File(context.filesDir, "files")
                val cacheDir = File(context.cacheDir, "files")
                for (fileName in attachmentFiles) {
                    File(filesDir, fileName).delete()
                    File(cacheDir, fileName).delete() // Delete cached thumbnails too
                }
            }

            Log.i(TAG, "Deleted ${attachmentFiles.size} attachments, " +
                    "${inviteAvatarPaths.size} invite avatars, " +
                    "chat avatar, and member avatars for chat $chatId")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting files for chat $chatId", e)
        }
    }

    /**
     * Adds a message to a group chat.
     */
    fun addGroupMessage(chatId: Long, serverMsgId: Long?, guid: Long, author: ByteArray, timestamp: Long, type: Int, system: Boolean, data: ByteArray, replyTo: Long = 0L): Long {
        // Get chat info to retrieve mediator's pubkey
        val chatInfo = getGroupChat(chatId)
        if (chatInfo == null) {
            Log.e(TAG, "Chat $chatId not found, cannot save message")
            return -1
        }

        // Check if author is the mediator (system message)
        val senderId = if (author.contentEquals(chatInfo.mediatorPubkey)) {
            // System message from mediator - use special senderId
            -1L
        } else {
            // Regular user message - lookup sender in members table
            val membersTable = "members_$chatId"
            val cursor = readableDatabase.query(
                membersTable,
                arrayOf("id"),
                "pubkey = ?", arrayOf(author.toHexString()), null, null,
                "id DESC",
                "1"
            )
            val id = if (cursor.moveToNext()) {
                cursor.getLong(0)
            } else {
                Log.w(TAG, "Error getting id of sender! Author not found in members table.")
                cursor.close()
                return -1
            }
            cursor.close()
            id
        }

        val incoming = !author.contentEquals(myPublicKey)

        val messagesTable = "messages_$chatId"
        val values = ContentValues().apply {
            if (serverMsgId != null) put("msg_id", serverMsgId.toLong())
            put("guid", guid.toLong())
            put("senderId", senderId)
            put("incoming", incoming)
            put("timestamp", timestamp)
            put("type", type)
            put("system", system)
            put("data", data)
            put("delivered", true) // Group messages are delivered via mediator
            put("read", false)
            put("replyTo", replyTo)
        }

        val id = writableDatabase.insert(messagesTable, null, values)
        if (id > 0) {
            // Update last message time
            val updateValues = ContentValues().apply {
                put("last_message_time", timestamp)
            }
            writableDatabase.update("group_chats", updateValues, "chat_id = ?", arrayOf(chatId.toLong().toString()))

            // Don't notify or show notifications for system messages (senderId == -1)
            if (senderId != -1L) {
                // Increment unread count for incoming messages
                if (incoming) {
                    incrementGroupUnreadCount(chatId)
                }

                // Notify listeners - track if any listener processed it
                var processed = false
                for (listener in listeners) {
                    processed = processed or listener.onGroupMessageReceived(chatId, id, senderId)
                }

                // Only show notification if not processed by any listener (i.e., chat not open)
                if (!processed) {
                    notificationManager.onGroupMessageReceived(chatId, id, senderId)
                }
            }
        }
        return id
    }

    fun checkGroupMessageExists(chatId: Long, guid: Long): Boolean {
        val messagesTable = "messages_$chatId"
        val cursor = readableDatabase.query(
            messagesTable,
            arrayOf("id"),
            "guid = ?",
            arrayOf(guid.toString()),
            null, null, null,
            "1"
        )
        val exists = cursor.count > 0
        cursor.close()
        return exists
    }

    fun updateGroupMessageServerId(chatId: Long, guid: Long, serverMsgId: Long): Boolean {
        val messagesTable = "messages_$chatId"
        val values = ContentValues().apply {
            put("msg_id", serverMsgId)
        }
        val updated = writableDatabase.update(
            messagesTable,
            values,
            "guid = ? AND msg_id IS NULL",
            arrayOf(guid.toString())
        )
        return updated > 0
    }

    /**
     * Gets the maximum server message ID for a chat.
     * Returns 0 if no messages with server IDs exist.
     * Uses index on msg_id for efficient querying.
     */
    fun getMaxServerMessageId(chatId: Long): Long {
        val messagesTable = "messages_$chatId"

        return try {
            val cursor = readableDatabase.rawQuery(
                "SELECT MAX(msg_id) FROM $messagesTable WHERE msg_id IS NOT NULL",
                null
            )
            val maxId = if (cursor.moveToNext() && !cursor.isNull(0)) {
                cursor.getLong(0)
            } else {
                0L
            }
            cursor.close()
            maxId
        } catch (e: Exception) {
            Log.e(TAG, "Error getting max server message ID for chat $chatId", e)
            0L
        }
    }

    /**
     * Gets messages for a group chat.
     */
    fun getGroupMessages(chatId: Long, limit: Int = 100): List<GroupMessage> {
        val messagesTable = "messages_$chatId"
        val list = mutableListOf<GroupMessage>()

        try {
            val cursor = readableDatabase.query(
                messagesTable,
                arrayOf("id", "msg_id", "guid", "senderId", "timestamp", "type", "data", "delivered", "read", "replyTo"),
                null, null, null, null,
                "id DESC",
                limit.toString()
            )

            while (cursor.moveToNext()) {
                list.add(GroupMessage(
                    localId = cursor.getLong(0),
                    msgId = if (cursor.isNull(1)) null else cursor.getLong(1),
                    guid = cursor.getLong(2),
                    senderId = cursor.getLong(3),
                    timestamp = cursor.getLong(4),
                    type = cursor.getInt(5),
                    data = cursor.getBlobOrNull(6),
                    delivered = cursor.getInt(7) != 0,
                    read = cursor.getInt(8) != 0,
                    replyTo = cursor.getLong(9)
                ))
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting messages for chat $chatId", e)
        }

        return list.reversed() // Return in chronological order
    }

    /**
     * Saves a group invite.
     */
    fun saveGroupInvite(chatId: Long, inviteId: Long, fromPubkey: ByteArray, timestamp: Long, chatName: String, chatDescription: String?, chatAvatar: ByteArray?, encryptedData: ByteArray): Pair<Long, String?> {
        // Save avatar to disk if provided
        val avatarPath = if (chatAvatar != null && chatAvatar.isNotEmpty()) {
            val avatarsDir = File(context.filesDir, "avatars")
            if (!avatarsDir.exists()) {
                avatarsDir.mkdirs()
            }

            val fileName = randomString(16)
            val ext = getImageExtensionOrNull(chatAvatar) ?: "jpg"
            val fullName = "$fileName.$ext"
            val outputFile = File(avatarsDir, fullName)
            outputFile.outputStream().use { it.write(chatAvatar) }
            fullName
        } else {
            null
        }

        val values = ContentValues().apply {
            put("id", inviteId)
            put("chat_id", chatId)
            put("from_pubkey", fromPubkey)
            put("timestamp", timestamp)
            put("chat_name", chatName)
            put("chat_description", chatDescription)
            put("chat_avatar", avatarPath)
            put("encrypted_data", encryptedData)
            put("status", 0) // 0 = pending
        }
        val inviteId = writableDatabase.insert("group_invites", null, values)
        return Pair(inviteId, avatarPath)
    }

    /**
     * Gets pending group invites with sender names populated.
     */
    fun getPendingGroupInvites(): List<GroupInvite> {
        val list = mutableListOf<GroupInvite>()
        val cursor = readableDatabase.query(
            "group_invites",
            arrayOf("id", "chat_id", "from_pubkey", "timestamp", "chat_name", "chat_description", "chat_avatar", "encrypted_data", "status"),
            "status = 0",
            null, null, null,
            "timestamp DESC"
        )

        while (cursor.moveToNext()) {
            val senderPubkey = cursor.getBlob(2)

            // Try to get contact name, fall back to hex string if not found
            val senderName = getContactNameByPubkey(senderPubkey).ifEmpty {
                Hex.toHexString(senderPubkey).take(8)
            }

            list.add(GroupInvite(
                id = cursor.getLong(0),
                chatId = cursor.getLong(1),
                sender = senderPubkey,
                senderName = senderName,
                timestamp = cursor.getLong(3),
                chatName = cursor.getString(4),
                chatDescription = cursor.getStringOrNull(5),
                chatAvatarPath = cursor.getStringOrNull(6),
                encryptedData = cursor.getBlob(7),
                status = cursor.getInt(8)
            ))
        }
        cursor.close()
        return list
    }

    /**
     * Updates invite status (0=pending, 1=accepted, 2=rejected).
     */
    fun updateGroupInviteStatus(inviteId: Long, status: Int) {
        val values = ContentValues().apply {
            put("status", status)
        }
        writableDatabase.update("group_invites", values, "id = ?", arrayOf(inviteId.toString()))
    }

    /**
     * Deletes an invite and cleans up its avatar file.
     */
    fun deleteGroupInvite(inviteId: Long) {
        // Get avatar path before deleting
        val cursor = readableDatabase.query(
            "group_invites",
            arrayOf("chat_avatar"),
            "id = ?",
            arrayOf(inviteId.toString()),
            null, null, null
        )

        if (cursor.moveToFirst()) {
            val avatarPath = cursor.getStringOrNull(0)
            cursor.close()

            // Delete avatar file if it exists
            if (avatarPath != null && avatarPath.isNotEmpty()) {
                val avatarsDir = File(context.filesDir, "avatars")
                val avatarFile = File(avatarsDir, avatarPath)
                if (avatarFile.exists()) {
                    avatarFile.delete()
                }
            }
        } else {
            cursor.close()
        }

        writableDatabase.delete("group_invites", "id = ?", arrayOf(inviteId.toString()))
    }

    /**
     * Updates member info in the members_{chatId} table.
     * Saves avatar to disk in avatars_{chatId} directory and returns the avatar path.
     */
    fun updateGroupMemberInfo(chatId: Long, pubkey: ByteArray, nickname: String?, info: String?, avatar: ByteArray?): String? {
        require(pubkey.size == 32) { "pubkey must be 32 bytes" }
        val membersTable = "members_$chatId"

        // Check if member exists
        val cursor = readableDatabase.query(
            membersTable,
            arrayOf("avatar"),
            "pubkey = ?",
            arrayOf(pubkey.toHexString()),
            null, null, null
        )

        val existingAvatarPath = if (cursor.moveToNext()) {
            cursor.getStringOrNull(0)
        } else {
            null
        }
        cursor.close()

        // Handle avatar file
        var avatarPath: String? = existingAvatarPath
        if (avatar != null && avatar.isNotEmpty()) {
            // Create chat-specific avatars directory
            val avatarsDir = File(this.context.filesDir, "avatars_$chatId")
            if (!avatarsDir.exists()) {
                avatarsDir.mkdirs()
            }

            // Delete old avatar if exists
            if (existingAvatarPath != null && existingAvatarPath.isNotEmpty()) {
                val oldFile = File(avatarsDir, existingAvatarPath)
                oldFile.delete()
            }

            // Save new avatar
            val fileName = "member_${pubkey.toHexString().substring(0, 16)}.jpg"
            val file = File(avatarsDir, fileName)
            try {
                FileOutputStream(file).use { it.write(avatar) }
                avatarPath = fileName
            } catch (e: Exception) {
                Log.e(TAG, "Error saving member avatar", e)
            }
        } else if (avatar != null && avatar.isEmpty() && existingAvatarPath != null) {
            // Empty array means delete avatar
            val avatarsDir = File(this.context.filesDir, "avatars_$chatId")
            val oldFile = File(avatarsDir, existingAvatarPath)
            oldFile.delete()
            avatarPath = null
        }

        // Update member info with current timestamp
        val now = getUtcTime()
        val values = ContentValues().apply {
            put("pubkey", pubkey.toHexString())
            if (nickname != null) put("nickname", nickname)
            if (info != null) put("info", info)
            put("avatar", avatarPath)
            put("updated", now)
        }

        val updated = writableDatabase.update(
            membersTable,
            values,
            "pubkey = ?",
            arrayOf(pubkey.toHexString())
        )

        if (updated == 0) {
            // Member doesn't exist, insert new
            values.put("joined_at", now)
            values.put("permissions", 16)
            values.put("banned", 0)
            writableDatabase.insert(membersTable, null, values)
        }

        return avatarPath
    }

    /**
     * Gets the latest (maximum) update time for all members in a chat.
     * Returns 0 if no members have been updated or chat doesn't exist.
     */
    fun getLatestGroupMemberUpdateTime(chatId: Long): Long {
        val membersTable = "members_$chatId"

        return try {
            val cursor = readableDatabase.rawQuery(
                "SELECT MAX(updated) FROM $membersTable",
                null
            )
            val maxTime = if (cursor.moveToNext()) {
                cursor.getLong(0)
            } else {
                0L
            }
            cursor.close()
            maxTime
        } catch (e: Exception) {
            Log.e(TAG, "Error getting latest member update time for chat $chatId", e)
            0L
        }
    }

    /**
     * Bulk updates permissions and online status for members from mediator response.
     * Uses the Member data class from MediatorClient.
     */
    fun updateGroupMembersPermissionsAndOnline(chatId: Long, members: List<com.revertron.mimir.net.MediatorClient.Member>) {
        val membersTable = "members_$chatId"

        writableDatabase.beginTransaction()
        try {
            for (member in members) {
                require(member.pubkey.size == 32) { "pubkey must be 32 bytes" }

                val values = ContentValues().apply {
                    put("permissions", member.permissions)
                    put("online", if (member.online) 1 else 0)
                }

                val updated = writableDatabase.update(
                    membersTable,
                    values,
                    "pubkey = ?",
                    arrayOf(member.pubkey.toHexString())
                )

                // If member doesn't exist in local table yet, log a warning
                if (updated == 0) {
                    Log.w(TAG, "Member ${member.pubkey.toHexString().substring(0, 16)}... not found in $membersTable")
                }
            }

            writableDatabase.setTransactionSuccessful()
            Log.i(TAG, "Updated permissions and online status for ${members.size} members in chat $chatId")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating members permissions and online status for chat $chatId", e)
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /**
     * Gets member nickname from the members_{chatId} table.
     */
    fun getGroupMemberNickname(chatId: Long, memberId: Long): String? {
        val membersTable = "members_$chatId"

        val cursor = readableDatabase.query(
            membersTable,
            arrayOf("nickname"),
            "id = ?",
            arrayOf(memberId.toString()),
            null, null, null
        )

        if (cursor.moveToNext()) {
            val nickname = cursor.getStringOrNull(0)
            cursor.close()
            return nickname
        } else {
            cursor.close()
            return null
        }
    }

    /**
     * Gets member info from the members_{chatId} table.
     */
    fun getGroupMemberInfo(chatId: Long, pubkey: ByteArray): GroupMemberInfo? {
        require(pubkey.size == 32) { "pubkey must be 32 bytes" }
        val membersTable = "members_$chatId"

        val cursor = readableDatabase.query(
            membersTable,
            arrayOf("nickname", "info", "avatar", "permissions", "joined_at", "banned"),
            "pubkey = ?",
            arrayOf(pubkey.toHexString()),
            null, null, null
        )

        val result = if (cursor.moveToNext()) {
            GroupMemberInfo(
                pubkey = pubkey,
                nickname = cursor.getStringOrNull(0),
                info = cursor.getStringOrNull(1),
                avatarPath = cursor.getStringOrNull(2),
                permissions = cursor.getInt(3),
                joinedAt = cursor.getLong(4),
                banned = cursor.getInt(5) != 0
            )
        } else {
            null
        }
        cursor.close()
        return result
    }

    /**
     * Gets all members for a group chat.
     */
    fun getGroupMembers(chatId: Long): List<GroupMemberInfo> {
        val membersTable = "members_$chatId"
        val list = mutableListOf<GroupMemberInfo>()

        val cursor = readableDatabase.query(
            membersTable,
            arrayOf("pubkey", "nickname", "info", "avatar", "permissions", "joined_at", "banned", "online"),
            null, null, null, null,
            "joined_at ASC"
        )

        while (cursor.moveToNext()) {
            val pubkeyHex = cursor.getString(0)
            list.add(GroupMemberInfo(
                pubkey = Hex.decode(pubkeyHex),
                nickname = cursor.getStringOrNull(1),
                info = cursor.getStringOrNull(2),
                avatarPath = cursor.getStringOrNull(3),
                permissions = cursor.getInt(4),
                joinedAt = cursor.getLong(5),
                banned = cursor.getInt(6) != 0,
                online = cursor.getInt(7) != 0
            ))
        }
        cursor.close()
        return list
    }

    /**
     * Gets member avatar drawable from avatars_{chatId} directory.
     */
    fun getGroupMemberAvatar(chatId: Long, pubkey: ByteArray, size: Int = 32, corners: Int = 6): Drawable? {
        val memberInfo = getGroupMemberInfo(chatId, pubkey) ?: return null
        val avatarPath = memberInfo.avatarPath ?: return null

        val avatarsDir = File(this.context.filesDir, "avatars_$chatId")
        val file = File(avatarsDir, avatarPath)

        if (!file.exists()) return null

        return try {
            loadRoundedAvatar(context, file.absolutePath, size, corners)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Deletes all group chats with their associated tables and recreates the group_chats and group_invites tables.
     */
    fun deleteAllGroupChatsAndReset(): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Get all group chats
            val chatList = getGroupChatList()

            // Drop per-chat tables for each group chat
            for (chat in chatList) {
                dropGroupChatTables(db, chat.chatId)
            }

            // Drop and recreate group_chats table
            db.execSQL("DROP TABLE IF EXISTS group_chats")
            db.execSQL(CREATE_GROUP_CHATS)

            // Drop and recreate group_invites table
            db.execSQL("DROP TABLE IF EXISTS group_invites")
            db.execSQL(CREATE_GROUP_INVITES)

            db.setTransactionSuccessful()
            Log.i(TAG, "Successfully deleted all group chats and reset tables")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting all group chats", e)
            return false
        } finally {
            db.endTransaction()
        }
    }

    // ==================== REACTIONS ====================

    /**
     * Adds or updates a reaction to a message.
     * If the same user reacts with the same emoji again, it's treated as a duplicate (UNIQUE constraint).
     * @param messageGuid GUID of the message
     * @param chatId Group chat ID (null for personal chats)
     * @param reactorPubkey Public key of the reactor
     * @param emoji The emoji reaction
     * @return The reaction ID, or -1 if failed
     */
    fun addReaction(messageGuid: Long, chatId: Long?, reactorPubkey: ByteArray, emoji: String): Long {
        val values = ContentValues().apply {
            put("message_guid", messageGuid)
            if (chatId != null) {
                put("chat_id", chatId)
            }
            put("reactor_pubkey", reactorPubkey)
            put("emoji", emoji)
            put("timestamp", System.currentTimeMillis())
        }

        val id = try {
            writableDatabase.insertWithOnConflict("reactions", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding reaction", e)
            -1L
        }

        if (id > 0) {
            // Notify listeners
            for (listener in listeners) {
                listener.onReactionAdded(messageGuid, chatId, emoji)
            }
        }

        return id
    }

    /**
     * Removes a specific reaction from a message.
     * @param messageGuid GUID of the message
     * @param chatId Group chat ID (null for personal chats)
     * @param reactorPubkey Public key of the reactor
     * @param emoji The emoji to remove
     * @return true if successfully removed
     */
    fun removeReaction(messageGuid: Long, chatId: Long?, reactorPubkey: ByteArray, emoji: String): Boolean {
        val pubkeyHex = Hex.toHexString(reactorPubkey)
        val deleted = try {
            if (chatId != null) {
                writableDatabase.delete(
                    "reactions",
                    "message_guid = ? AND chat_id = ? AND HEX(reactor_pubkey) = ? AND emoji = ?",
                    arrayOf(messageGuid.toString(), chatId.toString(), pubkeyHex.uppercase(), emoji)
                )
            } else {
                writableDatabase.delete(
                    "reactions",
                    "message_guid = ? AND chat_id IS NULL AND HEX(reactor_pubkey) = ? AND emoji = ?",
                    arrayOf(messageGuid.toString(), pubkeyHex.uppercase(), emoji)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing reaction", e)
            0
        }

        if (deleted > 0) {
            // Notify listeners
            for (listener in listeners) {
                listener.onReactionRemoved(messageGuid, chatId, emoji)
            }
        }

        return deleted > 0
    }

    /**
     * Gets all reactions for a specific message.
     * @param messageGuid GUID of the message
     * @param chatId Group chat ID (null for personal chats)
     * @return List of reactions
     */
    fun getReactions(messageGuid: Long, chatId: Long?): List<Reaction> {
        val reactions = mutableListOf<Reaction>()

        val whereClause = if (chatId != null) {
            "message_guid = ? AND chat_id = ?"
        } else {
            "message_guid = ? AND chat_id IS NULL"
        }

        val whereArgs = if (chatId != null) {
            arrayOf(messageGuid.toString(), chatId.toString())
        } else {
            arrayOf(messageGuid.toString())
        }

        val cursor = readableDatabase.query(
            "reactions",
            arrayOf("id", "message_guid", "chat_id", "reactor_pubkey", "emoji", "timestamp"),
            whereClause,
            whereArgs,
            null, null,
            "timestamp ASC"
        )

        while (cursor.moveToNext()) {
            reactions.add(Reaction(
                id = cursor.getLong(0),
                messageGuid = cursor.getLong(1),
                chatId = cursor.getLong(2).takeIf { !cursor.isNull(2) },
                reactorPubkey = cursor.getBlob(3), // Read as BLOB, not string
                emoji = cursor.getString(4),
                timestamp = cursor.getLong(5)
            ))
        }
        cursor.close()

        return reactions
    }

    /**
     * Gets aggregated reaction counts for a message.
     * Returns a map of emoji -> list of reactor pubkeys
     */
    fun getReactionCounts(messageGuid: Long, chatId: Long?): Map<String, List<ByteArray>> {
        val reactions = getReactions(messageGuid, chatId)
        val grouped = reactions.groupBy { it.emoji }
        return grouped.mapValues { entry -> entry.value.map { it.reactorPubkey } }
    }

    /**
     * Checks if a user has reacted with a specific emoji.
     */
    fun hasReacted(messageGuid: Long, chatId: Long?, reactorPubkey: ByteArray, emoji: String): Boolean {
        val pubkeyHex = Hex.toHexString(reactorPubkey)
        val cursor = if (chatId != null) {
            readableDatabase.query(
                "reactions",
                arrayOf("id"),
                "message_guid = ? AND chat_id = ? AND HEX(reactor_pubkey) = ? AND emoji = ?",
                arrayOf(messageGuid.toString(), chatId.toString(), pubkeyHex.uppercase(), emoji),
                null, null, null, "1"
            )
        } else {
            readableDatabase.query(
                "reactions",
                arrayOf("id"),
                "message_guid = ? AND chat_id IS NULL AND HEX(reactor_pubkey) = ? AND emoji = ?",
                arrayOf(messageGuid.toString(), pubkeyHex.uppercase(), emoji),
                null, null, null, "1"
            )
        }

        val exists = cursor.count > 0
        cursor.close()
        return exists
    }

    /**
     * Gets the current reaction emoji from a specific user on a message.
     * Returns null if the user hasn't reacted yet.
     */
    fun getUserCurrentReaction(messageGuid: Long, chatId: Long?, reactorPubkey: ByteArray): String? {
        val pubkeyHex = Hex.toHexString(reactorPubkey)
        val cursor = if (chatId != null) {
            readableDatabase.query(
                "reactions",
                arrayOf("emoji"),
                "message_guid = ? AND chat_id = ? AND HEX(reactor_pubkey) = ?",
                arrayOf(messageGuid.toString(), chatId.toString(), pubkeyHex.uppercase()),
                null, null, null, "1"
            )
        } else {
            readableDatabase.query(
                "reactions",
                arrayOf("emoji"),
                "message_guid = ? AND chat_id IS NULL AND HEX(reactor_pubkey) = ?",
                arrayOf(messageGuid.toString(), pubkeyHex.uppercase()),
                null, null, null, "1"
            )
        }

        val emoji = if (cursor.moveToFirst()) {
            cursor.getString(0)
        } else {
            null
        }
        cursor.close()
        return emoji
    }

    /**
     * Toggles a reaction - adds it if not present, removes it if present.
     * Important: Each user can only have ONE reaction per message.
     * If adding a new reaction, any existing reaction from this user is removed first.
     * @return true if added, false if removed
     */
    fun toggleReaction(messageGuid: Long, chatId: Long?, reactorPubkey: ByteArray, emoji: String): Boolean {
        return if (hasReacted(messageGuid, chatId, reactorPubkey, emoji)) {
            // User clicked on their existing reaction - remove it
            removeReaction(messageGuid, chatId, reactorPubkey, emoji)
            false
        } else {
            // User is adding a new reaction
            // First, remove any existing reaction from this user on this message
            removeAllReactionsFromUser(messageGuid, chatId, reactorPubkey)
            // Then add the new reaction
            addReaction(messageGuid, chatId, reactorPubkey, emoji)
            true
        }
    }

    /**
     * Removes all reactions from a specific user on a message.
     * Used to enforce "one reaction per user" policy.
     */
    private fun removeAllReactionsFromUser(messageGuid: Long, chatId: Long?, reactorPubkey: ByteArray): Int {
        val pubkeyHex = Hex.toHexString(reactorPubkey)
        return try {
            if (chatId != null) {
                writableDatabase.delete(
                    "reactions",
                    "message_guid = ? AND chat_id = ? AND HEX(reactor_pubkey) = ?",
                    arrayOf(messageGuid.toString(), chatId.toString(), pubkeyHex.uppercase())
                )
            } else {
                writableDatabase.delete(
                    "reactions",
                    "message_guid = ? AND chat_id IS NULL AND HEX(reactor_pubkey) = ?",
                    arrayOf(messageGuid.toString(), pubkeyHex.uppercase())
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing all user reactions", e)
            0
        }
    }

    // ==================== PENDING REACTIONS ====================

    /**
     * Adds a pending reaction to the queue for later sending.
     * Called when a reaction cannot be sent immediately due to no connection.
     */
    fun addPendingReaction(messageGuid: Long, chatId: Long?, contactPubkey: ByteArray?, emoji: String, add: Boolean): Long {
        val values = ContentValues().apply {
            put("message_guid", messageGuid)
            if (chatId != null) {
                put("chat_id", chatId)
            }
            if (contactPubkey != null) {
                put("contact_pubkey", contactPubkey)
            }
            put("emoji", emoji)
            put("add_reaction", add)
            put("timestamp", System.currentTimeMillis())
        }

        return try {
            writableDatabase.insert("pending_reactions", null, values)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding pending reaction", e)
            -1L
        }
    }

    /**
     * Gets all pending reactions for a specific contact.
     * Used when connection is established to send queued reactions.
     */
    fun getPendingReactionsForContact(contactPubkey: ByteArray): List<PendingReaction> {
        val pubkeyHex = Hex.toHexString(contactPubkey)
        val reactions = mutableListOf<PendingReaction>()

        val cursor = readableDatabase.query(
            "pending_reactions",
            arrayOf("id", "message_guid", "chat_id", "emoji", "add_reaction", "timestamp"),
            "HEX(contact_pubkey) = ? AND chat_id IS NULL",
            arrayOf(pubkeyHex.uppercase()),
            null, null, "timestamp ASC"
        )

        while (cursor.moveToNext()) {
            reactions.add(PendingReaction(
                id = cursor.getLong(0),
                messageGuid = cursor.getLong(1),
                chatId = cursor.getLong(2).takeIf { !cursor.isNull(2) },
                contactPubkey = contactPubkey,
                emoji = cursor.getString(3),
                add = cursor.getInt(4) != 0,
                timestamp = cursor.getLong(5)
            ))
        }
        cursor.close()

        return reactions
    }

    /**
     * Gets all pending reactions for a specific group chat.
     */
    fun getPendingReactionsForChat(chatId: Long): List<PendingReaction> {
        val reactions = mutableListOf<PendingReaction>()

        val cursor = readableDatabase.query(
            "pending_reactions",
            arrayOf("id", "message_guid", "emoji", "add_reaction", "timestamp"),
            "chat_id = ?",
            arrayOf(chatId.toString()),
            null, null, "timestamp ASC"
        )

        while (cursor.moveToNext()) {
            reactions.add(PendingReaction(
                id = cursor.getLong(0),
                messageGuid = cursor.getLong(1),
                chatId = chatId,
                contactPubkey = null,
                emoji = cursor.getString(2),
                add = cursor.getInt(3) != 0,
                timestamp = cursor.getLong(4)
            ))
        }
        cursor.close()

        return reactions
    }

    /**
     * Removes a pending reaction after it has been successfully sent.
     */
    fun removePendingReaction(id: Long): Boolean {
        return try {
            writableDatabase.delete("pending_reactions", "id = ?", arrayOf(id.toString())) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error removing pending reaction", e)
            false
        }
    }

    /**
     * Clears all pending reactions (e.g., for testing or cleanup).
     */
    fun clearAllPendingReactions(): Boolean {
        return try {
            writableDatabase.delete("pending_reactions", null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing pending reactions", e)
            false
        }
    }
}

// Data class for pending reactions
data class PendingReaction(
    val id: Long,
    val messageGuid: Long,
    val chatId: Long?,
    val contactPubkey: ByteArray?,
    val emoji: String,
    val add: Boolean,
    val timestamp: Long
)

// Data classes for group chat support
data class GroupChatInfo(
    val chatId: Long,
    val name: String,
    val description: String?,
    val avatarPath: String?,
    val mediatorPubkey: ByteArray,
    val ownerPubkey: ByteArray,
    val sharedKey: ByteArray,
    val createdAt: Long,
    val updatedAt: Long,
    val lastMessageTime: Long,
    val unreadCount: Int,
    val muted: Boolean
)

data class GroupMessage(
    val localId: Long,
    val msgId: Long?,
    val guid: Long,
    val senderId: Long,
    val timestamp: Long,
    val type: Int,
    val data: ByteArray?,
    val delivered: Boolean,
    val read: Boolean,
    val replyTo: Long = 0L
)

data class GroupInvite(
    val id: Long,
    val chatId: Long,
    val sender: ByteArray,
    val senderName: String,
    val timestamp: Long,
    val chatName: String,
    val chatDescription: String?,
    val chatAvatarPath: String?,
    val encryptedData: ByteArray,
    val status: Int // 0=pending, 1=accepted, 2=rejected
)

data class GroupMemberInfo(
    val pubkey: ByteArray,
    val nickname: String?,
    val info: String?,
    val avatarPath: String?,
    val permissions: Int,
    val joinedAt: Long,
    val banned: Boolean,
    val online: Boolean = false
)

data class AccountInfo(val name: String, val info: String, val avatar: String, val updated: Long, val clientId: Int, val keyPair: AsymmetricCipherKeyPair)
data class Peer(val address: String, val clientId: Int, val priority: Int, val expiration: Long)

/**
 * Represents a reaction on a message.
 * @param id Database row ID
 * @param messageGuid GUID of the message being reacted to
 * @param chatId Group chat ID (null for personal chats)
 * @param reactorPubkey Public key of the user who reacted
 * @param emoji The emoji reaction (UTF-8 string)
 * @param timestamp When the reaction was added
 */
data class Reaction(
    val id: Long,
    val messageGuid: Long,
    val chatId: Long?,
    val reactorPubkey: ByteArray,
    val emoji: String,
    val timestamp: Long
)

interface StorageListener {
    fun onContactAdded(id: Long) {}
    fun onContactRemoved(id: Long) {}
    fun onContactChanged(id: Long) {}

    fun onMessageSent(id: Long, contactId: Long) {}
    fun onMessageDelivered(id: Long, delivered: Boolean) {}
    fun onMessageReceived(id: Long, contactId: Long): Boolean { return false }
    fun onMessageRead(id: Long, contactId: Long) {}

    fun onGroupMessageReceived(chatId: Long, id: Long, contactId: Long): Boolean { return false }
    fun onGroupMessageRead(chatId: Long, id: Long) {}
    fun onGroupChatChanged(chatId: Long): Boolean { return false }
    fun onGroupInviteReceived(inviteId: Long, chatId: Long, fromPubkey: ByteArray) {}

    fun onReactionAdded(messageGuid: Long, chatId: Long?, emoji: String) {}
    fun onReactionRemoved(messageGuid: Long, chatId: Long?, emoji: String) {}
}