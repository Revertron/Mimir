package com.revertron.mimir.net

import android.util.Log
import com.revertron.mimir.getFileContents
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

const val MSG_TYPE_HELLO = 1
const val MSG_TYPE_CHALLENGE = 2
const val MSG_TYPE_CHALLENGE_ANSWER = 3
const val MSG_TYPE_CHALLENGE2 = 4
const val MSG_TYPE_CHALLENGE_ANSWER2 = 5
const val MSG_TYPE_INFO_REQUEST = 6
const val MSG_TYPE_INFO_RESPONSE = 7
const val MSG_TYPE_PING = 8
const val MSG_TYPE_PONG = 9
const val MSG_TYPE_MESSAGE_TEXT = 1000
const val MSG_TYPE_CALL_OFFER = 2000
const val MSG_TYPE_CALL_ANSWER = 2001
const val MSG_TYPE_CALL_HANG = 2002
const val MSG_TYPE_CALL_PACKET = 2003
const val MSG_TYPE_OK = 32767

data class Header(val stream: Int, val type: Int, val size: Long)
data class ClientHello(val version: Int, val pubkey: ByteArray, val receiver: ByteArray, val clientId: Int, val address: ByteArray? = null)
data class Challenge(val data: ByteArray)
data class ChallengeAnswer(val data: ByteArray)
data class InfoResponse(val time: Long, val nickname: String, val info: String, val avatar: ByteArray?)
data class Message(val guid: Long, val replyTo: Long, val sendTime: Long, val editTime: Long, val type: Int, val data: ByteArray)
data class CallOffer(val mimeType: String, val sampleRate: Int, val channelCount: Int = 1)
data class CallAnswer(val ok: Boolean, val error: String = "")
data class CallPacket(val data: ByteArray)
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

fun writePing(dos: DataOutputStream, stream: Int = 0, type: Int = MSG_TYPE_PING, size: Int = 0) {
    writeHeader(dos, stream, type, size)
}

fun writePong(dos: DataOutputStream, stream: Int = 0, type: Int = MSG_TYPE_PONG, size: Int = 0) {
    writeHeader(dos, stream, type, size)
}

fun readClientHello(dis: DataInputStream, readAddress: Boolean): ClientHello? {
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
    if (size != 32) {
        return null
    }
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
    val address = if (readAddress) {
        val size = dis.readInt()
        if (size != 32) {
            return null
        }
        val buf = ByteArray(size)
        dis.read(buf)
        buf
    } else {
        null
    }
    return ClientHello(version, pubkey, receiver, clientId, address)
}

fun writeClientHello(dos: DataOutputStream, hello: ClientHello, stream: Int = 0, type: Int = MSG_TYPE_HELLO): Boolean {
    var size = 4 + 4 + hello.pubkey.size + 4 + hello.receiver.size + 4
    if (hello.address != null) {
        size += hello.address.size + 4
    }
    writeHeader(dos, stream, type, size)

    dos.writeInt(hello.version)
    dos.writeInt(hello.pubkey.size)
    dos.write(hello.pubkey)
    dos.writeInt(hello.receiver.size)
    dos.write(hello.receiver)
    dos.writeInt(hello.clientId)
    if (hello.address != null) {
        dos.writeInt(hello.address.size)
        dos.write(hello.address)
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
    var size = dis.readInt()
    var data = ByteArray(size)
    var count = 0
    while (count < size) {
        val read = dis.read(data, count, size - count)
        if (read < 0) {
            return null
        }
        count += read
    }
    //Log.d(TAG, "Read $count bytes of message")

    try {
        val json = JSONObject(String(data))
        //Log.d(TAG, "Message JSON: $json")
        val guid = json.getLong("guid")
        val replyTo = json.optLong("replyTo", 0)
        val sendTime = json.optLong("sendTime", 0)
        val editTime = json.optLong("editTime", 0)
        val type = json.optInt("type", 0)
        size = json.optInt("payloadSize", 0)
        if (size > 0) {
            Log.d(TAG, "Reading $size bytes of payload")
            data = ByteArray(size)
            count = 0
            while (count < size) {
                val read = dis.read(data, count, size - count)
                Log.d(TAG, "Read $read bytes of payload")
                if (read < 0) {
                    return null
                }
                count += read
            }
        } else {
            data = ByteArray(0)
        }
        return Message(guid, replyTo, sendTime, editTime, type, data)
    } catch (e: JSONException) {
        Log.e(TAG, "Error parsing JSON: $e")
        return null
    }
}

/**
 * Writes message to socket
 */
fun writeMessage(dos: DataOutputStream, message: Message, filePath: String, stream: Int = 0, type: Int = MSG_TYPE_MESSAGE_TEXT): Boolean {
    val size = 8 + 4 + 4 + message.data.size
    writeHeader(dos, stream, type, size)

    val json = JSONObject()
    json.put("guid", message.guid)
    if (message.replyTo != 0L) {
        json.put("replyTo", message.replyTo)
    }
    json.put("sendTime", message.sendTime)
    if (message.editTime != 0L) {
        json.put("editTime", message.editTime)
    }
    json.put("type", message.type)
    val data: ByteArray
    var jsonSize = -1
    if (message.data.isNotEmpty()) {
        var add = 0
        when (message.type) {
            1 -> {
                val meta = JSONObject(String(message.data))
                jsonSize = message.data.size
                val file = File(filePath, meta.getString("name"))
                val picture = getFileContents(file.absolutePath)
                //TODO optimize memory
                data = message.data.plus(picture)
                add = 4
            }
            else -> {
                data = message.data
            }
        }
        json.put("payloadSize", data.size + add)
    } else {
        data = message.data
    }
    val jsonData = json.toString().toByteArray()

    dos.writeInt(jsonData.size)
    dos.write(jsonData)
    if (data.isNotEmpty()) {
        if (jsonSize >= 0) {
            dos.writeInt(jsonSize)
        }
        dos.write(data)
    }
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

fun writeCallOffer(dos: DataOutputStream, offer: CallOffer, stream: Int = 0, type: Int = MSG_TYPE_CALL_OFFER): Boolean {
    val mimeBuf = offer.mimeType.toByteArray()
    val size = 4 + mimeBuf.size + 4 + 4
    writeHeader(dos, stream, type, size)

    dos.writeInt(mimeBuf.size)
    dos.write(mimeBuf)
    dos.writeInt(offer.sampleRate)
    dos.writeInt(offer.channelCount)
    dos.flush()
    return true
}

fun readCallOffer(dis: DataInputStream): CallOffer? {
    val mimeSize = dis.readInt()
    val mimeBuf = ByteArray(mimeSize)
    var count = 0
    while (count < mimeSize) {
        val read = dis.read(mimeBuf, count, mimeSize - count)
        if (read < 0) return null
        count += read
    }
    val sampleRate = dis.readInt()
    val channelCount = dis.readInt()
    return CallOffer(String(mimeBuf), sampleRate, channelCount)
}

fun writeCallAnswer(dos: DataOutputStream, answer: CallAnswer, stream: Int = 0, type: Int = MSG_TYPE_CALL_ANSWER): Boolean {
    val errBuf = answer.error.toByteArray()
    val size = 1 + 4 + errBuf.size
    writeHeader(dos, stream, type, size)

    dos.writeBoolean(answer.ok)
    dos.writeInt(errBuf.size)
    dos.write(errBuf)
    dos.flush()
    return true
}

fun readCallAnswer(dis: DataInputStream): CallAnswer? {
    val ok = dis.readBoolean()
    val errSize = dis.readInt()
    val errBuf = if (errSize > 0) {
        val buf = ByteArray(errSize)
        var count = 0
        while (count < errSize) {
            val read = dis.read(buf, count, errSize - count)
            if (read < 0) return null
            count += read
        }
        buf
    } else null
    return CallAnswer(ok, if (errBuf != null) String(errBuf) else "")
}

fun writeCallHangup(dos: DataOutputStream, stream: Int = 0, type: Int = MSG_TYPE_CALL_HANG): Boolean {
    writeHeader(dos, stream, type, 0)
    dos.flush()
    return true
}

fun writeCallPacket(dos: DataOutputStream, packet: CallPacket, stream: Int = 0, type: Int = MSG_TYPE_CALL_PACKET): Boolean {
    val size = 4 + packet.data.size
    writeHeader(dos, stream, type, size)

    dos.writeInt(packet.data.size)
    dos.write(packet.data)
    dos.flush()
    return true
}

fun readCallPacket(dis: DataInputStream): CallPacket? {
    val size = dis.readInt()
    val data = ByteArray(size)
    var count = 0
    while (count < size) {
        val read = dis.read(data, count, size - count)
        if (read < 0) return null
        count += read
    }
    return CallPacket(data)
}

fun readAndDismiss(dis: DataInputStream, size: Long) {
    var size = size
    val data = ByteArray(1024)
    while (size > 0) {
        val read = dis.read(data, 0, if (size > data.size) data.size else (size as Int))
        if (read < 0) return
        size -= read
    }
}