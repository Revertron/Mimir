package com.revertron.mimir.net

import android.util.Log
import com.revertron.mimir.getUtcTime
import com.revertron.mimir.sec.Sign
import com.revertron.mimir.storage.Peer
import com.revertron.mimir.yggmobile.Connection
import com.revertron.mimir.yggmobile.Messenger
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Random

class Resolver(private val messenger: Messenger, private val tracker: ByteArray) {

    companion object {
        private const val TAG = "Resolver"
        private const val VERSION = 1
        private const val CMD_ANNOUNCE = 0
        private const val CMD_GET_ADDRS = 1
    }

    private val random = Random(System.currentTimeMillis())

    fun resolveAddrs(pubkey: ByteArray, receiver: ResolverReceiver) {
        //Log.i(TAG, "Resolving...")
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeByte(VERSION)
        val nonce = random.nextInt()
        dos.writeInt(nonce)
        dos.writeByte(CMD_GET_ADDRS)
        dos.write(pubkey)
        val request = baos.toByteArray()
        var socket: Connection? = null
        try {
            socket = messenger.connect(tracker)
            //Log.i(TAG, "Created socket")
            socket?.write(request)
            //Log.i(TAG, "Packet sent")
            val buf = ByteArray(1024)
            val length = socket!!.readWithTimeout(buf, 5000)
            //Log.i(TAG, "Read $length bytes")
            process(buf.copyOfRange(0, length.toInt()), pubkey, receiver)
            //startTimeoutThread(nonce, receiver)
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "Error resolving: $e")
            receiver.onError(pubkey)
        } finally {
            socket?.close()
        }
    }

    fun announce(pubkey: ByteArray, privkey: ByteArray, peer: Peer, receiver: ResolverReceiver) {
        Log.i(TAG, "Announcing ${peer.address}")
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeByte(VERSION)
        val nonce = random.nextInt()
        dos.writeInt(nonce)
        dos.writeByte(CMD_ANNOUNCE)
        dos.write(pubkey)
        dos.writeByte(peer.priority)
        dos.writeInt(peer.clientId)
        val addr = Hex.decode(peer.address)
        dos.write(addr)
        val priv = Ed25519PrivateKeyParameters(privkey)
        val signature = Sign.sign(priv, addr)
        //val s = Hex.toHexString(signature)
        dos.write(signature)
        val request = baos.toByteArray()
        var socket: Connection? = null
        try {
            socket = messenger.connect(tracker)
            socket?.write(request)
            val buf = ByteArray(1024)
            val length = socket!!.readWithTimeout(buf, 5000)
            //Log.i(TAG, "Read $length bytes")
            process(buf.copyOfRange(0, length.toInt()), pubkey, receiver)
            //startTimeoutThread(nonce, receiver)
            //Log.i(TAG, "Announce packet sent")
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "Error announcing: $e")
            receiver.onError(pubkey)
        } finally {
            socket?.close()
        }
    }

    private fun process(message: ByteArray, pubkey: ByteArray, receiver: ResolverReceiver) {
        val time = getUtcTime()
        val bais = ByteArrayInputStream(message, 0, message.size)
        val dis = DataInputStream(bais)
        val nonce = dis.readInt()
        val command = dis.readByte()

        when (command.toInt()) {
            CMD_ANNOUNCE -> {
                val ttl = dis.readLong()
                receiver.onAnnounceResponse(pubkey, ttl)
            }

            CMD_GET_ADDRS -> {
                val count = dis.readByte()
                if (count <= 0) {
                    receiver.onError(pubkey)
                    return
                }
                Log.i(TAG, "Got $count addresses")
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
                    Log.i(TAG, "Got addr $addr with TTL $ttl")
                    val public = Ed25519PublicKeyParameters(pubkey)
                    if (!Sign.verify(public, addrBuf, sigBuf)) {
                        Log.w(TAG, "Wrong addr signature!")
                        continue
                    }
                    results.add(Peer(addr, clientId, priority.toInt(), time + ttl))
                }
                //Log.i(TAG, "Resolved $results for $nonce")
                receiver.onResolveResponse(pubkey, results)
            }
        }
    }
}

interface ResolverReceiver {
    fun onResolveResponse(pubkey: ByteArray, ips: List<Peer>)
    fun onAnnounceResponse(pubkey: ByteArray, ttl: Long)
    fun onError(pubkey: ByteArray)
}