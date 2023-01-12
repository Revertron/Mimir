package com.revertron.mimir.net

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress

const val MSG_TYPE_HELLO = 1
const val MSG_TYPE_CHALLENGE = 2
const val MSG_TYPE_CHALLENGE_ANSWER = 3
const val MSG_TYPE_CHALLENGE2 = 4
const val MSG_TYPE_CHALLENGE_ANSWER2 = 5
const val MSG_TYPE_INFO_REQUEST = 6
const val MSG_TYPE_INFO_RESPONSE = 7
const val MSG_TYPE_MESSAGE_TEXT = 1000
const val MSG_TYPE_OK = 32767

data class Header(val stream: Int, val type: Int, val size: Long)
data class ClientHello(val version: Int, val pubkey: ByteArray, val receiver: ByteArray, val clientId: Int, val address: InetAddress? = null)
data class Challenge(val data: ByteArray)
data class ChallengeAnswer(val data: ByteArray)
data class InfoResponse(val time: Long, val nickname: String, val info: String, val avatar: ByteArray?)
data class Message(val guid: Long, val replyTo: Long, val sendTime: Long, val editTime: Long, val type: Int, val data: ByteArray)
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

fun readClientHello(dis: DataInputStream, read_address: Boolean): ClientHello? {
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
    val address = if (read_address) {
        val size = dis.readInt()
        val buf = ByteArray(size)
        dis.read(buf)
        InetAddress.getByAddress(buf)
    } else {
        null
    }
    return ClientHello(version, pubkey, receiver, clientId, address)
}

fun writeClientHello(dos: DataOutputStream, hello: ClientHello, stream: Int = 0, type: Int = MSG_TYPE_HELLO): Boolean {
    var size = 4 + 4 + hello.pubkey.size + 4 + hello.receiver.size + 4
    if (hello.address != null) {
        size += hello.address.address.size + 4
    }
    writeHeader(dos, stream, type, size)

    dos.writeInt(hello.version)
    dos.writeInt(hello.pubkey.size)
    dos.write(hello.pubkey)
    dos.writeInt(hello.receiver.size)
    dos.write(hello.receiver)
    dos.writeInt(hello.clientId)
    if (hello.address != null) {
        dos.writeInt(hello.address.address.size)
        dos.write(hello.address.address)
    }
    dos.flush()
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
    dos.flush()
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
    dos.flush()
    return true
}

/**
 * Reads message from socket
 */
fun readMessage(dis: DataInputStream): Message? {
    val guid = dis.readLong()
    val replyTo = dis.readLong()
    val sendTime = dis.readLong()
    val editTime = dis.readLong()
    val type = dis.readInt()
    val size = dis.readInt()
    //TODO check for too big sizes
    val data = ByteArray(size)
    var count = 0
    Log.d(TAG, "Guid $guid, size is $size, reading bytes...")
    while (count < size) {
        val read = dis.read(data, count, size - count)
        Log.d(TAG, "Read $read bytes")
        if (read < 0) {
            return null
        }
        count += read
    }
    return Message(guid, replyTo, sendTime, editTime, type, data)
}

/**
 * Writes message to socket
 */
fun writeMessage(dos: DataOutputStream, message: Message, stream: Int = 0, type: Int = MSG_TYPE_MESSAGE_TEXT): Boolean {
    val size = 8 + 4 + 4 + message.data.size
    writeHeader(dos, stream, type, size)

    dos.writeLong(message.guid)
    dos.writeLong(message.replyTo)
    dos.writeLong(message.sendTime)
    dos.writeLong(message.editTime)
    dos.writeInt(message.type)
    dos.writeInt(message.data.size)
    dos.write(message.data)
    dos.flush()
    return true
}

fun writeOk(dos: DataOutputStream, id: Long, stream: Int = 0, type: Int = MSG_TYPE_OK): Boolean {
    val size = 8
    writeHeader(dos, stream, type, size)

    dos.writeLong(id)
    dos.flush()
    return true
}

fun readOk(dis: DataInputStream): Ok? {
    val id = dis.readLong()
    return Ok(id)
}

fun writeInfoRequest(dos: DataOutputStream, time: Long, stream: Int = 0, type: Int = MSG_TYPE_INFO_REQUEST): Boolean {
    writeHeader(dos, stream, type, 8)
    dos.writeLong(time)
    dos.flush()
    return true
}

fun writeInfoResponse(dos: DataOutputStream, info: InfoResponse, stream: Int = 0, type: Int = MSG_TYPE_INFO_RESPONSE): Boolean {
    val nickBuf = info.nickname.toByteArray()
    val infoBuf = info.info.toByteArray()
    val size = 16 + 4 + nickBuf.size + 4 + infoBuf.size + 4 + (info.avatar?.size ?: 0)
    writeHeader(dos, stream, type, size)

    dos.writeLong(info.time)
    dos.writeInt(nickBuf.size)
    dos.write(nickBuf)
    dos.writeInt(infoBuf.size)
    dos.write(infoBuf)
    if (info.avatar == null) {
        dos.writeInt(0)
    } else {
        dos.writeInt(info.avatar.size)
        dos.write(info.avatar)
    }
    dos.flush()
    return true
}

fun readInfoResponse(dis: DataInputStream): InfoResponse? {
    val time = dis.readLong()

    var size = dis.readInt()
    val nickname = if (size > 0) {
        val data = ByteArray(size)
        var count = 0
        while (count < size) {
            val read = dis.read(data, count, size - count)
            if (read < 0) {
                return null
            }
            count += read
        }
        String(data)
    } else {
        ""
    }

    size = dis.readInt()
    val info = if (size > 0) {
        val data = ByteArray(size)
        var count = 0
        while (count < size) {
            val read = dis.read(data, count, size - count)
            if (read < 0) {
                return null
            }
            count += read
        }
        String(data)
    } else {
        ""
    }

    size = dis.readInt()
    val avatar = if (size > 0) {
        val data = ByteArray(size)
        var count = 0
        while (count < size) {
            val read = dis.read(data, count, size - count)
            if (read < 0) {
                return null
            }
            count += read
        }
        data
    } else {
        null
    }

    return InfoResponse(time, nickname, info, avatar)
}