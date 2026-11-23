package dev.clicker.remote.appletv

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * One TCP connection to the Apple TV's Companion service (usually port 49153).
 *
 * Frame: 1 byte type, 3 byte big-endian payload length, payload. After pair-verify, payloads
 * are ChaCha20-Poly1305 encrypted with the 4-byte header as associated data.
 */
class CompanionConnection(private val host: String, private val port: Int) {

    enum class FrameType(val code: Int) {
        NoOp(1), PS_Start(3), PS_Next(4), PV_Start(5), PV_Next(6),
        U_OPACK(7), E_OPACK(8), P_OPACK(9);

        companion object {
            fun of(code: Int) = entries.firstOrNull { it.code == code }
        }
    }

    data class Frame(val typeCode: Int, val payload: ByteArray) {
        val type: FrameType? get() = FrameType.of(typeCode)
    }

    private var socket: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream

    @Volatile private var outKey: ByteArray? = null
    @Volatile private var inKey: ByteArray? = null
    private var outCounter = 0L
    private var inCounter = 0L

    val isOpen: Boolean get() = socket?.let { it.isConnected && !it.isClosed } == true

    fun open(timeoutMs: Int = 4000) {
        val s = Socket()
        s.tcpNoDelay = true
        s.keepAlive = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        socket = s
        input = DataInputStream(s.getInputStream().buffered())
        output = s.getOutputStream()
    }

    fun enableEncryption(outputKey: ByteArray, inputKey: ByteArray) {
        outKey = outputKey
        inKey = inputKey
        outCounter = 0
        inCounter = 0
    }

    @Synchronized
    fun send(type: FrameType, payload: ByteArray) {
        val key = outKey
        val length = if (key != null && payload.isNotEmpty()) payload.size + 16 else payload.size
        val header = byteArrayOf(type.code.toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte())
        val body = if (key != null && payload.isNotEmpty()) {
            HapCrypto.seal(key, HapCrypto.counterNonce(outCounter++), payload, header)
        } else payload
        output.write(header + body)
        output.flush()
    }

    /** Blocks until a whole frame arrives. Call from a single reader thread. */
    fun receive(): Frame {
        val header = ByteArray(4)
        input.readFully(header)
        val length = ((header[1].toInt() and 0xFF) shl 16) or ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val body = ByteArray(length)
        input.readFully(body)
        val key = inKey
        val payload = if (key != null && length > 0) {
            try {
                HapCrypto.open(key, HapCrypto.counterNonce(inCounter++), body, header)
            } catch (e: Exception) {
                throw IOException("could not decrypt frame from Apple TV", e)
            }
        } else body
        return Frame(header[0].toInt() and 0xFF, payload)
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}
