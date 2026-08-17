package com.glasscontrol.dyson.data.local

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A minimal MQTT 3.1 broker that behaves like a Dyson machine.
 *
 * Dyson brokers speak only MQTT 3.1 with the serial as username, so the exact
 * handshake matters. Rather than trust that our client gets it right, this
 * implements just enough of the wire protocol to prove it: CONNECT/CONNACK with
 * credential checking, SUBSCRIBE/SUBACK, and PUBLISH in both directions.
 *
 * @param expectedUser serial the client must authenticate with.
 * @param expectedPassword local credential the client must present.
 */
class FakeDysonBroker(
    private val expectedUser: String,
    private val expectedPassword: String,
) {
    /** Every command payload the client published, in order. */
    val received = CopyOnWriteArrayList<Pair<String, String>>()

    /** Topics the client subscribed to. */
    val subscriptions = CopyOnWriteArrayList<String>()

    /** Set to reject the next connection with "bad username or password". */
    var rejectCredentials = false

    /** Replies published back when a command arrives, keyed by the `msg` field. */
    var replies: Map<String, () -> String> = emptyMap()

    /** Topic the replies are published on. */
    var statusTopic: String = ""

    private lateinit var server: ServerSocket
    private var running = false

    val port: Int get() = server.localPort

    fun start() {
        server = ServerSocket(0)
        running = true
        thread(isDaemon = true) {
            while (running) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(socket) } }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server.close() }
    }

    private fun serve(socket: Socket) {
        val input = DataInputStream(socket.getInputStream().buffered())
        val output = DataOutputStream(socket.getOutputStream())

        while (running && !socket.isClosed) {
            val header = input.read()
            if (header < 0) return
            val length = readRemainingLength(input)
            val body = ByteArray(length).also { if (length > 0) input.readFully(it) }

            when (header shr 4) {
                CONNECT -> handleConnect(body, output) || return
                SUBSCRIBE -> handleSubscribe(body, output)
                PUBLISH -> handlePublish(header, body, output)
                PINGREQ -> output.writePacket(PINGRESP shl 4, ByteArray(0))
                DISCONNECT -> return
            }
        }
    }

    /** @return false when the connection was refused and the socket should close. */
    private fun handleConnect(body: ByteArray, output: DataOutputStream): Boolean {
        val reader = ByteReader(body)
        reader.readString()          // protocol name: "MQIsdp" for 3.1
        reader.readByte()            // protocol level
        val flags = reader.readByte().toInt()
        reader.readShort()           // keep-alive
        reader.readString()          // client id

        val hasUser = flags and 0x80 != 0
        val hasPassword = flags and 0x40 != 0
        val user = if (hasUser) reader.readString() else null
        val password = if (hasPassword) reader.readString() else null

        val accepted = !rejectCredentials &&
            user == expectedUser &&
            password == expectedPassword

        // CONNACK: session-present flag, then the return code (0 = accepted,
        // 4 = bad username or password).
        output.writePacket(CONNACK shl 4, byteArrayOf(0, if (accepted) 0 else 4))
        return accepted
    }

    private fun handleSubscribe(body: ByteArray, output: DataOutputStream) {
        val reader = ByteReader(body)
        val packetId = reader.readShort()
        while (reader.hasMore()) {
            subscriptions += reader.readString()
            reader.readByte() // requested QoS
        }
        output.writePacket(
            SUBACK shl 4,
            byteArrayOf((packetId shr 8).toByte(), packetId.toByte(), 0),
        )
    }

    private fun handlePublish(header: Int, body: ByteArray, output: DataOutputStream) {
        val reader = ByteReader(body)
        val topic = reader.readString()
        val qos = (header shr 1) and 0x03
        val packetId = if (qos > 0) reader.readShort() else 0
        val payload = reader.readRest()

        received += topic to payload

        if (qos == 1) {
            output.writePacket(
                PUBACK shl 4,
                byteArrayOf((packetId shr 8).toByte(), packetId.toByte()),
            )
        }

        // Answer the way a machine does: a message per request type.
        val requested = Regex("\"msg\"\\s*:\\s*\"([^\"]+)\"").find(payload)?.groupValues?.get(1)
        replies[requested]?.let { reply -> output.publish(statusTopic, reply()) }
    }

    private fun DataOutputStream.publish(topic: String, payload: String) {
        val topicBytes = topic.toByteArray()
        val payloadBytes = payload.toByteArray()
        val body = ByteArray(2 + topicBytes.size + payloadBytes.size)
        body[0] = (topicBytes.size shr 8).toByte()
        body[1] = topicBytes.size.toByte()
        topicBytes.copyInto(body, 2)
        payloadBytes.copyInto(body, 2 + topicBytes.size)
        writePacket(PUBLISH shl 4, body)
    }

    @Synchronized
    private fun DataOutputStream.writePacket(header: Int, body: ByteArray) {
        write(header)
        writeRemainingLength(this, body.size)
        write(body)
        flush()
    }

    /** MQTT encodes lengths as 7 bits per byte with a continuation flag. */
    private fun readRemainingLength(input: DataInputStream): Int {
        var multiplier = 1
        var value = 0
        while (true) {
            val digit = input.read()
            if (digit < 0) return value
            value += (digit and 0x7F) * multiplier
            if (digit and 0x80 == 0) return value
            multiplier *= 128
        }
    }

    private fun writeRemainingLength(output: DataOutputStream, length: Int) {
        var remaining = length
        do {
            var digit = remaining % 128
            remaining /= 128
            if (remaining > 0) digit = digit or 0x80
            output.write(digit)
        } while (remaining > 0)
    }

    private class ByteReader(private val bytes: ByteArray) {
        private var index = 0

        fun hasMore() = index < bytes.size

        fun readByte(): Byte = bytes[index++]

        fun readShort(): Int {
            val value = ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)
            index += 2
            return value
        }

        fun readString(): String {
            val length = readShort()
            val value = String(bytes, index, length)
            index += length
            return value
        }

        fun readRest(): String {
            val value = String(bytes, index, bytes.size - index)
            index = bytes.size
            return value
        }
    }

    private companion object {
        const val CONNECT = 1
        const val CONNACK = 2
        const val PUBLISH = 3
        const val PUBACK = 4
        const val SUBSCRIBE = 8
        const val SUBACK = 9
        const val PINGREQ = 12
        const val PINGRESP = 13
        const val DISCONNECT = 14
    }
}
