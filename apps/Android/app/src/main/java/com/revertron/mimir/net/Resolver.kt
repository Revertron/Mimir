package com.revertron.mimir.net

import android.util.Log
import com.revertron.mimir.getUtcTime
import com.revertron.mimir.sec.Sign
import com.revertron.mimir.storage.Peer
import com.revertron.mimir.storage.SqlStorage
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
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
    private val nonces = HashMap<Int, Pair<String, ResolverReceiver>>()
    private val socket = DatagramSocket()

    init {
        // Receiving thread
        val t = Thread {
            val buf = ByteArray(1024)
            val packet = DatagramPacket(buf, buf.size)
            while (!Thread.interrupted()) {
                socket.receive(packet)
                if (packet.length == 0) continue
                val time = getUtcTime()
                val bais = ByteArrayInputStream(packet.data, 0, packet.length)
                val dis = DataInputStream(bais)
                val nonce = dis.readInt()
                val command = dis.readByte()
                val pair = nonces.get(nonce) ?: continue
                when (command.toInt()) {
                    CMD_ANNOUNCE -> {
                        val ttl = dis.readLong()
                        pair.second.onAnnounceResponse(pair.first, ttl)
                    }
                    CMD_GET_IPS -> {
                        val count = dis.readByte()
                        Log.i(TAG, "Got $count ips")
                        if (count <= 0) continue
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
                            val public = Ed25519PublicKeyParameters(Hex.decode(pair.first))
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
                }
            }
        }
        t.start()
    }

    fun getIps(pubkey: String): List<Peer> {
        return storage.getContactPeers(pubkey)
    }

    fun saveIps(pubkey: String, peers: List<Peer>) {
        for (addr in peers) {
            storage.saveIp(pubkey, addr.address, addr.port, addr.clientId, addr.priority, addr.expiration)
        }
    }

    fun resolveIps(pubkey: String, receiver: ResolverReceiver) {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeByte(VERSION)
        val nonce = random.nextInt()
        dos.writeInt(nonce)
        nonces[nonce] = pubkey to receiver
        dos.writeByte(CMD_GET_IPS)
        val id = Hex.decode(pubkey)
        dos.write(id)
        val request = baos.toByteArray()
        val packet = DatagramPacket(request, request.size, tracker)
        socket.send(packet)
    }

    fun announce(pubkey: String, privkey: String, peer: Peer, receiver: ResolverReceiver) {
        Log.i(TAG, "Announcing ${peer.address}")
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeByte(VERSION)
        val nonce = random.nextInt()
        dos.writeInt(nonce)
        nonces[nonce] = pubkey to receiver
        dos.writeByte(CMD_ANNOUNCE)
        val id = Hex.decode(pubkey)
        dos.write(id)
        dos.writeShort(peer.port.toInt())
        dos.writeByte(peer.priority)
        dos.writeInt(peer.clientId)
        val ip = InetAddress.getByName(peer.address)
        dos.write(ip.address)
        val priv = Ed25519PrivateKeyParameters(Hex.decode(privkey))
        val signature = Sign.sign(priv, ip.address)
        dos.write(signature)
        val request = baos.toByteArray()
        val packet = DatagramPacket(request, request.size, tracker)
        socket.send(packet)
        Log.i(TAG, "Announce packet sent")
    }
}

interface ResolverReceiver {
    fun onResolveResponse(pubkey:String, ips: List<Peer>)
    fun onAnnounceResponse(pubkey: String, ttl: Long)
    fun onTimeout(pubkey: String)
}