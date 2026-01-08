package com.revertron.mimir.net

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.revertron.mimir.App
import com.revertron.mimir.BuildConfig
import com.revertron.mimir.NetState
import com.revertron.mimir.getUtcTime
import com.revertron.mimir.haveNetwork
import com.revertron.mimir.storage.Peer
import com.revertron.mimir.storage.PeerProvider
import com.revertron.mimir.storage.SqlStorage
import com.revertron.mimir.yggmobile.Messenger
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import org.json.JSONArray
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

private const val CONNECTION_TRIES = 1
private const val CONNECTION_PERIOD = 1000L

//TODO Get from DNS TXT record
private val TRACKERS = listOf(
    "e1436b91fbcbbfe694177da47103b4e370658ba2db31e7879a8d613a447c9302", // Rev
    "97fa689f6cebfea9b851569827674de89624048fb1e1f8e63bee634676822d8c", // Rev
    "00ff91ffad7fe6217c618cdb3e9d70663aa1e7794670fac3088306e1b88cbdfe", // Rev
    "b4f1946a617ba621a560440cbc92350f6b5e5c158f10a945453758b718e83f2d", // Afka
    "aa90d4a1826a6d485519d336c94b693f30adf63b8aaf01e2c3be53b1f0cc49e3"  // Souce Kalve
)

class MimirServer(
    val context: Context,
    val storage: SqlStorage,
    val peerProvider: PeerProvider,
    private val clientId: Int,
    private val keyPair: AsymmetricCipherKeyPair,
    private val messenger: Messenger,
    private val listener: EventListener,
    private val infoProvider: InfoProvider,
    private val wakeLock: PowerManager.WakeLock
): Thread(TAG), EventListener, ResolverReceiver {

    companion object {
        const val TAG: String = "MimirServer"
        private const val RESEND_INTERVAL_MS = 120_000L
    }

    private val working = AtomicBoolean(true)
    private val connections = HashMap<String, ConnectionHandler>(5)
    private val connectContacts = HashSet<String>(5)
    private var hasNewMessages = false
    private var callStatus: CallStatus = CallStatus.Idle
    private var callContact: ByteArray? = null
    private var callStartTime = 0L
    private var callIncoming = false
    private val lock = Object()          // the monitor of new messages we wait/notify on
    private val onlineStateLock = Object() // the monitor for online state signaling
    private lateinit var resolver: Resolver
    private val pubkey = (keyPair.public as Ed25519PublicKeyParameters).encoded
    private val privkey = (keyPair.private as Ed25519PrivateKeyParameters).encoded
    private var lastAnnounceTime = 0L
    private var announceTtl = 0L
    private var forceAnnounce = false
    private lateinit var currentPeer: String
    private var currentPeerTime = 0L
    private val peerStats = HashMap<String, PeerStats>()

    @SuppressLint("WakelockTimeout", "Wakelock")
    override fun run() {
        if (!wakeLock.isHeld) wakeLock.acquire()
        val myPub = messenger.publicKey()
        val hexPub = Hex.toHexString(myPub)
        Log.i(TAG, "My network ADDR: $hexPub")

        // Initialize oldPeer from messenger
        val peersJSON = messenger.peersJSON
        if (peersJSON != null && peersJSON != "null") {
            val peersArray = org.json.JSONArray(peersJSON)
            if (peersArray.length() > 0) {
                currentPeer = peersArray.getJSONObject(0).getString("URI")
                currentPeerTime = System.currentTimeMillis()
            }
        }

        resolver = Resolver(messenger, TRACKERS)
        startResendThread()
        startOnlineStateThread()
        val peer = Peer(hexPub, clientId, 3, 0)
        startAnnounceThread(pubkey, privkey, peer, this)
        //startRediscoverThread()
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
        wakeLock.release()
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
        val list = peers.toMutableList()
        list.remove(currentPeer)
        if (list.isEmpty()) return

        val newPeer = peers.random()
        Log.i(TAG, "Selected random peer: $newPeer")
        if (!newPeer.contentEquals(currentPeer)) {
            messenger.addPeer(newPeer)
            messenger.removePeer(currentPeer)
            currentPeer = newPeer
            currentPeerTime = System.currentTimeMillis()
            forceAnnounce = true
        }
    }

    fun jumpPeer() {
        if (messenger == null) return
        val peers = peerProvider.getPeers()
        // If user changed the group of peers from default to own or wise versa, we remove old stats
        peerStats.keys.retainAll(peers.toSet())
        if (peers.size < 2) {
            Log.w(TAG, "No useful peers")
            return
        }
        val list = peers.toMutableList()
        list.remove(currentPeer)

        // If there are new peers, we add them to stats
        for (peer in list) {
            if (!peerStats.contains(peer)) {
                peerStats[peer] = PeerStats(0, -1)
            }
        }
        if (list.isEmpty()) return

        val sortedPeers: List<Pair<String, PeerStats>> =
            peerStats.toList().sortedWith(
                compareBy<Pair<String, PeerStats>> { it.second.fails }
                    .thenBy { it.second.cost }
            )

        val (newPeer, stats) = sortedPeers.first()
        Log.i(TAG, "Selected another peer: $newPeer with stats $stats")
        if (newPeer.contentEquals(currentPeer))
            return
        try {
            messenger.removePeer(currentPeer)
            messenger.addPeer(newPeer)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        currentPeer = newPeer
        currentPeerTime = System.currentTimeMillis()
        forceAnnounce = true
    }

    fun refreshPeerList() {
        if (messenger == null) return
        val newPeers = peerProvider.getPeers()
        if (newPeers.isEmpty()) {
            Log.w(TAG, "No peers available")
            return
        }

        // Get current peers from messenger
        val currentPeers = mutableSetOf<String>()
        val peersJSON = messenger.peersJSON
        if (peersJSON != null && peersJSON != "null") {
            val peersArray = JSONArray(peersJSON)
            for (i in 0 until peersArray.length()) {
                val peer = peersArray.getJSONObject(i)
                currentPeers.add(peer.getString("URI"))
            }
        }

        Log.i(TAG, "Refreshing peer list: current=${currentPeers.size}, new=${newPeers.size}")

        // Remove peers that are no longer in the new peer list
        val peersToRemove = currentPeers.subtract(newPeers.toSet())
        for (peer in peersToRemove) {
            try {
                messenger.removePeer(peer)
                peerStats.remove(peer)
                Log.i(TAG, "Removed old peer: $peer")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing peer $peer", e)
            }
        }

        // Add new peers that aren't already in the messenger
        val peersToAdd = newPeers.subtract(currentPeers)
        for (peer in peersToAdd) {
            try {
                messenger.addPeer(peer)
                peerStats[peer] = PeerStats(0, -1)
                Log.i(TAG, "Added new peer: $peer")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding peer $peer", e)
            }
        }

        // Clean up stats for peers not in the new list
        peerStats.keys.retainAll(newPeers.toSet())

        // Initialize stats for any peers without stats
        for (peer in newPeers) {
            if (!peerStats.contains(peer)) {
                peerStats[peer] = PeerStats(0, -1)
            }
        }

        // Update currentPeer if it was removed
        if (!newPeers.contains(currentPeer)) {
            currentPeer = newPeers.random()
            currentPeerTime = System.currentTimeMillis()
            Log.i(TAG, "Current peer was removed, selected new random peer: $currentPeer")
        }

        // Force announce to update trackers with new peer configuration
        forceAnnounce = true

        Log.i(TAG, "Peer list refreshed: ${newPeers.size} peers active, will select best peer based on cost")
    }

    private fun startAnnounceThread(pubkey: ByteArray, privkey: ByteArray, peer: Peer, receiver: ResolverReceiver) {
        Thread {
            while (working.get()) {
                sleep(5000)
                try {
                    val online = haveNetwork(context)

                    val expiredTtl = getUtcTime() >= lastAnnounceTime + announceTtl
                    if (App.app.online && online && (expiredTtl || forceAnnounce)) {
                        if (forceAnnounce) sleep(2000)
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
                val deadline = System.currentTimeMillis() + RESEND_INTERVAL_MS

                synchronized(lock) {
                    while (!hasNewMessages && System.currentTimeMillis() < deadline) {
                        val remaining = deadline - System.currentTimeMillis()
                        if (remaining > 0L) lock.wait(remaining)   // wait until deadline or notify
                    }
                    hasNewMessages = false        // consume the flag
                }

                if (!working.get()) break         // someone asked us to stop

                if (haveNetwork(context)) sendUnsent()
            }
        }.apply { name = "ResendThread" }.start()
    }

    private fun startOnlineStateThread() {
        Thread {
            var costSampleCount = 0
            var peerDownStartTime = 0L
            var lastJumpTime = 0L
            var waitingForBestPeer = false

            // Configurable timeouts for better slow network handling
            val PEER_COST_SAMPLES = 5
            val PEER_SELECTION_DELAY_MS = 15000L  // Wait 15s before selecting best peer (was 10s)
            val PEER_DOWN_GRACE_PERIOD_MS = 12000L  // Wait 12s before declaring peer dead (was 6s)
            val MIN_JUMP_INTERVAL_MS = 10000L  // Don't jump more often than every 10s
            val NETWORK_CHANGE_GRACE_MS = 5000L  // Grace period after network change

            while (working.get()) {
                sleep(3000)
                val online = NetState.haveNetwork()
                val now = System.currentTimeMillis()

                // Handle complete network offline state
                if (!online && !App.app.online) {
                    val waitStart = System.currentTimeMillis()
                    synchronized(onlineStateLock) {
                        try {
                            onlineStateLock.wait(60000)
                        } catch (_: InterruptedException) {
                            // Thread interrupted, continue normally
                        }
                    }
                    val waitDuration = System.currentTimeMillis() - waitStart
                    if (waitDuration < 60000) {
                        Log.i(TAG, "Woken up early after ${waitDuration}ms - network change detected")
                    }
                    peerDownStartTime = 0L
                    costSampleCount = 0
                    waitingForBestPeer = false
                    continue
                }

                // Reset state when network goes offline
                if (!online) {
                    peerDownStartTime = 0L
                    costSampleCount = 0
                    waitingForBestPeer = false
                }

                val oldOnlineState = App.app.online
                val peersJSON = messenger.peersJSON

                // Handle missing or invalid peers JSON
                if (peersJSON == null || peersJSON == "null") {
                    Log.i(TAG, "No peers JSON in messenger")
                    App.app.online = false
                    if (oldOnlineState) {
                        onServerStateChanged(false)
                    }
                    // Only jump if we have network and enough time has passed since last jump
                    if (online && now - lastJumpTime >= MIN_JUMP_INTERVAL_MS) {
                        jumpPeer()
                        lastJumpTime = now
                        peerDownStartTime = 0L
                        costSampleCount = 0
                    }
                    continue
                }

                val peers = JSONArray(peersJSON)
                val peersCount = peers.length()

                // Handle no peers available
                if (peersCount == 0) {
                    Log.i(TAG, "No peers in messenger")
                    App.app.online = false
                    if (oldOnlineState) {
                        onServerStateChanged(false)
                    }
                    peerDownStartTime = 0L
                    costSampleCount = 0
                    waitingForBestPeer = false
                    continue
                }

                // We have at least one peer
                val currentPeerObj = peers.getJSONObject(0)
                val peerUp = currentPeerObj.getBoolean("Up")
                App.app.online = peerUp
                val networkChanged = NetState.networkChangedRecently()
                val timeSinceCurrentPeer = now - currentPeerTime

                // Handle online state change
                if (oldOnlineState != peerUp) {
                    Log.i(TAG, "Online changed to $peerUp ($currentPeer)")
                    onServerStateChanged(peerUp)

                    if (peerUp) {
                        // Peer came back up
                        forceAnnounce = true
                        peerDownStartTime = 0L
                        costSampleCount = 0
                    } else {
                        // Peer went down - start grace period timer
                        if (peerDownStartTime == 0L) {
                            peerDownStartTime = now
                            Log.i(TAG, "Peer went down, starting grace period of ${PEER_DOWN_GRACE_PERIOD_MS}ms")
                        }
                    }
                } else if (peerUp) {
                    // Peer is stable and up - sample cost periodically
                    costSampleCount++
                    if (costSampleCount >= PEER_COST_SAMPLES) {
                        val cost = currentPeerObj.optInt("Cost", 300)
                        if (cost > 0) {
                            peerStats.getOrPut(currentPeer, { PeerStats(0, -1) }).apply {
                                if (this.cost !in 0..cost) {
                                    Log.i(TAG, "Updating cost: $cost (was ${this.cost})")
                                    this.cost = cost
                                }
                            }
                        }
                        costSampleCount = 0
                    }

                    // Reset down timer when peer is up
                    peerDownStartTime = 0L
                }

                // Peer selection phase: after initial connection period, find the best peer
                if (online && peerUp && !waitingForBestPeer &&
                    timeSinceCurrentPeer >= PEER_SELECTION_DELAY_MS && peersCount > 1) {

                    Log.i(TAG, "Starting best peer selection from $peersCount candidates")
                    var minimalCost = 500
                    var bestPeerUri = ""

                    // Collect costs from all peers
                    for (i in 0 until peersCount) {
                        val peerObj = peers.getJSONObject(i)
                        if (BuildConfig.DEBUG) {
                            Log.i(TAG, "Peer: $peerObj")
                        }
                        val uri = peerObj.getString("URI")
                        val cost = peerObj.optInt("Cost", minimalCost)

                        if (cost in 1 until 300) {
                            peerStats.getOrPut(uri, { PeerStats(0, -1) }).apply {
                                if (this.cost !in 0..cost) {
                                    this.cost = cost
                                }
                            }
                            if (cost < minimalCost) {
                                minimalCost = cost
                                bestPeerUri = uri
                            }
                        }
                    }

                    if (bestPeerUri.isNotEmpty()) {
                        Log.i(TAG, "Selected best peer: $bestPeerUri with cost $minimalCost")
                        // Remove all peers except the best one
                        for (i in 0 until peersCount) {
                            val uri = peers.getJSONObject(i).getString("URI")
                            if (uri != bestPeerUri) {
                                messenger.removePeer(uri)
                            }
                        }
                        currentPeer = bestPeerUri
                        waitingForBestPeer = true
                    }
                } else if (online && peerUp && timeSinceCurrentPeer >= PEER_SELECTION_DELAY_MS &&
                           peersCount == 1) {
                    // Only one peer - check if cost is too high
                    val cost = currentPeerObj.optInt("Cost", 500)
                    if (cost > 300 && now - lastJumpTime >= MIN_JUMP_INTERVAL_MS) {
                        Log.i(TAG, "High cost $cost on current peer, jumping to better one")
                        jumpPeer()
                        lastJumpTime = now
                        peerDownStartTime = 0L
                        costSampleCount = 0
                        waitingForBestPeer = false
                    }
                }

                // Handle peer down for extended period - but with grace period and rate limiting
                if (!peerUp && online && !networkChanged && peersCount == 1) {
                    if (peerDownStartTime == 0L) {
                        peerDownStartTime = now
                        Log.i(TAG, "Peer appears down, starting ${PEER_DOWN_GRACE_PERIOD_MS}ms grace period")
                    }

                    val downDuration = now - peerDownStartTime
                    val timeSinceLastJump = now - lastJumpTime

                    // Only jump if:
                    // 1. Grace period has elapsed
                    // 2. Minimum jump interval has passed
                    // 3. Peer has been connected long enough to be considered stable
                    if (downDuration >= PEER_DOWN_GRACE_PERIOD_MS &&
                        timeSinceLastJump >= MIN_JUMP_INTERVAL_MS &&
                        timeSinceCurrentPeer > NETWORK_CHANGE_GRACE_MS) {

                        Log.i(TAG, "Peer down for ${downDuration}ms, jumping to another peer")
                        peerStats.getOrPut(currentPeer, { PeerStats(0, 500) }).apply {
                            fails += 1
                        }
                        jumpPeer()
                        lastJumpTime = now
                        peerDownStartTime = 0L
                        costSampleCount = 0
                        waitingForBestPeer = false
                    }
                }
            }
        }.apply { name = "OnlineStateThread" }.start()
    }

    fun sendMessages() {
        hasNewMessages = true
        synchronized(lock) {
            try {
                lock.notify()
            } catch (_: IllegalMonitorStateException) {
                // Nothing
            }
        }
    }

    fun reconnectPeers() {
        messenger.retryPeersNow()
        announceTtl = 0L
    }

    /**
     * Signals the online state thread to wake up immediately.
     * Call this when network becomes available to avoid waiting in the sleep loop.
     */
    fun signalNetworkChange() {
        synchronized(onlineStateLock) {
            onlineStateLock.notifyAll()
        }
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
                Log.e(TAG, "Error connecting to ${peer.address}")
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
        if (status == CallStatus.Hangup && (callStatus == CallStatus.Hangup || callStatus == CallStatus.Idle)) {
            callStatus = CallStatus.Idle
            return
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

    override fun onConnectionClosed(from: ByteArray, address: String, deadPeer: Boolean) {
        synchronized(connections) {
            val pubKey = Hex.toHexString(from)
            Log.i(TAG, "Removing connection from $pubKey and $address")
            connections.remove(Hex.toHexString(from))
        }
        if (deadPeer) {
            storage.removeContactPeer(address)
        }
        listener.onConnectionClosed(from, address, deadPeer)
        listener.onPeerStatusChanged(from, PeerStatus.NotConnected)
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
        Log.d(TAG, "Got resolver error")
    }

    fun getCallingContact(): ByteArray? {
        return callContact
    }

    fun callAnswer() {
        synchronized(connections) {
            callStartTime = System.currentTimeMillis()
            callIncoming = true
            val connection = connections.get(Hex.toHexString(callContact))
            connection?.apply {
                answerCall(true)
                Log.i(TAG, "Call answered")
            }
        }
    }

    fun callDecline() {
        if (callContact == null) return
        synchronized(connections) {
            if (callContact == null) return
            val connection = connections.get(Hex.toHexString(callContact))
            connection?.apply {
                answerCall(false)
                Log.i(TAG, "Call declined")
            }
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

data class PeerStats(var fails: Int, var cost: Int)

interface EventListener {
    fun onServerStateChanged(online: Boolean)
    fun onTrackerPing(online: Boolean)
    fun onClientIPChanged(old: String, new: String) {}
    fun onClientConnected(from: ByteArray, address: String, clientId: Int)
    fun onMessageReceived(from: ByteArray, guid: Long, replyTo: Long, sendTime: Long, editTime: Long, type: Int, message: ByteArray)
    fun onMessageDelivered(to: ByteArray, guid: Long, delivered: Boolean)
    fun onIncomingCall(from: ByteArray, inCall: Boolean): Boolean { return false }
    fun onCallStatusChanged(status: CallStatus, from: ByteArray?) {}
    fun onConnectionClosed(from: ByteArray, address: String, deadPeer: Boolean) {}
    fun onPeerStatusChanged(from: ByteArray, status: PeerStatus) {}
}

interface InfoProvider {
    fun getMyInfo(ifUpdatedSince: Long): InfoResponse?
    fun getContactUpdateTime(pubkey: ByteArray): Long
    fun updateContactInfo(pubkey: ByteArray, info: InfoResponse)
    fun getFilesDirectory(): String
}