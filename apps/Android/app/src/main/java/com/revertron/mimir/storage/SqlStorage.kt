package com.revertron.mimir.storage

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDoneException
import android.database.sqlite.SQLiteOpenHelper
import android.provider.Settings
import android.util.Log
import androidx.core.database.getBlobOrNull
import com.revertron.mimir.NotificationManager
import com.revertron.mimir.getUtcTime
import com.revertron.mimir.ui.Contact
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.KeyGenerationParameters
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import java.security.SecureRandom
import java.util.*

class SqlStorage(context: Context): SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val TAG = "SqlStorage"
        // If we change the database schema, we must increment the database version.
        const val DATABASE_VERSION = 7
        const val DATABASE_NAME = "data.db"
        const val CREATE_ACCOUNTS = "CREATE TABLE accounts (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, privkey TEXT, pubkey TEXT, client INTEGER, info TEXT, avatar TEXT, updated INTEGER)"
        const val CREATE_CONTACTS = "CREATE TABLE contacts (id INTEGER PRIMARY KEY AUTOINCREMENT, pubkey BLOB, name TEXT, info TEXT, avatar TEXT, updated INTEGER, renamed BOOL, last_seen INTEGER)"
        const val CREATE_IPS = "CREATE TABLE ips (id INTEGER, client INTEGER, address TEXT, port INTEGER DEFAULT 5050, priority INTEGER DEFAULT 3, expiration INTEGER DEFAULT 3600)"
        const val CREATE_MESSAGES = "CREATE TABLE messages (id INTEGER PRIMARY KEY, contact INTEGER, guid INTEGER, replyTo INTEGER, incoming BOOL, delivered BOOL, read BOOL, time INTEGER, edit INTEGER, type INTEGER, message BLOB)"
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
        fun getText(): String {
            return if (data != null) {
                String(data)
            } else {
                "<Empty>"
            }
        }
    }

    val listeners = mutableListOf<StorageListener>()
    private val notificationManager = NotificationManager(context)
    @SuppressLint("HardwareIds")
    private val androidId: Int = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.hashCode() ?: 0

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_ACCOUNTS)
        db.execSQL(CREATE_CONTACTS)
        db.execSQL(CREATE_IPS)
        db.execSQL(CREATE_MESSAGES)
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
        writableDatabase.execSQL("DELETE FROM ips")
        writableDatabase.execSQL("VACUUM")

        val columns = arrayOf("id", "name", "privkey", "pubkey", "client", "info", "avatar", "updated")
        val cursor = readableDatabase.query("accounts", columns, null, null, null, null, "id", null)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            val name = cursor.getString(1)
            val privkey = cursor.getBlob(2)
            val pubkey = cursor.getBlob(3)
            val client = cursor.getInt(4)
            val info = cursor.getString(5)
            val avatar = cursor.getString(6)
            val updated = cursor.getLong(7)

            Log.i(TAG, "$id $name, ${Hex.toHexString(privkey)}, ${Hex.toHexString(pubkey)}, $client, $info, $avatar, $updated")
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
            put("read", !incoming)
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

    fun getMessageIds(userId: Long): List<Long> {
        val list = mutableListOf<Long>()
        val cursor = readableDatabase.query("messages", arrayOf("id"), "contact = ?", arrayOf("$userId"), null, null, "id")
        while (cursor.moveToNext()) {
            list.add(cursor.getLong(0))
        }
        cursor.close()
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

    private fun getLastMessage(userId: Long): Pair<String?, Long> {
        var message: String? = null
        var time = 0L
        val cursor = readableDatabase.query("messages", arrayOf("type", "message", "time"), "contact = ?", arrayOf("$userId"), null, null, "id DESC", "1")
        if (cursor.moveToNext()) {
            val type = cursor.getInt(0)
            val data = cursor.getBlob(1)
            if (type == 0) {
                message = String(data)
            }
            time = cursor.getLong(2)
        }
        cursor.close()
        return message to time
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

    fun getContactList(): List<Contact> {
        val list = mutableListOf<Contact>()
        val db = this.readableDatabase
        val cursor = db.query("contacts", arrayOf("id", "pubkey", "name"), "", emptyArray(), null, null, "name", "")
        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            val pubkey = cursor.getBlob(1)
            val name = cursor.getString(2)
            list.add(Contact(id, pubkey, name, "", 0L, false, 0))
        }
        cursor.close()
        for (c in list) {
            c.unread = getUnreadCount(c.id)
            c.lastMessageDelivered = getLastMessageDelivered(c.id)
            val lastMessage = getLastMessage(c.id)
            c.lastMessage = lastMessage.first.orEmpty()
            c.lastMessageTime = lastMessage.second
        }
        list.sortByDescending { it.lastMessageTime }

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
                val port = cursor.getShort(1)
                val client = cursor.getInt(2)
                val priority = cursor.getInt(3)
                val expiration = cursor.getLong(4)
                if (curTime <= expiration) {
                    list.add(Peer(address, port, client, priority, expiration))
                }
            }
            Log.i(TAG, "Found ips: $list")
            cursor.close()
            return list
        }
        return emptyList()
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
        if (db.delete("contacts", "id = ?", arrayOf("$id")) > 0) {
            db.delete("messages", "contact = ?", arrayOf("$id"))
            db.delete("ips", "id = ?", arrayOf("$id"))
        }
    }

    fun deleteMessage(messageId: Long) {
        writableDatabase.delete("messages", "id=?", arrayOf("$messageId"))
    }

    private fun generateGuid(time: Long, data: ByteArray): Long {
        return (data.contentHashCode().toLong() shl 32) xor time
    }
}

data class AccountInfo(val name: String, val info: String, val avatar: String, val updated: Long, val clientId: Int, val keyPair: AsymmetricCipherKeyPair)
data class Peer(val address: String, val port: Short, val clientId: Int, val priority: Int, val expiration: Long)

interface StorageListener {
    fun onContactAdded(id: Long) {}
    fun onContactRemoved(id: Long) {}
    fun onContactChanged(id: Long) {}

    fun onMessageSent(id: Long, contactId: Long) {}
    fun onMessageDelivered(id: Long, delivered: Boolean) {}
    fun onMessageReceived(id: Long, contactId: Long): Boolean { return false }
    fun onMessageRead(id: Long, contactId: Long) {}
}