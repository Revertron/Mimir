package com.revertron.mimir.net

import android.content.Context
import android.util.Log
import com.revertron.mimir.App
import com.revertron.mimir.BuildConfig
import com.revertron.mimir.NetState
import com.revertron.mimir.NetType
import com.revertron.mimir.getNetworkType
import com.revertron.mimir.storage.PeerProvider
import com.revertron.mimir.yggmobile.Messenger
import org.json.JSONArray
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages Yggdrasil network peer health monitoring, selection, and state tracking.
 *
 * PeerManager runs as an independent thread that:
 * - Monitors peer connectivity and health every 3 seconds
 * - Implements cost-based peer selection algorithms
 * - Handles automatic peer jumping on failures
 * - Broadcasts online state changes to registered listeners
 * - Maintains backward compatibility by updating App.app.online
 *
 * Thread Safety:
 * - online and currentPeer are @Volatile for atomic reads
 * - peerStats is only accessed from PeerManager thread
 * - listeners uses ConcurrentHashMap for safe concurrent access
 * - onlineStateLock is used for wake-up signaling
 */
class PeerManager(
    private val messenger: Messenger,
    private val peerProvider: PeerProvider,
    private val context: Context
) : Thread("PeerManagerThread") {

    companion object {
        const val TAG = "PeerManager"

        // Configuration constants for peer management
        private const val PEER_COST_SAMPLES = 2
        private const val PEER_SELECTION_DELAY_MS = 30000L  // Wait 30s before selecting best peer
        private const val PEER_DOWN_GRACE_PERIOD_MS = 12000L  // Wait 12s before declaring peer dead
        private const val MIN_JUMP_INTERVAL_MS = 10000L  // Don't jump more often than every 10s
        private const val NETWORK_CHANGE_GRACE_MS = 5000L  // Grace period after network change
        private const val ONLINE_CHECK_INTERVAL_MS = 15000L  // Check peer health every 15s
    }

    // Public state (thread-safe)
    @Volatile
    var currentPeer: String = ""
        private set

    @Volatile
    private var online: Boolean = false

    // Private state
    private var currentPeerTime = 0L
    private var currentCost = 0
    private var currentPeerFails = 0
    private val peerStats = HashMap<String, PeerStats>()
    private val working = AtomicBoolean(true)
    private val onlineStateLock = Object()

    // Listener management
    private val listeners = ConcurrentHashMap<String, PeerStateListener>()

    // Callback for force announce
    var onForceAnnounce: (() -> Unit)? = null

    /**
     * Get current online state
     */
    fun isOnline(): Boolean = online

    /**
     * Register a listener for peer state changes.
     *
     * @param key Unique identifier for this listener
     * @param listener Callback that receives online state changes
     */
    fun addListener(key: String, listener: PeerStateListener) {
        listeners[key] = listener
    }

    /**
     * Remove a registered listener.
     *
     * @param key Identifier of the listener to remove
     */
    fun removeListener(key: String) {
        listeners.remove(key)
    }

    /**
     * Initialize the current peer from messenger.
     * Should be called once after messenger is initialized.
     */
    fun initializeCurrentPeer() {
        val peersJSON = messenger.peersJSON
        if (peersJSON != null && peersJSON != "null") {
            val peersArray = JSONArray(peersJSON)
            if (peersArray.length() > 0) {
                currentPeer = peersArray.getJSONObject(0).getString("URI")
                currentPeerTime = System.currentTimeMillis()
                Log.i(TAG, "Initialized current peer: $currentPeer")
            }
        }
    }

    /**
     * Switch to a random peer from the available peer list.
     * Excludes the current peer from selection.
     */
    fun refreshPeer() {
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
            onForceAnnounce?.invoke()
        }
    }

    /**
     * Switch to the best available peer based on failure count and cost metrics.
     * Peers are sorted by: failures (ascending), then cost (ascending).
     */
    fun jumpPeer() {
        val peers = peerProvider.getPeers()
        // If user changed the group of peers from default to own or vice versa, we remove old stats
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
        currentPeerFails = 0
        onForceAnnounce?.invoke()
    }

    /**
     * Reconcile the messenger's peer list with the peer provider's list.
     * Adds new peers, removes obsolete peers, and updates statistics.
     */
    fun refreshPeerList() {
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
            currentPeerFails = 0
            Log.i(TAG, "Current peer was removed, selected new random peer: $currentPeer")
        }

        // Force announce to update trackers with new peer configuration
        onForceAnnounce?.invoke()

        Log.i(TAG, "Peer list refreshed: ${newPeers.size} peers active, will select best peer based on cost")
    }

    /**
     * Signal the peer monitoring thread to wake up immediately.
     * Call this when network becomes available to avoid waiting in the sleep loop.
     */
    fun signalNetworkChange() {
        synchronized(onlineStateLock) {
            onlineStateLock.notifyAll()
        }
    }

    /**
     * Stop the peer manager thread.
     */
    fun stopManager() {
        working.set(false)
        signalNetworkChange()  // Wake up the thread so it can exit
    }

    /**
     * Main peer monitoring loop.
     * Runs continuously, checking peer health every 3 seconds.
     */
    override fun run() {
        var costSampleCount = 0
        var peerDownStartTime = 0L
        var lastJumpTime = 0L
        var waitingForBestPeer = false

        while (working.get()) {
            synchronized(onlineStateLock) {
                try {
                    onlineStateLock.wait(ONLINE_CHECK_INTERVAL_MS)
                } catch (_: InterruptedException) {}
            }
            val networkOnline = NetState.haveNetwork()
            val now = System.currentTimeMillis()
            //Log.d(TAG, "pathsJSON: " + messenger.pathsJSON)

            // Handle complete network offline state
            if (!networkOnline && !online) {
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
            if (!networkOnline) {
                peerDownStartTime = 0L
                costSampleCount = 0
                waitingForBestPeer = false
            }

            val oldOnlineState = online
            val peersJSON = messenger.peersJSON

            // Handle missing or invalid peers JSON
            if (peersJSON == null || peersJSON == "null") {
                Log.i(TAG, "No peers JSON in messenger")
                notifyStateChange(false)
                // Only jump if we have network and enough time has passed since last jump
                if (networkOnline && now - lastJumpTime >= MIN_JUMP_INTERVAL_MS) {
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
                notifyStateChange(false)
                peerDownStartTime = 0L
                costSampleCount = 0
                waitingForBestPeer = false
                continue
            }

            // We have at least one peer
            val currentPeerObj = peers.getJSONObject(0)
            val peerUp = currentPeerObj.getBoolean("Up")
            val networkChanged = NetState.networkChangedRecently()
            val networkType = getNetworkType(context)
            val timeSinceCurrentPeer = now - currentPeerTime

            // Handle online state change
            if (oldOnlineState != peerUp) {
                Log.i(TAG, "Online changed to $peerUp ($currentPeer)")
                if (getNetworkType(context) == NetType.CELLULAR && peerUp) {
                    //Log.d(TAG, "Before: " + messenger.treeJSON)
                    //Log.d(TAG, "Wait a bit before connecting to mediator...")
                    sleep(8000)
                    //Log.d(TAG, "After : " + messenger.treeJSON)
                }
                notifyStateChange(peerUp)

                if (peerUp) {
                    onForceAnnounce?.invoke()
                    peerDownStartTime = 0L
                    costSampleCount = 0
                } else {
                    // Peer went down - start grace period timer
                    if (peerDownStartTime == 0L) {
                        peerDownStartTime = now
                        peerStats.getOrPut(currentPeer) { PeerStats(0, -1) }.apply {
                            fails += 1
                            Log.i(TAG, "Peer $currentPeer went down, fails=$fails, starting grace period")
                        }
                    }
                    currentPeerFails += 1
                    // Peer came back up - check if we should jump to a more stable peer
                    if (currentPeerFails >= 3 && timeSinceCurrentPeer >= MIN_JUMP_INTERVAL_MS * 3) {
                        if (timeSinceCurrentPeer / currentPeerFails <= MIN_JUMP_INTERVAL_MS * 3) {
                            jumpPeer()
                            lastJumpTime = now
                        }
                    }
                }
            } else if (peerUp) {
                // Peer is stable and up - sample cost periodically
                costSampleCount++
                if (costSampleCount >= PEER_COST_SAMPLES) {
                    val cost = currentPeerObj.optInt("Cost", 500)
                    if (cost > 0) {
                        peerStats.getOrPut(currentPeer, { PeerStats(0, -1) }).apply {
                            if (this.cost !in 0..cost) {
                                Log.i(TAG, "Updating cost for $currentPeer: $cost (was ${this.cost})")
                                this.cost = cost
                            }
                        }
                        if (cost != currentCost) {
                            currentCost = cost
                            notifyListeners()
                        }
                    }
                    costSampleCount = 0
                }

                // Reset down timer when peer is up
                peerDownStartTime = 0L

                val paths = messenger.pathsJSON
                if (paths == null || paths == "null") {
                    onForceAnnounce?.invoke()
                }
            }

            // Peer selection phase: after initial connection period, find the best peer
            if (networkOnline && peerUp && !waitingForBestPeer &&
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
            } else if (networkOnline && peerUp && timeSinceCurrentPeer >= PEER_SELECTION_DELAY_MS &&
                       !networkChanged && peersCount == 1) {
                var highCost = 500
                if (networkType == NetType.CELLULAR) {
                    highCost = 4500
                }
                // Only one peer - check if cost is too high
                val cost = currentPeerObj.optInt("Cost", 500)
                if (cost > highCost && now - lastJumpTime >= MIN_JUMP_INTERVAL_MS) {
                    Log.i(TAG, "High cost $cost on current peer, jumping to better one")
                    jumpPeer()
                    lastJumpTime = now
                    peerDownStartTime = 0L
                    costSampleCount = 0
                    waitingForBestPeer = false
                }
            }

            // Handle peer down for extended period - but with grace period and rate limiting
            if (!peerUp && networkOnline && !networkChanged && peersCount == 1) {
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
        Log.i(TAG, "PeerManager thread stopped")
    }

    /**
     * Extract host from a peer URI like "tls://1.2.3.4:5678".
     */
    private fun extractHost(peerUri: String): String {
        return try {
            URI(peerUri).host ?: peerUri
        } catch (_: Exception) {
            peerUri
        }
    }

    /**
     * Notify all listeners of an online state change.
     * Also updates App.app.online for backward compatibility.
     */
    private fun notifyStateChange(newState: Boolean) {
        if (online != newState) {
            online = newState
            App.app.online = newState
            if (!newState) currentCost = 0
            notifyListeners()
        }
    }

    /**
     * Notify all listeners with current peer info.
     * Called on state changes and when cost info updates.
     */
    private fun notifyListeners() {
        val host = if (online) extractHost(currentPeer) else ""
        listeners.values.forEach { listener ->
            try {
                listener.onPeerStateChanged(online, host, currentCost)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener", e)
            }
        }
    }
}

/**
 * Statistics for a Yggdrasil peer.
 *
 * @param fails Number of times this peer has failed
 * @param cost Network cost metric in milliseconds (-1 if unknown)
 */
data class PeerStats(var fails: Int, var cost: Int)

/**
 * Listener interface for peer state changes.
 */
fun interface PeerStateListener {
    /**
     * Called when the peer state or info changes.
     *
     * @param online True if peer is online, false if offline
     * @param peerHost The host part of the current peer URI (empty if offline)
     * @param cost The network cost metric of the current peer (0 if unknown)
     */
    fun onPeerStateChanged(online: Boolean, peerHost: String, cost: Int)
}
