package com.revertron.mimir.net

import android.util.Log
import com.revertron.mimir.getUtcTime
import com.revertron.mimir.getYggdrasilAddress
import com.revertron.mimir.storage.Peer
import com.revertron.mimir.storage.SqlStorage
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import java.io.IOException
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.HashMap

//TODO This port will be random, and clients will get it from trackers (or DNS)
const val CONNECTION_PORT: Short = 5050
private const val CONNECTION_TRIES = 5
private const val CONNECTION_TIMEOUT = 3000
private const val CONNECTION_PERIOD = 1000L
//TODO move to gradle config maybe?
private const val RESOLVER_ADDR = "[202:7991::880a:d4b2:de3b:2da1]"

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
    private var hasNewMessages = false
    private var serverSocket: ServerSocket? = null
    private lateinit var resolver: Resolver
    private val pubkey = (keyPair.public as Ed25519PublicKeyParameters).encoded
    private val privkey = (keyPair.private as Ed25519PrivateKeyParameters).encoded
    private var lastAnnounceTime = 0L
    private var announceTtl = 0L

    override fun run() {
        var online = false
        resolver = Resolver(storage, InetSocketAddress(RESOLVER_ADDR, CONNECTION_PORT.toInt()))
        startResendThread()
        while (working.get()) {
            var socket: Socket? = null
            try {
                Log.d(TAG, "Getting Yggdrasil address...")
                val localAddress = getYggdrasilAddress()
                if (localAddress == null) {
                    Log.e(TAG, "Could not start server, no Yggdrasil IP found")
                    online = false
                    sleep(10000)
                    continue
                }
                Log.i(TAG, "Starting on $localAddress")
                serverSocket = ServerSocket(port, 50, localAddress)
                serverSocket?.soTimeout = 60000
                Log.d(TAG, "Socket on $localAddress created")
                val peer = Peer(localAddress.toString().replace("/", ""), port.toShort(), clientId, 3, 0)
                while (working.get()) {
                    try {
                        if (getUtcTime() >= lastAnnounceTime + announceTtl) {
                            resolver.announce(pubkey, privkey, peer, this)
                            listener.onTrackerPing(false)
                        }
                        if (!online) {
                            online = true
                            listener.onServerStateChanged(online)
                            sendUnsent()
                        }
                        socket = serverSocket!!.accept()
                        Log.i(TAG, "New client from: $socket")
                        // Use threads for each client to communicate with them simultaneously
                        val connection = ConnectionHandler(clientId, keyPair, socket, this, infoProvider)
                        connection.peerStatus = Status.ConnectedIn
                        synchronized(connections) {
                            val address = socket.inetAddress.toString().substring(1)
                            connections[address] = connection
                        }
                        connection.start()
                    } catch (e: SocketTimeoutException) {
                        if (getYggdrasilAddress() != localAddress) {
                            Log.i(TAG, "Yggdrasil address changed, restarting")
                            break
                        }
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
                Log.e(TAG, "Error creating server socket or accepting connection")
                lastAnnounceTime = 0L
                if (online && getYggdrasilAddress() == null) {
                    online = false
                    listener.onServerStateChanged(online)
                }
                connections.values.forEach {
                    it.interrupt()
                }
                try {
                    socket?.close()
                } catch (ex: IOException) {
                    ex.printStackTrace()
                }
                sleep(10000)
            } catch (e: SecurityException) {
                e.printStackTrace()
                Log.e(TAG, "Security manager doesn't allow binding socket")
                break
            }
        }
    }

    fun stopServer() {
        working.set(false)
        serverSocket?.close()
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

    fun sendMessages() {
        hasNewMessages = true
    }

    private fun sendUnsent() {
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
            val receiver = object : ResolverReceiver {
                override fun onResolveResponse(pubkey: ByteArray, ips: List<Peer>) {
                    Log.i(TAG, "Resolved IPS: $ips")
                    if (ips.isNotEmpty()) {
                        ips.forEach {
                            storage.saveIp(pubkey, it.address, it.port, it.clientId, it.priority, it.expiration)
                        }
                        // We don't want to make an endless loop, so we give it a null receiver
                        connectContact(contact, contactString, ips, null)
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

            val peers = storage.getContactPeers(contact)
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
                Log.i(TAG, "Locally found IPs are dead, resolving")
                resolver.resolveIps(contact, receiver)
            }
        } else {
            if (receiver != null) {
                Log.i(TAG, "No local ips found, resolving")
                resolver.resolveIps(contact, receiver)
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
                val socket = Socket()
                val socketAddress = InetSocketAddress(InetAddress.getByName(peer.address), peer.port.toInt())
                socket.connect(socketAddress, CONNECTION_TIMEOUT)
                if (socket.isConnected) {
                    val connection = ConnectionHandler(clientId, keyPair, socket, this, infoProvider)
                    connection.peerStatus = Status.ConnectedOut
                    connection.setPeerPublicKey(recipient)
                    connection.start()
                    return connection
                }
            } catch (e: IOException) {
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
}