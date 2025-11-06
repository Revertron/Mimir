package com.revertron.mimir.net

import android.media.MediaFormat
import android.util.Log
import com.revertron.mimir.calls.AudioReceiver
import com.revertron.mimir.calls.AudioSender
import com.revertron.mimir.randomBytes
import com.revertron.mimir.sec.Sign
import com.revertron.mimir.yggmobile.Connection
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.absoluteValue
import kotlin.random.Random

class ConnectionHandler(
    private val clientId: Int,
    private val keyPair: AsymmetricCipherKeyPair,
    private val connection: Connection,
    private val listener: EventListener,
    private val infoProvider: InfoProvider
): Thread(TAG) {

    companion object {
        private const val TAG = "ConnectionHandler"
        private const val VERSION = 1
        // We need at least a header to know the length
        private const val HEADER_SIZE = 16

        // Keep-alive configuration
        private const val PING_INTERVAL_MS = 60_000L // 60 seconds
        private const val CONNECTION_TIMEOUT_MS = 600_000L // 10 minutes
        private const val PING_TIMEOUT_MS = 5_000L // 5 seconds to wait for pong
        private const val CALL_PING_INTERVAL_MS = 2_000L // 2 seconds during calls
        private const val CALL_TIMEOUT_MS = 3_500L // 3.5 seconds during calls
    }

    private var peer: ByteArray? = null
    var peerStatus: Status = Status.Created
    private var challengeBytes: ByteArray? = randomBytes(32)
    private var infoRequested = false
    private val buffer = mutableListOf<Pair<Long, Message>>()
    private val sentMessages = HashSet<Long>()
    private var address = Hex.toHexString(connection.publicKey())
    private var peerClientId = 0
    private var lastActiveTime = System.currentTimeMillis()
    private var lastPingTime = System.currentTimeMillis()
    private var lastPongTime = System.currentTimeMillis()
    private var callStatus: CallStatus = CallStatus.Idle
    private var audioSender: AudioSender? = null
    private var audioReceiver: AudioReceiver? = null

    override fun run() {

        // TODO make buffered reader:
        // new BufferedReader(new InputStreamReader(socket.getInputStream()));
        Log.i(TAG, "Starting connection with $address")
        val dis = DataInputStream(ConnectionInputStream(connection))
        val startTime = System.currentTimeMillis()
        val rand = Random.Default
        try {
            while (!this.isInterrupted) {
                when (peerStatus) {
                    Status.ConnectedOut -> {
                        val hello = getHello(clientId, /*connection.publicKey()*/ null)
                        val baos = ByteArrayOutputStream()
                        val dos = DataOutputStream(baos)
                        writeClientHello(dos, hello)
                        connection.write(baos.toByteArray())
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
                            val baos = ByteArrayOutputStream()
                            val dos = DataOutputStream(baos)
                            writeMessage(dos, message.second, infoProvider.getFilesDirectory())
                            val bytes = baos.toByteArray()
                            connection.write(bytes)
                            Log.i(TAG, "Message ${message.second.guid} sent, ${bytes.size} bytes")
                        } else if (!infoRequested) {
                            val baos = ByteArrayOutputStream()
                            val dos = DataOutputStream(baos)
                            writeInfoRequest(dos, infoProvider.getContactUpdateTime(peer!!))
                            val bytes = baos.toByteArray()
                            connection.write(bytes)
                            infoRequested = true
                        }
                        processCallStates()
                    }
                    Status.HelloSent -> {
                        if (System.currentTimeMillis() - startTime >= 5000) {
                            Log.i(TAG, "Connection with $address timed out")
                            break
                        }
                    }
                    else -> {}
                }

                //Log.i(TAG, "Peer status $peerStatus")

                try {
                    if (dis.available() >= HEADER_SIZE) {
                        val baos = ByteArrayOutputStream()
                        val dos = DataOutputStream(baos)
                        val result = processInput(dis, dos)
                        if (result != ProcessResult.Failed) {
                            val bytes = baos.toByteArray()
                            if (bytes.size > 0) {
                                connection.write(bytes)
                            }
                            // Update lastActiveTime only for meaningful messages (not ping/pong)
                            if (result == ProcessResult.MessageOk) {
                                lastActiveTime = System.currentTimeMillis()
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e.message?.contains("deadline exceeded") == true) {
                        continue
                    }
                    Log.e(TAG, "Exception: $e")
                    // Connection severed somewhere, we just bail
                    val peer = Hex.toHexString(peer)
                    Log.i(TAG, "Connection with $peer and $address broke")
                    interrupt()
                    break
                }

                // Handle keep-alive and connection timeout
                if (!handleKeepAlive(rand.nextInt().absoluteValue % 20000)) {
                    break
                }

                // Sleep briefly when idle to avoid busy-waiting
                if (System.currentTimeMillis() > lastActiveTime + CONNECTION_TIMEOUT_MS) {
                    try {
                        sleep(100)
                    } catch (_: InterruptedException) {
                        peer?.apply {
                            val peer = Hex.toHexString(this)
                            Log.i(TAG, "Connection thread with $peer and $address interrupted")
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            //TODO propagate event that message was not sent
        }
        connection.close()
        stopAudio()
        peer?.let {
            if (callStatus != CallStatus.Idle) {
                listener.onCallStatusChanged(CallStatus.Hangup, it)
            }
            listener.onConnectionClosed(it, address)
        }
    }

    private fun processCallStates() {
        when (callStatus) {
            CallStatus.Call -> {
                val baos = ByteArrayOutputStream()
                val dos = DataOutputStream(baos)
                writeCallOffer(dos, CallOffer(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1))
                val bytes = baos.toByteArray()
                connection.write(bytes)
                Log.i(TAG, "Call offer sent")
                callStatus = CallStatus.Calling
                listener.onCallStatusChanged(callStatus, peer)
            }

            CallStatus.Answer -> {
                val baos = ByteArrayOutputStream()
                val dos = DataOutputStream(baos)
                writeCallAnswer(dos, CallAnswer(ok = true))
                val bytes = baos.toByteArray()
                connection.write(bytes)
                Log.i(TAG, "Call answer sent")
                callStatus = CallStatus.InCall
                listener.onCallStatusChanged(callStatus, peer)
                startAudio()
            }

            CallStatus.Reject -> {
                val baos = ByteArrayOutputStream()
                val dos = DataOutputStream(baos)
                writeCallAnswer(dos, CallAnswer(ok = false))
                val bytes = baos.toByteArray()
                connection.write(bytes)
                Log.i(TAG, "Call decline sent")
                callStatus = CallStatus.Idle
                listener.onCallStatusChanged(callStatus, peer)
            }

            CallStatus.Hangup -> {
                val baos = ByteArrayOutputStream()
                val dos = DataOutputStream(baos)
                writeCallHangup(dos)
                val bytes = baos.toByteArray()
                connection.write(bytes)
                Log.i(TAG, "Call hangup sent")
                callStatus = CallStatus.Idle
                listener.onCallStatusChanged(callStatus, peer)
            }

            else -> {}
        }
    }

    private fun processInput(dis: DataInputStream, dos: DataOutputStream): ProcessResult {
        try {
            val header = readHeader(dis)
            //Log.i(TAG, "Got header $header, our state $peerStatus, available data: ${dis.available()} bytes")
            when (header.type) {
                MSG_TYPE_HELLO -> {
                    val hello = readClientHello(dis, header.size > 80) ?: return ProcessResult.Failed
                    if (peer == null) {
                        peer = hello.pubkey
                        if (!isMessageForMe(hello)) {
                            Log.w(TAG, "Connection for wrong number!")
                            return ProcessResult.Failed
                        }
                        if (hello.address != null) {
                            /*if (!isAddressFromSubnet(socket.inetAddress, hello.address)) {
                                Log.e(TAG, "Spoofing Yggdrasil address!\n${socket.inetAddress} and ${hello.address}")
                                return ProcessResult.Failed
                            }*/
                            val oldAddress = address
                            address = Hex.toHexString(hello.address)
                            Log.i(TAG, "Client connected new address: ${address}")
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
                    val answer = getChallengeAnswer(challenge?.data ?: return ProcessResult.Failed)
                    writeChallengeAnswer(dos, answer)
                    peerStatus = Status.ChallengeAnswered
                }
                MSG_TYPE_CHALLENGE2 -> {
                    val challenge = readChallenge(dis)
                    val answer = getChallengeAnswer(challenge?.data ?: return ProcessResult.Failed)
                    writeChallengeAnswer(dos, answer, type = MSG_TYPE_CHALLENGE_ANSWER2)
                    peerStatus = Status.Challenge2Answered
                }
                MSG_TYPE_CHALLENGE_ANSWER -> {
                    val answer = readChallengeAnswer(dis)
                    val public = Ed25519PublicKeyParameters(peer)
                    if (!Sign.verify(public, challengeBytes!!, answer?.data ?: return ProcessResult.Failed)) {
                        Log.w(TAG, "Wrong challenge answer!")
                        return ProcessResult.Failed
                    }
                    // Client answered challenge
                    writeOk(dos, 0)
                    peerStatus = Status.AuthDone
                    synchronized(listener) {
                        peer?.let { listener.onClientConnected(it, address, peerClientId) }
                    }
                }
                MSG_TYPE_CHALLENGE_ANSWER2 -> {
                    val answer = readChallengeAnswer(dis)
                    val public = Ed25519PublicKeyParameters(peer)
                    if (!Sign.verify(public, challengeBytes!!, answer?.data ?: return ProcessResult.Failed)) {
                        Log.w(TAG, "Wrong challenge answer!")
                        return ProcessResult.Failed
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
                            return ProcessResult.Failed
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
                        if (dis.available() > 0) {
                            return processInput(dis, dos)
                        }
                    }
                }
                MSG_TYPE_MESSAGE_TEXT -> {
                    val message = readMessage(dis) ?: return ProcessResult.Failed
                    Log.i(TAG, "Got message ${message.guid}, in reply to ${message.replyTo}")
                    writeOk(dos, message.guid)
                    synchronized(listener) {
                        peer?.let { listener.onMessageReceived(it, message.guid, message.replyTo, message.sendTime, message.editTime, message.type, message.data) }
                    }
                }
                MSG_TYPE_CALL_OFFER -> {
                    val offer = readCallOffer(dis) ?: return ProcessResult.Failed
                    Log.i(TAG, "Got call offer: $offer")
                    if (callStatus == CallStatus.Idle) {
                        callStatus = CallStatus.Receiving
                        listener.onIncomingCall(peer!!, false)
                    }
                }
                MSG_TYPE_CALL_ANSWER -> {
                    val callAnswer = readCallAnswer(dis) ?: return ProcessResult.Failed
                    Log.i(TAG, "Got call answer: $callAnswer")
                    //TODO Start call on call screen through listener
                    if (callStatus == CallStatus.Calling && callAnswer.ok) {
                        callStatus = CallStatus.InCall
                        listener.onCallStatusChanged(callStatus, peer)
                        startAudio()
                    } else {
                        listener.onCallStatusChanged(CallStatus.Hangup, peer)
                        callStatus = CallStatus.Idle
                    }
                }
                MSG_TYPE_CALL_PACKET -> {
                    //Log.i(TAG, "Call packet received")
                    val packet = readCallPacket(dis) ?: return ProcessResult.Failed
                    //Log.i(TAG, "Call packet size: ${packet.data.size}")
                    audioReceiver?.pushPacket(packet.data)
                }
                MSG_TYPE_CALL_HANG -> {
                    Log.i(TAG, "Stopping the call")
                    callStatus = CallStatus.Idle
                    listener.onCallStatusChanged(CallStatus.Hangup, peer)
                    stopAudio()
                }
                MSG_TYPE_PING -> {
                    lastPingTime = System.currentTimeMillis()
                    writePong(dos)
                    return ProcessResult.KeepAlive  // Ping/Pong is not meaningful activity
                }
                MSG_TYPE_PONG -> {
                    lastPongTime = System.currentTimeMillis()
                    return ProcessResult.KeepAlive  // Ping/Pong is not meaningful activity
                }
                else -> {
                    Log.i(TAG, "Unknown message type: ${header.type}")
                    if (header.size > 0) {
                        Log.i(TAG, "Dismissing ${header.size} bytes")
                        readAndDismiss(dis, header.size)
                    }
                }
            }
            return ProcessResult.MessageOk
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ProcessResult.Failed
    }

    private fun isMessageForMe(hello: ClientHello): Boolean {
        val publicKey = (keyPair.public as Ed25519PublicKeyParameters).encoded
        //Log.d(TAG, "My ${Hex.toHexString(publicKey)} and his ${Hex.toHexString(hello.receiver)}")
        return publicKey.contentEquals(hello.receiver)
    }

    fun setPeerPublicKey(pubkey: ByteArray) {
        peer = pubkey
    }

    fun sendMessage(guid: Long, replyTo: Long, sendTime: Long, editTime: Long, type: Int, data: ByteArray) {
        synchronized(buffer) {
            if (!sentMessages.contains(guid)) {
                val message = Message(guid, replyTo, sendTime, editTime, type, data)
                buffer.add(id to message)
                sentMessages.add(guid)
            }
        }
    }

    fun sendData(bytes: ByteArray) {
        try {
            connection.writeWithTimeout(bytes, 2000)
            lastActiveTime = System.currentTimeMillis()
        } catch (e: Exception) {
            stopAudio()
            interrupt()
        }
    }

    fun loopData(bytes: ByteArray) {
        audioReceiver?.pushPacket(bytes)
    }

    fun startCall() {
        // Signal that we need to call
        callStatus = CallStatus.Call
    }

    fun answerCall(answer: Boolean) {
        if (answer)
            callStatus = CallStatus.Answer
        else
            callStatus = CallStatus.Reject
    }

    fun hangupCall() {
        callStatus = CallStatus.Hangup
        stopAudio()
    }

    fun muteCall(mute: Boolean) {
        audioSender?.muteCall(mute)
    }

    private fun startAudio() {
        Log.i(TAG, "Starting call audio")
        if (audioSender == null && audioReceiver == null) {
            audioSender = AudioSender(this).also { it.start() }
            audioReceiver = AudioReceiver(44100).also { it.start() }
            Log.i(TAG, "Audio sender/receiver started")
        }
    }

    fun stopAudio() {
        if (audioSender != null || audioReceiver != null) {
            audioSender?.stopSender()
            audioSender = null
            audioReceiver?.stopReceiver()
            audioReceiver = null
            Log.i(TAG, "Audio stopped")
        }
    }

    private fun getHello(clientId: Int, address: ByteArray? = null): ClientHello {
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

    /**
     * Handles connection keep-alive by sending pings and detecting timeouts.
     * Returns false if the connection timed out and should be terminated.
     */
    private fun handleKeepAlive(jitter: Int): Boolean {
        val now = System.currentTimeMillis()
        val isInCall = callStatus == CallStatus.Calling || callStatus == CallStatus.InCall || callStatus == CallStatus.Receiving

        // Check for connection timeout based on call status
        val timeoutMs = if (isInCall) CALL_TIMEOUT_MS else CONNECTION_TIMEOUT_MS
        if (now - lastActiveTime >= timeoutMs) {
            Log.w(TAG, "Connection with $address timed out (no activity for ${timeoutMs / 1000}s)")
            return false
        }

        // Check for ping timeout (sent ping but no pong received)
        if (lastPingTime > lastPongTime && now - lastPingTime >= PING_TIMEOUT_MS) {
            Log.w(TAG, "Connection with $address timed out (no pong response)")
            return false
        }

        // Determine ping interval based on call status
        val pingInterval = if (isInCall) CALL_PING_INTERVAL_MS else PING_INTERVAL_MS

        // Send ping if interval has elapsed
        if (now - lastPingTime >= pingInterval - jitter) {
            return sendPing()
        }

        return true
    }

    /**
     * Sends a ping message to the peer.
     * Returns false if sending fails and connection should be terminated.
     */
    private fun sendPing(): Boolean {
        Log.d(TAG, "Sending ping to $address")
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        writePing(dos)

        return try {
            connection.writeWithTimeout(baos.toByteArray(), 2000)
            lastPingTime = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            if (e.message?.contains("deadline exceeded") == true) {
                Log.w(TAG, "Connection with $address timed out (ping write failed)")
                false
            } else {
                Log.e(TAG, "Error sending ping to $address: ${e.message}")
                false
            }
        }
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

enum class ProcessResult {
    Failed,      // Processing failed, connection should handle error
    MessageOk,   // Meaningful message processed (update lastActiveTime)
    KeepAlive    // Keep-alive message (ping/pong, don't update lastActiveTime)
}