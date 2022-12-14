package com.revertron.mimir.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.Settings
import android.util.Log
import com.revertron.mimir.NotificationManager
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
        const val DATABASE_VERSION = 2
        const val DATABASE_NAME = "data.db"
        const val CREATE_ACCOUNTS = "CREATE TABLE accounts (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, privkey TEXT, pubkey TEXT, client INTEGER)"
        const val CREATE_CONTACTS = "CREATE TABLE contacts (id INTEGER PRIMARY KEY AUTOINCREMENT, pubkey TEXT, name TEXT)"
        const val CREATE_IPS = "CREATE TABLE ips (id INTEGER, client INTEGER, address TEXT, expiration INTEGER)"
        const val CREATE_MESSAGES = "CREATE TABLE messages (id INTEGER PRIMARY KEY, contact INTEGER, incoming BOOL, delivered BOOL, time INTEGER, type INTEGER, message TEXT, read BOOL)"
    }

    data class Message(val id: Long, val contact: Long, val incoming: Boolean, var delivered: Boolean, val time: Long, val type: Int, val message: String, val read: Boolean)

    val listeners = mutableListOf<StorageListener>()
    private val notificationManager = NotificationManager(context)
    private val androidId: Int = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.hashCode() ?: 0

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_ACCOUNTS)
        db.execSQL(CREATE_CONTACTS)
        db.execSQL(CREATE_IPS)
        db.execSQL(CREATE_MESSAGES)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (newVersion > oldVersion && newVersion == 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN read BOOL DEFAULT 1");
        }
    }

    fun addContact(pubkey: String, name: String): Long {
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

    fun saveIp(pubkey: String, address: String, clientId: Int): Boolean {
        for (ip in getContactIps(pubkey)) {
            if (ip == address) {
                //TODO renew TTL in the table, not ignore it
                Log.d(TAG, "Already have this IP")
                return false
            }
        }
        // First we get numeric id for this contact
        var id = getContactId(pubkey)
        return if (id >= 0) {
            Log.i(TAG, "Found contact id $id")
            saveIp(id, address, clientId)
        } else {
            id = addContact(pubkey, "Unknown")
            Log.i(TAG, "Created contact id $id")
            saveIp(id, address, clientId)
        }
    }

    private fun saveIp(id: Long, address: String, clientId: Int): Boolean {
        val expiration = System.currentTimeMillis() / 1000 + 365 * 86400

        var values = ContentValues().apply {
            put("address", address)
            put("expiration", expiration)
        }
        if (writableDatabase.update("ips", values, "id = ? AND (client = ? OR client = 0)", arrayOf("$id", "$clientId")) > 0) {
            return true
        }

        values = ContentValues().apply {
            put("id", id)
            put("client", clientId)
            put("address", address)
            put("expiration", expiration) // In seconds
        }
        return this.writableDatabase.insert("ips", null, values) >= 0
    }

    fun renameContact(contactId: Long, name: String) {
        val values = ContentValues().apply {
            put("name", name)
        }
        writableDatabase.update("contacts", values, "id = ?", arrayOf("$contactId"))
    }

    fun addMessage(contact: String, incoming: Boolean, delivered: Boolean, time: Long, type: Int, message: String): Long {
        var id = getContactId(contact)
        if (id <= 0) {
            id = addContact(contact, "Unknown")
        }
        val values = ContentValues().apply {
            put("contact", id)
            put("incoming", incoming)
            put("delivered", delivered)
            put("time", time)
            put("type", type)
            put("message", message)
            put("read", !incoming)
        }
        return this.writableDatabase.insert("messages", null, values).also {
            var processed = false
            for (listener in listeners) {
                if (!incoming) {
                    listener.onMessageSent(it, id, message)
                } else {
                    processed = processed or listener.onMessageReceived(it, id, message)
                }
            }
            if (!incoming) {
                notificationManager.onMessageSent(it, id, message)
            } else {
                if (!processed) {
                    notificationManager.onMessageReceived(it, id, message)
                }
            }
        }
    }

    fun setMessageDelivered(to: String, id: Long, delivered: Boolean) {
        var contact = getContactId(to)
        if (contact <= 0) {
            contact = addContact(to, "Unknown")
        }
        val values = ContentValues().apply {
            put("delivered", delivered)
        }
        if (this.writableDatabase.update("messages", values, "id = ? AND contact = ?", arrayOf("$id", "$contact")) > 0) {
            Log.i(TAG, "Message $id delivered = $delivered")
        }
        for (listener in listeners) {
            listener.onMessageDelivered(id, delivered)
        }
        notificationManager.onMessageDelivered(id, delivered)
    }

    fun setMessageRead(contactId: Long, id: Long, read: Boolean) {
        val values = ContentValues().apply {
            put("read", read)
        }
        if (this.writableDatabase.update("messages", values, "id = ? AND contact = ?", arrayOf("$id", "$contactId")) > 0) {
            Log.i(TAG, "Message $id read = $read")
        }
    }

    fun getMessages(pubkey: String): List<Message> {
        val id = getContactId(pubkey)
        if (id <= 0) {
            return emptyList()
        }
        val list = mutableListOf<Message>()
        val db = this.readableDatabase
        val columns = arrayOf("id", "incoming", "delivered", "time", "type", "message", "read")
        val cursor = db.query("messages", columns, "contact = ?", arrayOf(id.toString()), null, null, "time", "")
        while (cursor.moveToNext()) {
            val messId = cursor.getLong(0)
            val incoming = cursor.getInt(1) != 0
            val delivered = cursor.getInt(2) != 0
            val time = cursor.getLong(3)
            val type = cursor.getInt(4)
            val message = cursor.getString(5)
            val read = cursor.getInt(6) != 0
            //Log.i(TAG, "$message ::: $messId")
            list.add(Message(messId, id, incoming, delivered, time, type, message, read))
        }
        cursor.close()
        return list
    }

    fun getMessageIds(userId: Long): List<Long> {
        val list = mutableListOf<Long>()
        val cursor = readableDatabase.query("messages", arrayOf("id"), "contact = ?", arrayOf("$userId"), null, null, "time")
        while (cursor.moveToNext()) {
            list.add(cursor.getLong(0))
        }
        cursor.close()
        return list
    }

    private fun getUnreadCount(userId: Long): Int {
        val db = this.readableDatabase
        val cursor =
            db.query("messages", arrayOf("count(read)"), "contact = ? AND read = false", arrayOf("$userId"), null, null, null)
        val count = if (cursor.moveToNext()) {
            cursor.getInt(0)
        } else {
            -1
        }
        cursor.close()
        return count
    }

    fun getLastMessage(userId: Long): String? {
        var result: String? = null
        val cursor = readableDatabase.query("messages", arrayOf("message"), "contact = ? AND incoming = true", arrayOf("$userId"), null, null, "time DESC", "1")
        if (cursor.moveToNext()) {
            result = cursor.getString(0)
        }
        cursor.close()
        return result
    }

    fun getMessage(messageId: Long): Message? {
        val columns = arrayOf("contact", "incoming", "delivered", "time", "type", "message", "read")
        val cursor = readableDatabase.query("messages", columns, "id = ?", arrayOf("$messageId"), null, null, "time", "1")
        if (cursor.moveToNext()) {
            val contactId = cursor.getLong(0)
            val incoming = cursor.getInt(1) != 0
            val delivered = cursor.getInt(2) != 0
            val time = cursor.getLong(3)
            val type = cursor.getInt(4)
            val message = cursor.getString(5)
            val read = cursor.getInt(6) != 0
            //Log.i(TAG, "$message ::: $messId")
            cursor.close()
            return Message(messageId, contactId, incoming, delivered, time, type, message, read)
        }
        cursor.close()
        return null
    }

    fun getContactId(pubkey: String): Long {
        val db = this.readableDatabase
        val cursor =
            db.query("contacts", arrayOf("id"), "pubkey = ?", arrayOf(pubkey), null, null, null)
        val id = if (cursor.moveToNext()) {
            cursor.getLong(0)
        } else {
            -1
        }
        cursor.close()
        return id
    }

    fun getContactPubkey(id: Long): String {
        val db = this.readableDatabase
        val cursor =
            db.query("contacts", arrayOf("pubkey"), "id = ?", arrayOf(id.toString()), null, null, null)
        val pubkey = if (cursor.moveToNext()) {
            cursor.getString(0)
        } else {
            ""
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
            "Unknown"
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
            val pubkey = cursor.getString(1)
            val name = cursor.getString(2)
            list.add(Contact(id, pubkey, name, "", 0))
        }
        cursor.close()
        for (c in list) {
            c.unread = getUnreadCount(c.id)
            c.lastMessage = getLastMessage(c.id).orEmpty()
        }

        return list
    }

    fun getContactIps(pubkey: String): List<String> {
        // First we get numeric id for this contact
        val id = getContactId(pubkey)
        val db = this.readableDatabase
        if (id >= 0) {
            Log.i(TAG, "Found contact id $id")
            val curTime = System.currentTimeMillis() / 1000
            val list = mutableListOf<String>()
            val cursor = db.query("ips", arrayOf("address", "expiration"), "id = ?",
                arrayOf(id.toString()), null, null, null, null)
            while (cursor.moveToNext()) {
                val address = cursor.getString(0)
                val expiration = cursor.getLong(1)
                if (curTime < expiration) {
                    list.add(address)
                }
            }
            cursor.close()
            return list
        }
        return emptyList()
    }

    fun getAccountInfo(id: Int): AccountInfo {
        var db = this.readableDatabase
        val cursor = db.query("accounts", arrayOf("name", "privkey", "pubkey", "client"), "id = ?", arrayOf(id.toString()), null, null, null)
        if (cursor.moveToNext()) {
            val name = cursor.getString(0)
            val privkey = Hex.decode(cursor.getString(1))
            val pubkeyString = cursor.getString(2)
            val pubkey = Hex.decode(pubkeyString)
            val clientId = cursor.getInt(3) xor androidId
            val priv = Ed25519PrivateKeyParameters(privkey)
            val pub = Ed25519PublicKeyParameters(pubkey)
            cursor.close()
            Log.i(TAG, "Found account $name with pubkey $pubkeyString")
            return AccountInfo(name, clientId, AsymmetricCipherKeyPair(pub, priv))
        }
        // If there is no account with that id we generate new
        val gen = Ed25519KeyPairGenerator()
        gen.init(KeyGenerationParameters(SecureRandom(), 256))
        val pair = gen.generateKeyPair()
        val name = "Default"
        val pub = Hex.toHexString((pair.public as Ed25519PublicKeyParameters).encoded)
        val priv = Hex.toHexString((pair.private as Ed25519PrivateKeyParameters).encoded)
        val clientId = Random(System.currentTimeMillis()).run { nextInt() }
        db = this.writableDatabase
        val values = ContentValues().apply {
            put("name", name)
            put("privkey", priv)
            put("pubkey", pub)
            put("client", clientId)
        }
        db.insert("accounts", null, values)
        return AccountInfo(name, clientId, pair)
    }

    fun updateName(id: Int, name: String): Boolean {
        val values = ContentValues().apply {
            put("name", name)
        }
        return this.writableDatabase.update("accounts", values, "id = ?", arrayOf("$id")) > 0
    }

    fun removeContactAndChat(id: Long) {
        val db = writableDatabase
        if (db.delete("contacts", "id = ?", arrayOf("$id")) > 0) {
            db.delete("messages", "contact = ?", arrayOf("$id"))
            db.delete("ips", "id = ?", arrayOf("$id"))
        }
    }
}

data class AccountInfo(val name: String, val clientId: Int, val keyPair: AsymmetricCipherKeyPair)

interface StorageListener {
    fun onContactAdded(id: Long) {}
    fun onContactRemoved(id: Long) {}
    fun onContactChanged(id: Long) {}

    fun onMessageSent(id: Long, contactId: Long, message: String) {}
    fun onMessageDelivered(id: Long, delivered: Boolean) {}
    fun onMessageReceived(id: Long, contactId: Long, message: String): Boolean { return false }
}