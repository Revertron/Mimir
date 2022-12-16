package com.revertron.mimir.net

import android.content.Context
import android.util.Log
import com.revertron.mimir.getYggdrasilAddress
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import java.io.IOException
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean

//TODO This port will be random, and clients will get it from trackers (or DNS)
private const val CONNECTION_PORT = 5050
private const val CONNECTION_TRIES = 5
private const val CONNECTION_TIMEOUT = 15000
private const val CONNECTION_PERIOD = 3000L

class MimirServer(val context: Context, private val clientId: Int, private val keyPair: AsymmetricCipherKeyPair, private val listener: EventListener, val port: Int): Thread(TAG), EventListener {

    companion object {
        const val TAG: String = "MimirServer"
    }

    private val working = AtomicBoolean(true)
    private val connections = HashMap<String, ConnectionHandler>(5)
    private var serverSocket: ServerSocket? = null

    override fun run() {
        var online = false
        while (working.get()) {
            var socket: Socket? = null
            try {
                Log.d(TAG, "Getting Yggdrasil address...")
                val localAddress = getYggdrasilAddress()
                if (localAddress == null) {
                    Log.e(TAG, "Could not start server, no Yggdrasil IP found")
                    sleep(10000)
                    continue
                }
                Log.i(TAG, "Starting on $localAddress")
                serverSocket = ServerSocket(port, 50, localAddress)
                serverSocket?.soTimeout = 60000
                while (working.get()) {
                    try {
                        if (!online) {
                            online = true
                            listener.onServerStateChanged(online)
                        }
                        socket = serverSocket!!.accept()
                        Log.i(TAG, "New client from: $socket")
                        // Use threads for each client to communicate with them simultaneously
                        val connection = ConnectionHandler(clientId, keyPair, socket, this)
                        connection.peerStatus = Status.ConnectedIn
                        synchronized(connections) {
                            val address = socket.inetAddress.toString().substring(1)
                            connections[address] = connection
                        }
                        connection.start()
                    } catch (e: SocketTimeoutException) {
                        if (getYggdrasilAddress() != localAddress) {
                            Log.i(TAG, "Yggdrasil address changed, restarting")
                            break
                        }
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
                Log.e(TAG, "Error creating server socket or accepting connection")
                if (online && getYggdrasilAddress() == null) {
                    online = false
                    listener.onServerStateChanged(online)
                }
                try {
                    socket?.close()
                } catch (ex: IOException) {
                    ex.printStackTrace()
                }
            }
        }
    }

    fun stopServer() {
        working.set(false)
        serverSocket?.close()
    }

    fun sendText(recipient: ByteArray, ips: List<String>, id: Long, message: String) {
        val recipientString = Hex.toHexString(recipient)
        var added = false
        synchronized(connections) {
            if (connections.contains(recipientString)) {
                Log.i(TAG, "Found keep-alive connection, sending message.")
                connections[recipientString]?.addForDeliveryText(id, message)
                added = true
            }
        }
        // If there is no established connection we try to create one
        if (!added) {
            for (ip in ips) {
                //TODO Check priorities of IPs and try to send in descending priority
                Thread{
                    val connection = connect(recipient, ip)
                    if (added) return@Thread
                    if (connection != null) {
                        Log.i(TAG, "Created new connection, sending message.")
                        connection.addForDeliveryText(id, message)
                        synchronized(connections) {
                            added = true
                            connections[recipientString] = connection
                        }
                    } else {
                        Log.e(TAG, "Can not connect to $recipientString")
                    }
                }.start()
            }
        }
        // If we couldn't create any connections we fail
        if (!added) {
            listener.onMessageDelivered(recipient, id, false)
        }
    }

    private fun connect(recipient: ByteArray, address: String): ConnectionHandler? {
        for (i in 1..CONNECTION_TRIES + 1) {
            try {
                Log.d(TAG, "Connection attempt $i for $address")
                val socket = Socket()
                val socketAddress = InetSocketAddress(InetAddress.getByName(address),
                    CONNECTION_PORT
                )
                socket.connect(socketAddress, CONNECTION_TIMEOUT)
                if (socket.isConnected) {
                    val connection = ConnectionHandler(clientId, keyPair, socket, this)
                    connection.peerStatus = Status.ConnectedOut
                    connection.setPeerPublicKey(recipient)
                    connection.start()
                    return connection
                }
            } catch (e: IOException) {
                //e.printStackTrace()
                Log.e(TAG, "Error connecting to $address")
            }
            try {
                sleep(CONNECTION_PERIOD * i)
            } catch (e: InterruptedException) {
                // Nothing
            }
        }
        return null
    }

    override fun onServerStateChanged(online: Boolean) {
        // Nothing
    }

    override fun onClientIPChanged(old: String, new: String) {
        synchronized(connections) {
            if (connections.containsKey(old)) {
                val connectionHandler = connections.remove(old)!!
                connections[new] = connectionHandler
            }
        }
    }

    override fun onClientConnected(from: ByteArray, address: String, clientId: Int) {
        // When some client connects to us, we put `ConnectionHandler` in `connections` by the address
        // as we don't know the public key of newly connected client.
        // Now we can change it to correct key.
        synchronized(connections) {
            if (connections.containsKey(address)) {
                val connectionHandler = connections.remove(address)!!
                val publicKey = Hex.toHexString(from)
                connections[publicKey] = connectionHandler
            }
        }
        listener.onClientConnected(from, address, clientId)
    }

    override fun onMessageReceived(from: ByteArray, address: String, id: Long, message: String) {
        listener.onMessageReceived(from, address, id, message)
    }

    override fun onMessageDelivered(to: ByteArray, id: Long, delivered: Boolean) {
        listener.onMessageDelivered(to, id, delivered)
    }

    override fun onConnectionClosed(from: ByteArray, address: String) {
        synchronized(connections) {
            val pubKey = Hex.toHexString(from)
            Log.i(TAG, "Removing connection from $pubKey and $address")
            connections.remove(Hex.toHexString(from))
        }
    }
}

interface EventListener {
    fun onServerStateChanged(online: Boolean)
    fun onClientIPChanged(old: String, new: String) {}
    fun onClientConnected(from: ByteArray, address: String, clientId: Int)
    fun onMessageReceived(from: ByteArray, address: String, id: Long, message: String)
    fun onMessageDelivered(to: ByteArray, id: Long, delivered: Boolean)
    fun onConnectionClosed(from: ByteArray, address: String) {}
}