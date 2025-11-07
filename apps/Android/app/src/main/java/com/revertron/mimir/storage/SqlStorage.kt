package com.revertron.mimir.storage

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDoneException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
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
        const val DATABASE_VERSION = 9
        const val DATABASE_NAME = "data.db"
        const val CREATE_ACCOUNTS = "CREATE TABLE accounts (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, privkey TEXT, pubkey TEXT, client INTEGER, info TEXT, avatar TEXT, updated INTEGER)"
        const val CREATE_CONTACTS = "CREATE TABLE contacts (id INTEGER PRIMARY KEY AUTOINCREMENT, pubkey BLOB, name TEXT, info TEXT, avatar TEXT, updated INTEGER, renamed BOOL, last_seen INTEGER)"
        const val CREATE_IPS = "CREATE TABLE ips (id INTEGER, client INTEGER, address TEXT, port INTEGER DEFAULT 5050, priority INTEGER DEFAULT 3, expiration INTEGER DEFAULT 3600)"
        const val CREATE_MESSAGES = "CREATE TABLE messages (id INTEGER PRIMARY KEY, contact INTEGER, guid INTEGER, replyTo INTEGER, incoming BOOL, delivered BOOL, read BOOL, time INTEGER, edit INTEGER, type INTEGER, message BLOB)"

        // Group chat tables (version 8+)
        const val CREATE_GROUP_CHATS = "CREATE TABLE group_chats (chat_id INTEGER PRIMARY KEY, name TEXT NOT NULL, description TEXT, avatar TEXT, mediator_pubkey BLOB NOT NULL, owner_pubkey BLOB NOT NULL, shared_key BLOB NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, last_message_time INTEGER DEFAULT 0, unread_count INTEGER DEFAULT 0, muted BOOL DEFAULT 0)"
        const val CREATE_GROUP_INVITES = "CREATE TABLE group_invites (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL, from_pubkey BLOB NOT NULL, timestamp INTEGER NOT NULL, chat_name TEXT NOT NULL, chat_description TEXT, chat_avatar TEXT, encrypted_data BLOB NOT NULL, status INTEGER DEFAULT 0)"
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
                        json.getString("text")
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

        if (newVersion > oldVersion && newVersion == 8) {
            // Add group chat support
            db.execSQL(CREATE_GROUP_CHATS)
            db.execSQL(CREATE_GROUP_INVITES)
        }

        if (newVersion > oldVersion && newVersion == 9) {
            migrateGroupMessageIncomingColumn(db)
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

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { info ->
            while (info.moveToNext()) if (info.getString(1) == column) return true
            false
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
            Log.i(TAG, "Message $id with guid $guid delivered = $delivered")
            for (listener in listeners) {
                listener.onMessageDelivered(id, delivered)
            }
            notificationManager.onMessageDelivered(id, delivered)
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
        val values = ContentValues().apply {
            put("read", read)
        }
        if (this.writableDatabase.update(messagesTable, values, "id = ?", arrayOf("$id")) > 0) {
            if (read) {
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
    fun getMessage(chatId: Long, messageId: Long): Message? {
        val messagesTable = "messages_$chatId"
        var result: Message? = null

        try {
            val cursor = readableDatabase.query(
                messagesTable,
                arrayOf("guid", "senderId", "incoming", "timestamp", "type", "data", "delivered", "read"),
                "id = ?",
                arrayOf("$messageId"),
                null, null, null, "1"
            )

            if (cursor.moveToNext()) {
                val guid = cursor.getLong(0)
                val senderId = cursor.getLong(1)
                val incoming = cursor.getInt(2) != 0
                val timestamp = cursor.getLong(3)
                val type = cursor.getInt(4)
                val data = cursor.getBlobOrNull(5)
                val delivered = cursor.getInt(6) != 0
                val read = cursor.getInt(7) != 0

                result = Message(
                    id = messageId,
                    contact = senderId,
                    guid = guid,
                    replyTo = 0L, // TODO: support replies
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

    private fun getMessageIdByGuid(guid: Long): Long {
        val db = this.readableDatabase
        val statement = db.compileStatement("SELECT id FROM messages WHERE guid=? LIMIT 1")
        statement.bindLong(1, guid)
        val result = try {
            statement.simpleQueryForLong()
        } catch (e: SQLiteDoneException) {
            -1
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
        val list = mutableListOf<GroupMemberInfo>()

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
            Log.i(TAG, "Found account $name with pubkey ${Hex.toHexString(pubkey)}")
            return AccountInfo(name, info, avatar, updated, clientId, AsymmetricCipherKeyPair(pub, priv))
        }
        cursor.close()
        Log.w(TAG, "Didn't find account info $id, or it didn't change since ${Date(ifUpdatedSince * 1000)}")
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
        // Firstly, we delete avatar file
        val cursor = this.readableDatabase.query("contacts", arrayOf("avatar"), "id = ?", arrayOf("$id"), null, null, null)
        if (cursor.moveToNext()) {
            val fileName = cursor.getString(0)
            if (fileName != null && fileName.isNotEmpty()) {
                val avatarsDir = File(this.context.filesDir, "avatars")
                val file = File(avatarsDir, fileName)
                file.delete()
            }
        }
        cursor.close()

        // TODO remove all files belonging to messages
        val db = writableDatabase
        if (db.delete("contacts", "id = ?", arrayOf("$id")) > 0) {
            db.delete("messages", "contact = ?", arrayOf("$id"))
            db.delete("ips", "id = ?", arrayOf("$id"))
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
                banned BOOL DEFAULT 0                
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
                read BOOL DEFAULT 0
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
            // Drop per-chat tables
            dropGroupChatTables(db, chatId)

            // Delete from main table
            val result = db.delete("group_chats", "chat_id = ?", arrayOf(chatId.toLong().toString())) > 0

            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Adds a message to a group chat.
     */
    fun addGroupMessage(chatId: Long, serverMsgId: Long?, guid: Long, author: ByteArray, timestamp: Long, type: Int, system: Boolean, data: ByteArray): Long {
        val membersTable = "members_$chatId"
        val cursor = readableDatabase.query(
            membersTable,
            arrayOf("id"),
            "pubkey = ?", arrayOf(author.toHexString()), null, null,
            "id DESC",
            "1"
        )
        val senderId = if (cursor.moveToNext()) {
            cursor.getLong(0)
        } else {
            Log.w(TAG, "Error getting id of sender id!")
            cursor.close()
            return -1
        }
        cursor.close()

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
        }

        val id = writableDatabase.insert(messagesTable, null, values)
        if (id > 0) {
            // Update last message time
            val updateValues = ContentValues().apply {
                put("last_message_time", timestamp)
            }
            writableDatabase.update("group_chats", updateValues, "chat_id = ?", arrayOf(chatId.toLong().toString()))

            // Notify listeners
            for (listener in listeners) {
                listener.onGroupMessageReceived(chatId, id, senderId)
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
                arrayOf("id", "msg_id", "guid", "senderId", "timestamp", "type", "data", "delivered", "read"),
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
                    read = cursor.getInt(8) != 0
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
     * Gets pending group invites.
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
            list.add(GroupInvite(
                id = cursor.getLong(0),
                chatId = cursor.getLong(1),
                sender = cursor.getBlob(2),
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
            arrayOf("pubkey", "nickname", "info", "avatar", "permissions", "joined_at", "banned"),
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
                banned = cursor.getInt(6) != 0
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
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
            if (bitmap != scaled) bitmap.recycle()
            BitmapDrawable(this.context.resources, scaled)
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
}

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
    val read: Boolean
)

data class GroupInvite(
    val id: Long,
    val chatId: Long,
    val sender: ByteArray,
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
    val banned: Boolean
)

data class AccountInfo(val name: String, val info: String, val avatar: String, val updated: Long, val clientId: Int, val keyPair: AsymmetricCipherKeyPair)
data class Peer(val address: String, val clientId: Int, val priority: Int, val expiration: Long)

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
}