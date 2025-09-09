package com.revertron.mimir.net

import android.util.Log
import com.revertron.mimir.getUtcTime
import com.revertron.mimir.sec.Sign
import com.revertron.mimir.storage.Peer
import com.revertron.mimir.storage.SqlStorage
import com.revertron.mimir.yggmobile.Connection
import com.revertron.mimir.yggmobile.Messenger
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.lang.Thread.sleep
import java.util.Random

class Resolver(private val storage: SqlStorage, private val messenger: Messenger, private val tracker: ByteArray) {

    companion object {
        private const val TAG = "Resolver"
        private const val VERSION = 1
        private const val CMD_ANNOUNCE = 0
        private const val CMD_GET_ADDRS = 1
    }

    private val random = Random(System.currentTimeMillis())
    private val nonces = HashMap<Int, Pair<ByteArray, ResolverReceiver>>()
    private val timeouts = HashMap<Int, Thread>()
    private var socket: Connection? = null

    init {
        // Receiving thread
        val t = Thread {
            val buf = ByteArray(1024)
            while (!Thread.interrupted()) {
                sleep(1000)
                synchronized(timeouts) {
                    if (socket != null) {
                        try {
                            //Log.i(TAG, "Reading from socket")
                            val length = socket!!.read(buf)
                            //Log.i(TAG, "Read $length bytes")
                            process(buf.copyOfRange(0, length.toInt()))
                            closeSocket()
                        } catch (e: Exception) {
                            when (e.message) {
                                "EOF" -> {
                                    closeSocket()
                                }

                                else -> {
                                    Log.e(TAG, "Socket read error", e)
                                    closeSocket()
                                }
                            }
                            continue
                        }
                    }
                }
            }
        }
        t.start()
    }

    fun getIps(pubkey: ByteArray): List<Peer> {
        return storage.getContactPeers(pubkey)
    }

    fun saveIps(pubkey: ByteArray, peers: List<Peer>) {
        for (addr in peers) {
            storage.saveIp(pubkey, addr.address, 0, addr.clientId, addr.priority, addr.expiration)
        }
    }

    fun resolveAddrs(pubkey: ByteArray, receiver: ResolverReceiver) {
        //Log.i(TAG, "Resolving...")
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeByte(VERSION)
        val nonce = random.nextInt()
        dos.writeInt(nonce)
        nonces[nonce] = pubkey to receiver
        dos.writeByte(CMD_GET_ADDRS)
        dos.write(pubkey)
        val request = baos.toByteArray()
        try {
            val socket = messenger.connect(tracker)
            Log.i(TAG, "Created socket")
            socket?.write(request)
            Log.i(TAG, "Packet sent")
            val buf = ByteArray(1024)
            val length = socket!!.readWithTimeout(buf, 5000)
            //Log.i(TAG, "Read $length bytes")
            process(buf.copyOfRange(0, length.toInt()))
            socket.close()
            //startTimeoutThread(nonce, receiver)
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "Error sending packet: $e")
            val pair = nonces.remove(nonce)
            pair?.second?.onError(pubkey)
        }
    }

    fun announce(pubkey: ByteArray, privkey: ByteArray, peer: Peer, receiver: ResolverReceiver) {
        Log.i(TAG, "Announcing ${peer.address}")
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeByte(VERSION)
        val nonce = random.nextInt()
        dos.writeInt(nonce)
        nonces[nonce] = pubkey to receiver
        dos.writeByte(CMD_ANNOUNCE)
        dos.write(pubkey)
        dos.writeByte(peer.priority)
        dos.writeInt(peer.clientId)
        val addr = Hex.decode(peer.address)
        dos.write(addr)
        val priv = Ed25519PrivateKeyParameters(privkey)
        val signature = Sign.sign(priv, addr)
        val s = Hex.toHexString(signature)
        dos.write(signature)
        val request = baos.toByteArray()
        try {
            val socket = messenger.connect(tracker)
            socket?.write(request)
            val buf = ByteArray(1024)
            val length = socket!!.readWithTimeout(buf, 5000)
            //Log.i(TAG, "Read $length bytes")
            process(buf.copyOfRange(0, length.toInt()))
            socket.close()
            //startTimeoutThread(nonce, receiver)
            //Log.i(TAG, "Announce packet sent")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending packet: $e")
            val pair = nonces.remove(nonce)
            pair?.second?.onError(pubkey)
        }
    }

    fun closeSocket() {
        if (socket != null) {
            try {
                socket?.close()
            } catch (_: Exception) {}
            socket = null
        }
    }

    private fun process(message: ByteArray) {
        val time = getUtcTime()
        val bais = ByteArrayInputStream(message, 0, message.size)
        val dis = DataInputStream(bais)
        val nonce = dis.readInt()
        val command = dis.readByte()
        val pair = nonces.remove(nonce) ?: return
        synchronized(timeouts) {
            timeouts.remove(nonce)?.interrupt()
        }
        when (command.toInt()) {
            CMD_ANNOUNCE -> {
                val ttl = dis.readLong()
                pair.second.onAnnounceResponse(pair.first, ttl)
                socket?.close()
                socket = null
            }

            CMD_GET_ADDRS -> {
                val count = dis.readByte()
                Log.i(TAG, "Got $count addresses")
                if (count <= 0) {
                    pair.second.onError(pair.first)
                    return
                }
                val results = mutableListOf<Peer>()
                val addrBuf = ByteArray(32)
                val sigBuf = ByteArray(64)
                for (r in 1..count) {
                    //Log.i(TAG, "Reading address $r")
                    dis.readFully(addrBuf)
                    dis.readFully(sigBuf)
                    val priority = dis.readByte()
                    val clientId = dis.readInt()
                    val ttl = dis.readLong()
                    val addr = Hex.toHexString(addrBuf)
                    Log.i(TAG, "Got ip $addr with TTL $ttl")
                    val public = Ed25519PublicKeyParameters(pair.first)
                    if (!Sign.verify(public, addrBuf, sigBuf)) {
                        Log.w(TAG, "Wrong addr signature!")
                        continue
                    }
                    results.add(Peer(addr, clientId, priority.toInt(), time + ttl))
                }
                //Log.i(TAG, "Resolved $results for $nonce")
                pair.second.onResolveResponse(pair.first, results)
            }
        }
    }

    private fun startTimeoutThread(nonce: Int, receiver: ResolverReceiver): Thread {
        val t = Thread {
            //Log.d(TAG, "Timeout thread for $nonce started")
            try {
                sleep(7000)
                synchronized(nonces) {
                    if (nonces.containsKey(nonce)) {
                        Log.d(TAG, "Timeout thread for $nonce got timeout")
                        val pair = nonces.remove(nonce)!!
                        receiver.onError(pair.first)
                    }
                }
            } catch (_: InterruptedException) {
                //Log.d(TAG, "Timeout thread for $nonce interrupted")
            }
            synchronized(timeouts) {
                timeouts.remove(nonce)
            }
        }
        synchronized(timeouts) {
            timeouts.put(nonce, t)
        }
        t.start()
        return t
    }
}

interface ResolverReceiver {
    fun onResolveResponse(pubkey: ByteArray, ips: List<Peer>)
    fun onAnnounceResponse(pubkey: ByteArray, ttl: Long)
    fun onError(pubkey: ByteArray)
}