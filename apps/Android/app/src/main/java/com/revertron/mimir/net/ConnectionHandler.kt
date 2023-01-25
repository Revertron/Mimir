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

class ConnectionHandler(
    private val clientId: Int,
    private val keyPair: AsymmetricCipherKeyPair,
    private val socket: Socket,
    private val listener: EventListener,
    private val infoProvider: InfoProvider
): Thread(TAG) {

    companion object {
        private const val TAG = "ConnectionHandler"
        private const val VERSION = 1
    }

    private var peer: ByteArray? = null
    var peerStatus: Status = Status.Created
    var challengeBytes: ByteArray? = randomBytes(32)
    private var infoRequested = false
    private val buffer = mutableListOf<Pair<Long, Message>>()
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
                Status.Auth2Done -> {
                    val message: Pair<Long, Message>? = synchronized(buffer) {
                        if (buffer.isNotEmpty()) {
                            buffer.removeAt(0)
                        } else {
                            null
                        }
                    }
                    if (message != null) {
                        try {
                            writeMessage(dos, message.second, infoProvider.getFilesDirectory())
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
                    peer?.apply {
                        val peer = Hex.toHexString(this)
                        Log.i(TAG, "Connection thread with $peer and ${socket.inetAddress} interrupted")
                    }
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
            Log.i(TAG, "Got header $header, our state $peerStatus")
            when (header.type) {
                MSG_TYPE_HELLO -> {
                    val hello = readClientHello(dis, header.size > 80) ?: return false
                    if (peer == null) {
                        peer = hello.pubkey
                        if (!isMessageForMe(hello)) {
                            Log.w(TAG, "Connection for wrong number!")
                            return false
                        }
                        if (hello.address != null) {
                            if (!isAddressFromSubnet(socket.inetAddress, hello.address)) {
                                Log.e(TAG, "Spoofing Yggdrasil address!\n${socket.inetAddress} and ${hello.address}")
                                return false
                            }
                            Log.i(TAG, "Client connected from NATed IPv6: ${hello.address}")
                            val oldAddress = address
                            address = hello.address.toString().replace("/", "")
                            listener.onClientIPChanged(oldAddress, address)
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
                MSG_TYPE_CHALLENGE2 -> {
                    val challenge = readChallenge(dis)
                    val answer = getChallengeAnswer(challenge?.data ?: return false)
                    writeChallengeAnswer(dos, answer, type = MSG_TYPE_CHALLENGE_ANSWER2)
                    peerStatus = Status.Challenge2Answered
                }
                MSG_TYPE_CHALLENGE_ANSWER -> {
                    val answer = readChallengeAnswer(dis)
                    val public = Ed25519PublicKeyParameters(peer)
                    if (!Sign.verify(public, challengeBytes!!, answer?.data ?: return false)) {
                        Log.w(TAG, "Wrong challenge answer!")
                        return false
                    }
                    // Client answered challenge
                    writeOk(dos, 0)
                    if (!infoRequested) {
                        if (peer == null) {
                            return false
                        }
                        writeInfoRequest(dos, infoProvider.getContactUpdateTime(peer!!))
                        infoRequested = true
                    }
                    peerStatus = Status.AuthDone
                    synchronized(listener) {
                        peer?.let { listener.onClientConnected(it, address, peerClientId) }
                    }
                }
                MSG_TYPE_CHALLENGE_ANSWER2 -> {
                    val answer = readChallengeAnswer(dis)
                    val public = Ed25519PublicKeyParameters(peer)
                    if (!Sign.verify(public, challengeBytes!!, answer?.data ?: return false)) {
                        Log.w(TAG, "Wrong challenge answer!")
                        return false
                    }
                    // Server answered challenge
                    writeOk(dos, 0)
                    peerStatus = Status.Auth2Done
                    synchronized(listener) {
                        peer?.let { listener.onClientConnected(it, address, peerClientId) }
                    }
                }
                MSG_TYPE_INFO_REQUEST -> {
                    val time = dis.readLong()
                    val info = infoProvider.getMyInfo(time)
                    if (info != null) {
                        writeInfoResponse(dos, info)
                    }
                }
                MSG_TYPE_INFO_RESPONSE -> {
                    val info = readInfoResponse(dis)
                    if (info != null) {
                        if (peer == null) {
                            return false
                        }
                        infoProvider.updateContactInfo(peer!!, info)
                    }
                }
                MSG_TYPE_OK -> {
                    val ok = readOk(dis)
                    if (ok != null) {
                        Log.i(TAG, "Message with id ${ok.id} received by peer")
                        //TODO process Ok as real confirmation
                        if (peerStatus == Status.ChallengeAnswered && ok.id == 0L) {
                            // Now we, as client node, need to authorize the server node
                            val challenge = getChallenge()
                            writeChallenge(dos, challenge!!, type = MSG_TYPE_CHALLENGE2)
                            peerStatus = Status.Challenge2Sent
                        } else if (peerStatus == Status.Challenge2Answered && ok.id == 0L) {
                            // Now we, as client node, are authorized
                            peerStatus = Status.Auth2Done
                        } else if (ok.id != 0L) {
                            //TODO Check that we really sent this ok.id to this user ;)
                            listener.onMessageDelivered(peer!!, ok.id, true)
                        }
                    }
                }
                MSG_TYPE_MESSAGE_TEXT -> {
                    val message = readMessage(dis) ?: return false
                    Log.i(TAG, "Got message ${message.guid}, in reply to ${message.replyTo}")
                    writeOk(dos, message.guid)
                    synchronized(listener) {
                        peer?.let { listener.onMessageReceived(it, message.guid, message.replyTo, message.sendTime, message.editTime, message.type, message.data) }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun isMessageForMe(hello: ClientHello): Boolean {
        val publicKey = (keyPair.public as Ed25519PublicKeyParameters).encoded
        Log.d(TAG, "My ${Hex.toHexString(publicKey)} and his ${Hex.toHexString(hello.receiver)}")
        return publicKey.contentEquals(hello.receiver)
    }

    fun setPeerPublicKey(pubkey: ByteArray) {
        peer = pubkey
    }

    fun sendMessage(guid: Long, replyTo: Long, sendTime: Long, editTime: Long, type: Int, data: ByteArray) {
        synchronized(buffer) {
            val message = Message(guid, replyTo, sendTime, editTime, type, data)
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
    Created,
    ConnectedIn,
    ConnectedOut,
    HelloSent,
    ChallengeSent,
    ChallengeAnswered,
    AuthDone,
    Challenge2Sent,
    Challenge2Answered,
    Auth2Done,
}