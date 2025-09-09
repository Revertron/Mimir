package com.revertron.mimir.net

import android.util.Log
import com.revertron.mimir.App
import com.revertron.mimir.getUtcTime
import com.revertron.mimir.storage.Peer
import com.revertron.mimir.storage.PeerProvider
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

private const val CONNECTION_TRIES = 3
private const val CONNECTION_PERIOD = 1000L
//TODO Get from DNS TXT record
private const val TRACKER_ADDR = "801bc33a735cded0588284af0bf1b8bdb4138f122a9c17ecee089e9ff151c3f6"

class MimirServer(
    val storage: SqlStorage,
    val peerProvider: PeerProvider,
    private val clientId: Int,
    private val keyPair: AsymmetricCipherKeyPair,
    private val listener: EventListener,
    private val infoProvider: InfoProvider
): Thread(TAG), EventListener, ResolverReceiver {

    companion object {
        const val TAG: String = "MimirServer"
    }

    private val working = AtomicBoolean(true)
    private val connections = HashMap<String, ConnectionHandler>(5)
    private val connectContacts = HashSet<String>(5)
    private lateinit var messenger: Messenger
    private var hasNewMessages = false
    private var callStatus: CallStatus = CallStatus.Idle
    private var callContact: ByteArray? = null
    private var callStartTime = 0L
    private var callIncoming = false
    private lateinit var resolver: Resolver
    private val pubkey = (keyPair.public as Ed25519PublicKeyParameters).encoded
    private val privkey = (keyPair.private as Ed25519PrivateKeyParameters).encoded
    private var lastAnnounceTime = 0L
    private var announceTtl = 0L
    private var forceAnnounce = false
    private lateinit var oldPeer: String

    override fun run() {
        val peers = peerProvider.getPeers()

        oldPeer = peers.random()
        Log.i(TAG, "Selected random peer: $oldPeer")

        messenger = Yggmobile.newMessenger(oldPeer)
        val myPub = messenger.publicKey()
        val hexPub = Hex.toHexString(myPub)
        val tracker = Hex.decode(TRACKER_ADDR)
        Log.i(TAG, "My network ADDR: $hexPub")
        resolver = Resolver(messenger, tracker)
        startResendThread()
        startOnlineStateThread()
        val peer = Peer(hexPub, clientId, 3, 0)
        startAnnounceThread(pubkey, privkey, peer, this)
        startRediscoverThread()
        while (working.get()) {
            try {
                sleep(1000)
                Log.i(TAG, "Accepting connections...")
                val newConnection = messenger.accept()
                val pub = Hex.toHexString(newConnection.publicKey())
                Log.i(TAG, "New client from: $pub")
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

    fun refreshPeer() {
        if (messenger == null) return
        val peers = peerProvider.getPeers()
        if (peers.isEmpty()) {
            Log.w(TAG, "No useful peers")
            return
        }
        val newPeer = peers.random()
        Log.i(TAG, "Selected random peer: $newPeer")
        if (!newPeer.contentEquals(oldPeer)) {
            messenger.addPeer(newPeer)
            messenger.removePeer(oldPeer)
            oldPeer = newPeer
            forceAnnounce = true
        }
    }

    private fun startAnnounceThread(pubkey: ByteArray, privkey: ByteArray, peer: Peer, receiver: ResolverReceiver) {
        Thread {
            while (working.get()) {
                sleep(10000)
                try {
                    val expiredTtl = getUtcTime() >= lastAnnounceTime + announceTtl
                    if (App.app.online && (expiredTtl || forceAnnounce)) {
                        resolver.announce(pubkey, privkey, peer, receiver)
                        listener.onTrackerPing(false)
                        forceAnnounce = false
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

    private fun startRediscoverThread() {
        Thread {
            val receiver = object : ResolverReceiver {
                override fun onResolveResponse(pubkey: ByteArray, ips: List<Peer>) {
                    Log.i(TAG, "Resolved addrs: $ips")
                    ips.forEach {
                        storage.saveIp(pubkey, it.address, 0, it.clientId, it.priority, it.expiration)
                    }
                }

                override fun onAnnounceResponse(pubkey: ByteArray, ttl: Long) {}
                override fun onError(pubkey: ByteArray) {
                    //val hex = Hex.toHexString(pubkey)
                    //Log.w(TAG, "Error resolving IPs for $hex")
                }
            }

            sleep(8000)
            while (working.get()) {
                val missing = storage.getContactsWithoutValidAddresses()
                Log.i(TAG, "Found ${missing.size} contacts without addresses, resolving")
                for (contact in missing) {
                    resolver.resolveAddrs(contact, receiver)
                    sleep(1000)
                }

                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 180000) {
                    sleep(1000)
                    if (!working.get()) {
                        break
                    }
                }
            }
        }.start()
    }

    private fun startOnlineStateThread() {
        Thread {
            while (working.get()) {
                sleep(2000)
                val old = App.app.online
                val peersJSON = messenger.peersJSON
                if (peersJSON != null && peersJSON != "null") {
                    val peers = JSONArray(peersJSON)
                    if (peers.length() > 0) {
                        val peer = peers.getJSONObject(0)
                        //Log.i(TAG, "Peer: $peer")
                        val up = peer.getBoolean("Up")
                        App.app.online = up
                        if (old != up)
                        {
                            Log.i(TAG, "Online changed to $up ($oldPeer)")
                            onServerStateChanged(up)
                            if (up)
                                forceAnnounce = true
                        }
                    } else {
                        App.app.online = false
                        if (old) {
                            onServerStateChanged(false)
                        }
                    }
                } else {
                    App.app.online = false
                    if (old) {
                        onServerStateChanged(false)
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
        if (!App.app.online || callStatus != CallStatus.Idle) return

        //Log.i(TAG, "Sending unsent messages")
        val contacts = storage.getContactsWithUnsentMessages()
        if (contacts.isNotEmpty()) {
            Log.i(TAG, "Contacts with unsent messages: ${contacts.size}")
        }
        hasNewMessages = false
        for (contact in contacts) {
            connectContact(contact)
        }
    }

    fun call(contact: ByteArray) {
        Log.i(TAG, "CallStatus before call = $callStatus")
        if (callStatus == CallStatus.Idle) {
            callStatus = CallStatus.Calling
            callContact = contact
            connectContact(contact)
        }
    }

    fun connectContact(contact: ByteArray) {
        val contactString = Hex.toHexString(contact)
        Log.i(TAG, "Connecting to $contactString")
        listener.onPeerStatusChanged(contact, PeerStatus.Connecting)
        synchronized(connections) {
            Log.i(TAG, "Current connections: ${connections.keys}")
            if (connections.contains(contactString)) {
                Log.i(TAG, "Found established connection, reusing")
                listener.onPeerStatusChanged(contact, PeerStatus.Connected)
                sendUnsentMessages(contact)
                if (callContact == null || !callContact.contentEquals(contact)) {
                    return
                } else if (callStatus == CallStatus.Calling) {
                    Log.i(TAG, "Calling over established connection")
                    val connection = connections.get(contactString)
                    connection?.startCall()
                    return
                }
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
                    Log.i(TAG, "Resolved addrs: $ips")
                    val newIps = ips.subtract(peers.toSet())
                    if (newIps.isNotEmpty()) {
                        newIps.forEach {
                            storage.saveIp(pubkey, it.address, 0, it.clientId, it.priority, it.expiration)
                        }
                        // We don't want to make an endless loop, so we give it a null receiver
                        connectContact(contact, contactString, newIps.toList(), null)
                    } else {
                        Log.i(TAG, "Didn't find alive addrs for $contactString, giving up")
                        synchronized(connectContacts) {
                            connectContacts.remove(contactString)
                        }
                    }
                }

                override fun onAnnounceResponse(pubkey: ByteArray, ttl: Long) {}
                override fun onError(pubkey: ByteArray) {
                    Log.w(TAG, "Error resolving addrs")
                    listener.onPeerStatusChanged(contact, PeerStatus.ErrorConnecting)
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
                        if (connections.contains(contactString)) {
                            val oldConnection = connections.remove(contactString)
                            oldConnection?.interrupt()
                        }

                        connections[contactString] = connection
                    }
                    synchronized(connectContacts) {
                        connectContacts.remove(contactString)
                    }
                    connected = true
                    break
                } else {
                    Log.w(TAG, "Can not connect to $contactString")
                    listener.onPeerStatusChanged(contact, PeerStatus.ErrorConnecting)
                    synchronized(connectContacts) {
                        connectContacts.remove(contactString)
                    }
                }
            }
            if (!connected && receiver != null) {
                Log.i(TAG, "Locally found addrss are dead, resolving more")
                resolver.resolveAddrs(contact, receiver)
            }
        } else {
            if (receiver != null) {
                Log.i(TAG, "No local addrs found, resolving")
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
        Log.i(TAG, "onClientConnected from/to $pubkey")
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
        listener.onPeerStatusChanged(from, PeerStatus.Connected)
        sendUnsentMessages(from)
        if (callStatus == CallStatus.Calling && callContact != null) {
            if (from.contentEquals(callContact)) {
                val connectionHandler = connections.get(pubkey)
                connectionHandler?.startCall()
            }
        }
    }

    override fun onMessageReceived(from: ByteArray, guid: Long, replyTo: Long, sendTime: Long, editTime: Long, type: Int, message: ByteArray) {
        listener.onMessageReceived(from, guid, replyTo, sendTime, editTime, type, message)
    }

    override fun onMessageDelivered(to: ByteArray, guid: Long, delivered: Boolean) {
        listener.onMessageDelivered(to, guid, delivered)
    }

    override fun onIncomingCall(from: ByteArray, inCall: Boolean): Boolean {
        Log.i(TAG, "onIncomingCall status: $callStatus")
        if (callStatus != CallStatus.Idle) {
            return false
        }
        callContact = from
        callIncoming = true
        callStatus = CallStatus.Receiving
        return listener.onIncomingCall(from, inCall)
    }

    override fun onCallStatusChanged(status: CallStatus, from: ByteArray?) {
        Log.i(TAG, "onCallStatusChanged prev: $callStatus, new: $status")
        if (callStatus == CallStatus.Calling && status == CallStatus.InCall) {
            callStartTime = System.currentTimeMillis()
            callIncoming = false
        }
        listener.onCallStatusChanged(status, from)
        val inProcess = callStatus == CallStatus.InCall || callStatus == CallStatus.Calling || callStatus == CallStatus.Receiving
        if (status == CallStatus.Hangup && inProcess) {
            val endTime = System.currentTimeMillis()
            if (callStartTime == 0L) {
                callStartTime = endTime
            }
            storage.addMessage(from!!, 0, 0, callIncoming, true, callStartTime, endTime, 2, ByteArray(0))
            callStatus = CallStatus.Idle
            callIncoming = false
            callStartTime = 0L
            return
        }
        callStatus = status
    }

    override fun onConnectionClosed(from: ByteArray, address: String) {
        synchronized(connections) {
            val pubKey = Hex.toHexString(from)
            Log.i(TAG, "Removing connection from $pubKey and $address")
            connections.remove(Hex.toHexString(from))
        }
        listener.onConnectionClosed(from, address)
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

    fun getCallingContact(): ByteArray? {
        return callContact
    }

    fun callAnswer() {
        synchronized(connections) {
            callStartTime = System.currentTimeMillis()
            callIncoming = true
            val connection = connections.get(Hex.toHexString(callContact))
            connection?.answerCall(true)
        }
    }

    fun callDecline() {
        if (callContact == null) return
        synchronized(connections) {
            if (callContact == null) return
            val connection = connections.get(Hex.toHexString(callContact))
            connection?.answerCall(false)
            val endTime = System.currentTimeMillis()
            if (callStartTime == 0L) {
                callStartTime = endTime
            }
            storage.addMessage(callContact!!, 0, 0, callIncoming, true, callStartTime, endTime, 2, ByteArray(0))
            callStatus = CallStatus.Idle
            callContact = null
            callIncoming = false
            callStartTime = 0L
        }
    }

    fun callHangup() {
        synchronized(connections) {
            if (callContact != null) {
                val connection = connections.get(Hex.toHexString(callContact))
                connection?.hangupCall()
                val endTime = System.currentTimeMillis()
                if (callStartTime == 0L) {
                    callStartTime = endTime
                }
                storage.addMessage(callContact!!, 0, 0, callIncoming, true, callStartTime, endTime, 2, ByteArray(0))
                callStatus = CallStatus.Idle
                callContact = null
                callStartTime = 0L
            }
        }
    }

    fun callMute(mute: Boolean) {
        synchronized(connections) {
            val connection = connections.get(Hex.toHexString(callContact))
            connection?.muteCall(mute)
        }
    }
}

enum class CallStatus {
    Idle,
    Call,
    Calling,
    Receiving,
    Answer,
    Reject,
    Hangup,
    InCall,
}

enum class PeerStatus {
    NotConnected,
    Connecting,
    Connected,
    ErrorConnecting
}

interface EventListener {
    fun onServerStateChanged(online: Boolean)
    fun onTrackerPing(online: Boolean)
    fun onClientIPChanged(old: String, new: String) {}
    fun onClientConnected(from: ByteArray, address: String, clientId: Int)
    fun onMessageReceived(from: ByteArray, guid: Long, replyTo: Long, sendTime: Long, editTime: Long, type: Int, message: ByteArray)
    fun onMessageDelivered(to: ByteArray, guid: Long, delivered: Boolean)
    fun onIncomingCall(from: ByteArray, inCall: Boolean): Boolean { return false }
    fun onCallStatusChanged(status: CallStatus, from: ByteArray?) {}
    fun onConnectionClosed(from: ByteArray, address: String) {}
    fun onPeerStatusChanged(from: ByteArray, status: PeerStatus) {}
}

interface InfoProvider {
    fun getMyInfo(ifUpdatedSince: Long): InfoResponse?
    fun getContactUpdateTime(pubkey: ByteArray): Long
    fun updateContactInfo(pubkey: ByteArray, info: InfoResponse)
    fun getFilesDirectory(): String
}