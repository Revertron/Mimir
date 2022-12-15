package com.revertron.mimir.net

import android.util.Log
import com.revertron.mimir.isAddressFromSubnet
import com.revertron.mimir.isSubnetYggdrasilAddress
import com.revertron.mimir.randomBytes
import com.revertron.mimir.sec.Sign
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket

class ConnectionHandler(private val clientId: Int, private val keyPair: AsymmetricCipherKeyPair, private val socket: Socket, private val listener: EventListener): Thread(TAG) {

    companion object {
        private const val TAG = "ConnectionHandler"
        private const val VERSION = 1
    }

    private var peer: ByteArray? = null
    var peerStatus: Status = Status.Created
    var challengeBytes: ByteArray? = randomBytes(32)
    private val buffer = mutableListOf<Pair<Long, String>>()
    private var address = socket.inetAddress.toString().replace("/", "")
    private var peerClientId = 0

    override fun run() {
        var lastActiveTime = System.currentTimeMillis()
        val dis = DataInputStream(socket.getInputStream())
        val dos = DataOutputStream(socket.getOutputStream())

        // TODO make buffered reader:
        // new BufferedReader(new InputStreamReader(socket.getInputStream()));

        while (!this.isInterrupted) {
            when (peerStatus) {
                Status.ConnectedOut -> {
                    // If our client is from NATed Yggdrasil network we send its address from 300::/8
                    val hello = if (isSubnetYggdrasilAddress(socket.localAddress))
                        getHello(clientId, socket.localAddress)
                    else
                        getHello(clientId)
                    writeClientHello(dos, hello)
                    peerStatus = Status.HelloSent
                }
                Status.AuthDone -> {
                    val message: Pair<Long, String>? = synchronized(buffer) {
                        if (buffer.isNotEmpty()){
                            buffer.removeAt(0)
                        } else {
                            null
                        }
                    }
                    if (message != null) {
                        try {
                            val mes = MessageText(message.first, message.second.toByteArray())
                            writeMessageText(dos, mes)
                            lastActiveTime = System.currentTimeMillis()
                        } catch (e: Exception) {
                            e.printStackTrace()
                            socket.close()
                            //TODO propagate event that message was not sent
                            this.interrupt()
                            break
                        }
                    }
                }
                else -> {}
            }

            try {
                if (dis.available() > 0) {
                    if (processInput(dis, dos)) {
                        lastActiveTime = System.currentTimeMillis()
                    } else {
                        socket.close()
                        break
                    }
                }
            } catch (e: IOException) {
                // Connection severed somewhere, we just bail
                try {
                    socket.close()
                } catch (e: IOException) { /**/ }
                Log.i(TAG, "Connection with $peer and ${socket.inetAddress} broke")
                interrupt()
                break
            }

            if (System.currentTimeMillis() > lastActiveTime + 1000) {
                try {
                    sleep(100)
                } catch (e: InterruptedException) {
                    Log.i(TAG, "Connection thread with $peer and ${socket.inetAddress} interrupted")
                    break
                }
                if (System.currentTimeMillis() > lastActiveTime + 120000) {
                    Log.i(TAG, "Connection with ${socket.inetAddress} timed out")
                    socket.close()
                    break
                }
            }
        }
        peer?.let { listener.onConnectionClosed(it, socket.inetAddress.toString()) }
    }

    private fun processInput(dis: DataInputStream, dos: DataOutputStream): Boolean {
        try {
            val header = readHeader(dis)
            Log.i(TAG, "Got header $header")
            when (header.type) {
                MSG_TYPE_HELLO -> {
                    val hello = readClientHello(dis, header.size > 80) ?: return false
                    if (peer == null) {
                        peer = hello.pubkey
                        if (!(keyPair.public as Ed25519PublicKeyParameters).encoded.contentEquals(hello.receiver)) {
                            Log.w(TAG, "Connection for wrong number!")
                            return false
                        }
                        if (hello.address != null) {
                            if (!isAddressFromSubnet(socket.inetAddress, hello.address)) {
                                Log.e(TAG, "Spoofing Yggdrasil address!\n${socket.inetAddress} and ${hello.address}")
                                return false
                            }
                            Log.i(TAG, "Client connected from NATed IPv6: ${hello.address}")
                            address = hello.address.toString().replace("/", "")
                        }
                        val challenge = getChallenge()
                        writeChallenge(dos, challenge!!)
                        peerStatus = Status.ChallengeSent
                        peerClientId = hello.clientId
                    }
                }
                MSG_TYPE_CHALLENGE -> {
                    val challenge = readChallenge(dis)
                    val answer = getChallengeAnswer(challenge?.data ?: return false)
                    writeChallengeAnswer(dos, answer)
                    peerStatus = Status.ChallengeAnswered
                }
                MSG_TYPE_CHALLENGE_ANSWER -> {
                    val answer = readChallengeAnswer(dis)
                    val public = Ed25519PublicKeyParameters(peer)
                    if (!Sign.verify(public, challengeBytes!!, answer?.data ?: return false)) {
                        Log.w(TAG, "Wrong challenge answer!")
                        return false
                    }
                    writeOk(dos, 0)
                    peerStatus = Status.AuthDone
                    synchronized(listener) {
                        peer?.let { listener.onClientConnected(it, address, peerClientId) }
                    }
                }
                MSG_TYPE_OK -> {
                    val ok = readOk(dis)
                    if (ok != null) {
                        Log.i(TAG, "Message with id ${ok.id} received by peer")
                        //TODO process Ok as real confirmation
                        if (peerStatus == Status.ChallengeAnswered && ok.id == 0L) {
                            peerStatus = Status.AuthDone
                        } else if (ok.id > 0) {
                            //TODO Check that we really sent this ok.id to this user ;)
                            listener.onMessageDelivered(peer!!, ok.id, true)
                        }
                    }
                }
                MSG_TYPE_MESSAGE_TEXT -> {
                    val message = readMessageText(dis)
                    val text = String(message?.data ?: return false)
                    Log.i(TAG, "Got message ${message.id}")
                    writeOk(dos, message.id)
                    synchronized(listener) {
                        peer?.let { listener.onMessageReceived(it, address, message.id, text) }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    fun setPeerPublicKey(pubkey: ByteArray) {
        peer = pubkey
    }

    fun setPeerPublicKey(pubkey: String) {
        peer = Hex.decode(pubkey)
    }

    fun addForDeliveryText(id: Long, message: String) {
        synchronized(buffer) {
            buffer.add(id to message)
        }
    }

    private fun getHello(clientId: Int, address: InetAddress? = null): ClientHello {
        val pubkey = (keyPair.public as Ed25519PublicKeyParameters).encoded
        return ClientHello(VERSION, pubkey, peer!!, clientId, address)
    }

    private fun getChallenge(): Challenge? {
        if (challengeBytes == null) {
            return null
        }
        return Challenge(challengeBytes!!)
    }

    private fun getChallengeAnswer(message: ByteArray): ChallengeAnswer {
        val signed = Sign.sign(keyPair.private, message)
        return ChallengeAnswer(signed!!)
    }

    private fun getOk(id: Long, stream: Int): Ok {
        return Ok(id)
    }
}

enum class Status {
    Created, ConnectedIn, ConnectedOut, HelloSent, ChallengeSent, ChallengeAnswered, AuthDone,
}