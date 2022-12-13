package com.revertron.mimir.net

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

const val MSG_TYPE_HELLO = 1
const val MSG_TYPE_CHALLENGE = 2
const val MSG_TYPE_CHALLENGE_ANSWER = 3
const val MSG_TYPE_MESSAGE_TEXT = 4
const val MSG_TYPE_OK = 32767

data class Header(val stream: Int, val type: Int, val size: Long)
data class ClientHello(val version: Int, val pubkey: ByteArray, val receiver: ByteArray, val clientId: Int)
data class Challenge(val data: ByteArray)
data class ChallengeAnswer(val data: ByteArray)
data class MessageText(val id: Long, val data: ByteArray)
data class Ok(val id: Long)

private const val TAG = "Messages"

fun readHeader(dis: DataInputStream): Header {
    val stream = dis.readInt()
    val type = dis.readInt()
    val size = dis.readLong()
    return Header(stream, type, size)
}

private fun writeHeader(dos: DataOutputStream, stream: Int, type: Int, size: Int) {
    // Writing header to socket as one packet
    val baos = ByteArrayOutputStream(4 + 4 + 8)
    val buf = DataOutputStream(baos)
    buf.writeInt(stream)
    buf.writeInt(type)
    buf.writeLong(size.toLong())
    dos.write(baos.toByteArray())
}

fun readClientHello(dis: DataInputStream): ClientHello? {
    val version = dis.readInt()
    var size = dis.readInt()
    val pubkey = ByteArray(size)
    var count = 0
    while (count < size) {
        val read = dis.read(pubkey, count, size - count)
        if (read < 0) {
            return null
        }
        count += read
    }
    size = dis.readInt()
    val receiver = ByteArray(size)
    count = 0
    while (count < size) {
        val read = dis.read(receiver, count, size - count)
        if (read < 0) {
            return null
        }
        count += read
    }
    val clientId = dis.readInt()
    return ClientHello(version, pubkey, receiver, clientId)
}

fun writeClientHello(dos: DataOutputStream, hello: ClientHello, stream: Int = 0, type: Int = MSG_TYPE_HELLO): Boolean {
    val size = 4 + 4 + hello.pubkey.size + 4 + hello.receiver.size + 4
    writeHeader(dos, stream, type, size)

    dos.writeInt(hello.version)
    dos.writeInt(hello.pubkey.size)
    dos.write(hello.pubkey)
    dos.writeInt(hello.receiver.size)
    dos.write(hello.receiver)
    dos.writeInt(hello.clientId)
    return true
}

fun readChallenge(dis: DataInputStream): Challenge? {
    val size = dis.readInt()
    val data = ByteArray(size)
    var count = 0
    while (count < size) {
        val read = dis.read(data, count, size - count)
        if (read < 0) {
            return null
        }
        count += read
    }
    return Challenge(data)
}

fun writeChallenge(dos: DataOutputStream, challenge: Challenge, stream: Int = 0, type: Int = MSG_TYPE_CHALLENGE): Boolean {
    val size = 4 + challenge.data.size
    writeHeader(dos, stream, type, size)

    dos.writeInt(challenge.data.size)
    dos.write(challenge.data)
    return true
}

fun readChallengeAnswer(dis: DataInputStream): ChallengeAnswer? {
    val size = dis.readInt()
    val data = ByteArray(size)
    var count = 0
    while (count < size) {
        val read = dis.read(data, count, size - count)
        if (read < 0) {
            return null
        }
        count += read
    }
    return ChallengeAnswer(data)
}

fun writeChallengeAnswer(dos: DataOutputStream, challenge: ChallengeAnswer, stream: Int = 0, type: Int = MSG_TYPE_CHALLENGE_ANSWER): Boolean {
    val size = 4 + challenge.data.size
    writeHeader(dos, stream, type, size)

    dos.writeInt(challenge.data.size)
    dos.write(challenge.data)
    return true
}

// val id: Int, val size: Long, val offset: Long, val data: ByteArray
fun readMessageText(dis: DataInputStream): MessageText? {
    val id = dis.readLong()
    val size = dis.readInt()
    val data = ByteArray(size)
    var count = 0
    Log.i(TAG, "Id is $id, size is $size, reading bytes...")
    while (count < size) {
        val read = dis.read(data, count, size - count)
        Log.i(TAG, "Read $read bytes")
        if (read < 0) {
            return null
        }
        count += read
    }
    return MessageText(id, data)
}

// val id: Int, val size: Long, val offset: Long, val data: ByteArray
fun writeMessageText(dos: DataOutputStream, message: MessageText, stream: Int = 0, type: Int = MSG_TYPE_MESSAGE_TEXT): Boolean {
    val size = 4 + 4 + message.data.size
    writeHeader(dos, stream, type, size)

    dos.writeLong(message.id)
    dos.writeInt(message.data.size)
    dos.write(message.data)
    dos.flush()
    return true
}

fun writeOk(dos: DataOutputStream, id: Long, stream: Int = 0, type: Int = MSG_TYPE_OK): Boolean {
    val size = 4 + 4
    writeHeader(dos, stream, type, size)

    dos.writeLong(id)
    return true
}

fun readOk(dis: DataInputStream): Ok? {
    val id = dis.readLong()
    return Ok(id)
}