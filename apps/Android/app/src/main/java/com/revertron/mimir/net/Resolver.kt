package com.revertron.mimir.net

import android.util.Log
import com.revertron.mimir.getUtcTime
import com.revertron.mimir.sec.Sign
import com.revertron.mimir.storage.Peer
import com.revertron.mimir.storage.SqlStorage
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.*
import java.util.*
import kotlin.collections.HashMap

class Resolver(private val storage: SqlStorage, private val tracker: InetSocketAddress) {

    companion object {
        private const val TAG = "Resolver"
        private const val VERSION = 1
        private const val CMD_ANNOUNCE = 0
        private const val CMD_GET_IPS = 1
    }

    private val random = Random(System.currentTimeMillis())
    private val nonces = HashMap<Int, Pair<ByteArray, ResolverReceiver>>()
    private val timeouts = HashMap<Int, Thread>()
    private val socket = DatagramSocket()

    init {
        // Receiving thread
        val t = Thread {
            val buf = ByteArray(1024)
            val packet = DatagramPacket(buf, buf.size)
            socket.soTimeout = 30000 // Увеличиваем таймаут до 30 секунд
            while (!Thread.interrupted()) {
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    Log.w(TAG, "Socket receive timeout, retrying...")
                    continue
                } catch (e: Exception) {
                    Log.e(TAG, "Error receiving packet", e)
                    break
                }
                if (packet.length == 0) continue
                val time = getUtcTime()
                val bais = ByteArrayInputStream(packet.data, 0, packet.length)
                val dis = DataInputStream(bais)
                try {
                    val nonce = dis.readInt()
                    val command = dis.readByte()
                    val pair = nonces.remove(nonce) ?: continue
                    synchronized(timeouts) {
                        timeouts.remove(nonce)?.interrupt()
                    }
                    try {
                        when (command.toInt()) {
                            CMD_ANNOUNCE -> {
                                val ttl = dis.readLong()
                                pair.second.onAnnounceResponse(pair.first, ttl)
                            }
                            CMD_GET_IPS -> {
                                val count = dis.readByte()
                                Log.i(TAG, "Got $count ips")
                                if (count <= 0) {
                                    pair.second.onError(pair.first)
                                    continue
                                }
                                val results = mutableListOf<Peer>()
                                val ipBuf = ByteArray(16)
                                val sigBuf = ByteArray(64)
                                for (r in 1..count) {
                                    Log.i(TAG, "Reading address $r")
                                    dis.readFully(ipBuf)
                                    val ip = Inet6Address.getByAddress(ipBuf)
                                    dis.readFully(sigBuf)
                                    val port = dis.readShort()
                                    val priority = dis.readByte()
                                    val clientId = dis.readInt()
                                    val ttl = dis.readLong()
                                    Log.i(TAG, "Got ip $ip")
                                    val public = Ed25519PublicKeyParameters(pair.first)
                                    if (!Sign.verify(public, ipBuf, sigBuf)) {
                                        Log.w(TAG, "Wrong IP signature!")
                                        continue
                                    }
                                    val address = ip.toString().replace("/", "")
                                    Log.i(TAG, "Got TTL: $ttl")
                                    results.add(Peer(address, port, clientId, priority.toInt(), time + ttl))
                                }
                                Log.i(TAG, "Resolved $results")
                                pair.second.onResolveResponse(pair.first, results)
                            }
                            else -> {
                                Log.w(TAG, "Unknown command: $command")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing command", e)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing packet", e)
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
            storage.saveIp(pubkey, addr.address, addr.port, addr.clientId, addr.priority, addr.expiration)
        }
    }

    fun resolveIps(pubkey: ByteArray, receiver: ResolverReceiver) {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeByte(VERSION)
        val nonce = random.nextInt()
        dos.writeInt(nonce)
        nonces[nonce] = pubkey to receiver
        dos.writeByte(CMD_GET_IPS)
        dos.write(pubkey)
        val request = baos.toByteArray()
        val packet = DatagramPacket(request, request.size, tracker)
        try {
            socket.send(packet)
            startTimeoutThread(nonce, receiver)
        } catch (e: IOException) {
            Log.e(TAG, "Error sending packet: $e")
            val pair = nonces.remove(nonce)!!
            pair.second.onError(pubkey)
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
        dos.writeShort(peer.port.toInt())
        dos.writeByte(peer.priority)
        dos.writeInt(peer.clientId)
        val ip = InetAddress.getByName(peer.address)
        dos.write(ip.address)
        val priv = Ed25519PrivateKeyParameters(privkey)
        val signature = Sign.sign(priv, ip.address)
        dos.write(signature)
        val request = baos.toByteArray()
        val packet = DatagramPacket(request, request.size, tracker)
        try {
            socket.send(packet)
            startTimeoutThread(nonce, receiver)
            Log.i(TAG, "Announce packet sent")
        } catch (e: IOException) {
            Log.e(TAG, "Error sending packet: $e")
            val pair = nonces.remove(nonce)!!
            pair.second.onError(pubkey)
        }
    }

    private fun startTimeoutThread(nonce: Int, receiver: ResolverReceiver): Thread {
        val t = Thread {
            try {
                Thread.sleep(30000) // Увеличиваем таймаут до 30 секунд
                synchronized(nonces) {
                    if (nonces.containsKey(nonce)) {
                        val pair = nonces.remove(nonce)!!
                        receiver.onError(pair.first)
                        Log.w(TAG, "Timeout for nonce $nonce")
                    }
                }
            } catch (e: InterruptedException) {
                // Timeout interrupted
            }
            synchronized(timeouts) {
                timeouts.remove(nonce)
            }
        }
        synchronized(timeouts) {
            timeouts[nonce] = t
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
