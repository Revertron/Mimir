package com.revertron.mimir.net

import android.util.Log
import com.revertron.mimir.App
import com.revertron.mimir.getUtcTime
import com.revertron.mimir.storage.Peer
import com.revertron.mimir.storage.SqlStorage
import com.revertron.mimir.yggmobile.Messenger
import com.revertron.mimir.yggmobile.Yggmobile
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import org.json.JSONArray
import java.io.IOException
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.HashMap

//TODO Remove it, we have no ports now
const val CONNECTION_PORT: Short = 5050
private const val CONNECTION_TRIES = 5
private const val CONNECTION_TIMEOUT = 3000
private const val CONNECTION_PERIOD = 1000L
//TODO Get from DNS TXT record
private const val TRACKER_ADDR = "801bc33a735cded0588284af0bf1b8bdb4138f122a9c17ecee089e9ff151c3f6"

class MimirServer(
    val storage: SqlStorage,
    private val clientId: Int,
    private val keyPair: AsymmetricCipherKeyPair,
    private val listener: EventListener,
    private val infoProvider: InfoProvider,
    private val port: Int
): Thread(TAG), EventListener, ResolverReceiver {

    companion object {
        const val TAG: String = "MimirServer"
    }

    private val working = AtomicBoolean(true)
    private val connections = HashMap<String, ConnectionHandler>(5)
    private val connectContacts = HashSet<String>(5)
    private lateinit var messenger: Messenger
    private var hasNewMessages = false
    private lateinit var resolver: Resolver
    private val pubkey = (keyPair.public as Ed25519PublicKeyParameters).encoded
    private val privkey = (keyPair.private as Ed25519PrivateKeyParameters).encoded
    private var lastAnnounceTime = 0L
    private var announceTtl = 0L

    override fun run() {
        val bootstrapPeers = arrayOf(
            "tls://109.176.250.101:65534",
            "tls://37.205.14.171:993",
            "tcp://62.210.85.80:39565",
            "tcp://51.15.204.214:12345",
            "tls://45.95.202.21:443",
            "tcp://y.zbin.eu:7743"
        )

        val addr = bootstrapPeers.random()
        Log.i(TAG, "Selected random peer: $addr")

        messenger = Yggmobile.newMessenger(addr)
        val myPub = messenger.publicKey()
        val hexPub = Hex.toHexString(myPub)
        val tracker = Hex.decode(TRACKER_ADDR)
        Log.i(TAG, "My network ADDR: $hexPub")
        resolver = Resolver(storage, messenger, tracker)
        startResendThread()
        startOnlineStateThread()
        val peer = Peer(hexPub, clientId, 3, 0)
        startAnnounceThread(pubkey, privkey, peer, this)
        while (working.get()) {
            try {
                sleep(1000)
                Log.i(TAG, "Accepting connections...")
                val newConnection = messenger.accept()
                val pub = Hex.toHexString(newConnection.publicKey())
                Log.i(TAG, "New client from: ${pub}")
                // Use threads for each client to communicate with them simultaneously
                val connection = ConnectionHandler(clientId, keyPair, newConnection, this, infoProvider)
                connection.peerStatus = Status.ConnectedIn
                synchronized(connections) {
                    connections[pub] = connection
                }
                connection.start()

            } catch (e: IOException) {
                e.printStackTrace()
                Log.e(TAG, "Error creating server socket or accepting connection")
                lastAnnounceTime = 0L
            } catch (e: SecurityException) {
                e.printStackTrace()
                Log.e(TAG, "Security manager doesn't allow binding socket")
                break
            }
        }
    }

    fun stopServer() {
        working.set(false)
    }

    private fun startAnnounceThread(pubkey: ByteArray, privkey: ByteArray, peer: Peer, receiver: ResolverReceiver) {
        Thread {
            while (working.get()) {
                sleep(10000)
                try {
                    if (App.app.online && getUtcTime() >= lastAnnounceTime + announceTtl) {
                        resolver.announce(pubkey, privkey, peer, receiver)
                        listener.onTrackerPing(false)
                    }
                } catch (e: SocketTimeoutException) {
                    Log.e(TAG, "Error announcing our address: $e")
                    lastAnnounceTime = 0L
                }
            }
        }.start()
    }

    private fun startResendThread() {
        Thread {
            while (working.get()) {
                // We try to resend messages once in 2 minutes
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 120000) {
                    sleep(1000)
                    if (hasNewMessages) {
                        hasNewMessages = false
                        break
                    }
                }
                if (working.get()) {
                    sendUnsent()
                } else {
                    break
                }
            }
        }.start()
    }

    private fun startOnlineStateThread() {
        Thread {
            while (working.get()) {
                sleep(3000)
                val peersJSON = messenger.peersJSON
                if (peersJSON != null && peersJSON != "null") {
                    val peers = JSONArray(peersJSON)
                    if (peers.length() > 0) {
                        val peer = peers.getJSONObject(0)
                        val up = peer.getBoolean("Up")
                        App.app.online = up
                    } else {
                        App.app.online = false
                    }
                }
            }
        }.start()
    }

    fun sendMessages() {
        hasNewMessages = true
    }

    fun reconnectPeers() {
        messenger.retryPeersNow()
    }

    private fun sendUnsent() {
        if (!App.app.online) return

        Log.i(TAG, "Sending unsent messages")
        hasNewMessages = false
        val contacts = storage.getContactsWithUnsentMessages()
        Log.i(TAG, "Contacts with unsent messages: ${contacts.size}")
        for (contact in contacts) {
            connectContact(contact)
        }
    }

    private fun connectContact(contact: ByteArray) {
        val contactString = Hex.toHexString(contact)
        Log.i(TAG, "Connecting to $contactString")
        synchronized(connections) {
            if (connections.contains(contactString)) {
                Log.i(TAG, "Found established connection, trying to send")
                sendUnsentMessages(contact)
                return
            }
        }
        synchronized(connectContacts) {
            if (connectContacts.contains(contactString)) {
                Log.i(TAG, "We already are connecting to $contactString")
                return
            }
            // Keeping some track of connecting contacts
            connectContacts.add(contactString)
        }
        Thread {
            Log.d(TAG, "Starting connection to $contactString")
            val peers = storage.getContactPeers(contact)
            val receiver = object : ResolverReceiver {
                override fun onResolveResponse(pubkey: ByteArray, ips: List<Peer>) {
                    Log.i(TAG, "Resolved IPS: $ips")
                    val newIps = ips.subtract(peers.toSet())
                    if (newIps.isNotEmpty()) {
                        newIps.forEach {
                            storage.saveIp(pubkey, it.address, 0, it.clientId, it.priority, it.expiration)
                        }
                        // We don't want to make an endless loop, so we give it a null receiver
                        connectContact(contact, contactString, newIps.toList(), null)
                    } else {
                        Log.i(TAG, "Didn't find alive IPs for $contactString, giving up")
                        synchronized(connectContacts) {
                            connectContacts.remove(contactString)
                        }
                    }
                }

                override fun onAnnounceResponse(pubkey: ByteArray, ttl: Long) {}
                override fun onError(pubkey: ByteArray) {
                    Log.e(TAG, "Error resolving IPs")
                    synchronized(connectContacts) {
                        connectContacts.remove(contactString)
                    }
                }
            }

            connectContact(contact, contactString, peers, receiver)
        }.start()
    }

    private fun connectContact(contact: ByteArray, contactString: String, peers: List<Peer>, receiver: ResolverReceiver?) {
        if (peers.isNotEmpty()) {
            val sortedPeers = peers
                .sortedBy { it.priority }
                .distinctBy { it.address }
            Log.i(TAG, "Found ${sortedPeers.size} peers for contact $contactString")
            var connected = false
            for (peer in sortedPeers) {
                val connection = connect(contact, peer)
                if (connection != null) {
                    Log.i(TAG, "Created new connection to $contactString")
                    synchronized(connections) {
                        connections[contactString] = connection
                    }
                    synchronized(connectContacts) {
                        connectContacts.remove(contactString)
                    }
                    connected = true
                    break
                } else {
                    Log.e(TAG, "Can not connect to $contactString")
                    synchronized(connectContacts) {
                        connectContacts.remove(contactString)
                    }
                }
            }
            if (!connected && receiver != null) {
                Log.i(TAG, "Locally found IPs are dead, resolving more")
                resolver.resolveAddrs(contact, receiver)
            }
        } else {
            if (receiver != null) {
                Log.i(TAG, "No local ips found, resolving")
                resolver.resolveAddrs(contact, receiver)
            }
        }
    }

    private fun sendUnsentMessages(contact: ByteArray) {
        val unsentMessages = storage.getUnsentMessages(contact)
        if (unsentMessages.isEmpty()) {
            return
        }
        Log.i(TAG, "Found ${unsentMessages.size} messages, sending")
        val publicKey = Hex.toHexString(contact)
        for (m in unsentMessages) {
            val message = storage.getMessage(m)
            if (message?.data != null) {
                synchronized(connections) {
                    val connection = connections[publicKey]
                    Log.i(TAG, "Sending message ${message.guid} with id ${message.id} and time ${message.time}")
                    connection?.sendMessage(message.guid, message.replyTo, message.time, message.edit, message.type, message.data)
                }
            }
        }
    }

    private fun connect(recipient: ByteArray, peer: Peer): ConnectionHandler? {
        for (i in 1..CONNECTION_TRIES) {
            try {
                Log.d(TAG, "Connection attempt $i for ${peer.address}")
                val addr = Hex.decode(peer.address)
                val conn = messenger.connect(addr)
                // If there is no exception we should be connected
                val connection = ConnectionHandler(clientId, keyPair, conn, this, infoProvider)
                connection.peerStatus = Status.ConnectedOut
                connection.setPeerPublicKey(recipient)
                connection.start()
                return connection
            } catch (e: Exception) {
                //e.printStackTrace()
                Log.e(TAG, "Error connecting to $peer")
            }
            try {
                sleep(CONNECTION_PERIOD * i)
            } catch (e: InterruptedException) {
                // Nothing
            }
        }
        return null
    }

    override fun onServerStateChanged(online: Boolean) {
        listener.onServerStateChanged(online)
    }

    override fun onTrackerPing(online: Boolean) {
        listener.onTrackerPing(online)
    }

    override fun onClientIPChanged(old: String, new: String) {
        synchronized(connections) {
            if (connections.containsKey(old)) {
                val connectionHandler = connections.remove(old)!!
                connections[new] = connectionHandler
            }
        }
    }

    override fun onClientConnected(from: ByteArray, address: String, clientId: Int) {
        val pubkey = Hex.toHexString(from)
        Log.i(TAG, "onClientConnected from $pubkey")
        // When some client connects to us, we put `ConnectionHandler` in `connections` by the address
        // as we don't know the public key of newly connected client.
        // Now we can change it to correct key.
        synchronized(connections) {
            if (connections.containsKey(address)) {
                val connectionHandler = connections.remove(address)!!
                val publicKey = Hex.toHexString(from)
                connections[publicKey] = connectionHandler
            }
        }
        synchronized(connectContacts) {
            val publicKey = Hex.toHexString(from)
            connectContacts.remove(publicKey)
        }
        listener.onClientConnected(from, address, clientId)
        sendUnsentMessages(from)
    }

    override fun onMessageReceived(from: ByteArray, guid: Long, replyTo: Long, sendTime: Long, editTime: Long, type: Int, message: ByteArray) {
        listener.onMessageReceived(from, guid, replyTo, sendTime, editTime, type, message)
    }

    override fun onMessageDelivered(to: ByteArray, guid: Long, delivered: Boolean) {
        listener.onMessageDelivered(to, guid, delivered)
    }

    override fun onConnectionClosed(from: ByteArray, address: String) {
        synchronized(connections) {
            val pubKey = Hex.toHexString(from)
            Log.i(TAG, "Removing connection from $pubKey and $address")
            connections.remove(Hex.toHexString(from))
        }
    }

    override fun onResolveResponse(pubkey: ByteArray, ips: List<Peer>) {
        Log.d(TAG, "Resolved: $ips")
    }

    override fun onAnnounceResponse(pubkey: ByteArray, ttl: Long) {
        Log.d(TAG, "Got TTL: $ttl")
        lastAnnounceTime = getUtcTime()
        announceTtl = ttl
        listener.onTrackerPing(true)
    }

    override fun onError(pubkey: ByteArray) {
        Log.d(TAG, "Got timeout")
    }
}

interface EventListener {
    fun onServerStateChanged(online: Boolean)
    fun onTrackerPing(online: Boolean)
    fun onClientIPChanged(old: String, new: String) {}
    fun onClientConnected(from: ByteArray, address: String, clientId: Int)
    fun onMessageReceived(from: ByteArray, guid: Long, replyTo: Long, sendTime: Long, editTime: Long, type: Int, message: ByteArray)
    fun onMessageDelivered(to: ByteArray, guid: Long, delivered: Boolean)
    fun onConnectionClosed(from: ByteArray, address: String) {}
}

interface InfoProvider {
    fun getMyInfo(ifUpdatedSince: Long): InfoResponse?
    fun getContactUpdateTime(pubkey: ByteArray): Long
    fun updateContactInfo(pubkey: ByteArray, info: InfoResponse)
    fun getFilesDirectory(): String
}