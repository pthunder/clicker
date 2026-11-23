package dev.clicker.remote.appletv

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** HID button codes understood by tvOS over the Companion protocol. */
enum class HidCommand(val code: Int) {
    Up(1), Down(2), Left(3), Right(4), Menu(5), Select(6), Home(7),
    VolumeUp(8), VolumeDown(9), Siri(10), Screensaver(11), Sleep(12), Wake(13),
    PlayPause(14), ChannelUp(15), ChannelDown(16), Guide(17), PageUp(18), PageDown(19),
}

/** How this remote introduces itself to the Apple TV. Keep it stable between runs. */
data class ClientIdentity(
    val name: String,
    val rpId: String,      // 12 hex characters
    val publicId: String,  // MAC-style identifier, e.g. "02:AB:CD:EF:01:23"
    val model: String = "iPhone10,6",
)

class AppleTvException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Apple TV remote control over the Companion protocol (the protocol Apple's own Remote app uses).
 *
 * Usage: pair once with [beginPairing] and [finishPairing] (the Apple TV shows a 4-digit code),
 * store the returned [CompanionCredentials], then call [connect] and send commands.
 *
 * Protocol reference: pyatv (MIT), pyatv/protocols/companion.
 */
class AppleTvClient(
    private val host: String,
    private val port: Int,
    private val identity: ClientIdentity,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var connection: CompanionConnection? = null
    private var readerJob: Job? = null
    private var authResponses = Channel<Map<String, Any?>>(Channel.UNLIMITED)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Map<String, Any?>>>()
    private var xid = Random.nextLong(0, 0x10000)

    @Volatile var isConnected = false
        private set

    /** Called when an established connection drops (not on a deliberate close). */
    var onDisconnected: (() -> Unit)? = null

    // Pair-setup state between beginPairing and finishPairing
    private var pairSalt: ByteArray? = null
    private var pairServerKey: ByteArray? = null

    // ---- Pairing -------------------------------------------------------------------------

    /** Opens a pairing session. The Apple TV now shows a 4-digit code on screen. */
    suspend fun beginPairing() = withContext(Dispatchers.IO) {
        lock.withLock {
            closeLocked()
            openLocked()
            val tlv = authExchange(
                CompanionConnection.FrameType.PS_Start,
                mapOf("_pd" to Tlv8.write(Tlv8.METHOD to byteArrayOf(0), Tlv8.SEQ_NO to byteArrayOf(1)), "_pwTy" to 1),
            )
            pairSalt = tlv[Tlv8.SALT] ?: throw AppleTvException("Apple TV sent no salt")
            pairServerKey = tlv[Tlv8.PUBLIC_KEY] ?: throw AppleTvException("Apple TV sent no public key")
        }
    }

    /** Completes pairing with the code shown on the TV and returns long-term credentials. */
    suspend fun finishPairing(pin: String): CompanionCredentials = withContext(Dispatchers.IO) {
        lock.withLock {
            val salt = pairSalt ?: throw AppleTvException("Pairing was not started")
            val serverKey = pairServerKey!!
            try {
                val longTerm = HapCrypto.Ed25519KeyPair.generate()
                val srp = SrpClient(pin.trim(), HapCrypto.randomBytes(32))
                srp.process(salt, serverKey)

                val m4 = authExchange(
                    CompanionConnection.FrameType.PS_Next,
                    mapOf("_pd" to Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(3), Tlv8.PUBLIC_KEY to srp.publicKey, Tlv8.PROOF to srp.proof), "_pwTy" to 1),
                )
                val serverProof = m4[Tlv8.PROOF] ?: throw AppleTvException("Apple TV sent no proof")
                if (!srp.verifyServerProof(serverProof)) throw AppleTvException("Wrong code")

                val k = srp.sessionKey
                val deviceX = HapCrypto.hkdfSha512(k, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
                val encryptKey = HapCrypto.hkdfSha512(k, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
                val pairingId = UUID.randomUUID().toString().toByteArray()
                val signature = longTerm.sign(deviceX + pairingId + longTerm.publicKey)
                val inner = Tlv8.write(
                    Tlv8.IDENTIFIER to pairingId,
                    Tlv8.PUBLIC_KEY to longTerm.publicKey,
                    Tlv8.SIGNATURE to signature,
                    Tlv8.NAME to Opack.pack(mapOf("name" to identity.name)),
                )
                val m6 = authExchange(
                    CompanionConnection.FrameType.PS_Next,
                    mapOf(
                        "_pd" to Tlv8.write(
                            Tlv8.SEQ_NO to byteArrayOf(5),
                            Tlv8.ENCRYPTED_DATA to HapCrypto.seal(encryptKey, HapCrypto.labelNonce("PS-Msg05"), inner),
                        ),
                        "_pwTy" to 1,
                    ),
                )
                val encrypted = m6[Tlv8.ENCRYPTED_DATA] ?: throw AppleTvException("Apple TV sent no keys")
                val device = Tlv8.read(HapCrypto.open(encryptKey, HapCrypto.labelNonce("PS-Msg06"), encrypted))
                CompanionCredentials(
                    ltpk = device[Tlv8.PUBLIC_KEY] ?: throw AppleTvException("missing Apple TV key"),
                    ltsk = longTerm.seed,
                    atvId = device[Tlv8.IDENTIFIER] ?: throw AppleTvException("missing Apple TV id"),
                    clientId = pairingId,
                )
            } finally {
                pairSalt = null
                pairServerKey = null
                closeLocked()
            }
        }
    }

    // ---- Session ---------------------------------------------------------------------------

    suspend fun connect(credentials: CompanionCredentials) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (isConnected) return@withLock
            closeLocked()
            openLocked()
            try {
                verify(credentials)
                request("_systemInfo", mapOf(
                    "_bf" to 0, "_cf" to 512, "_clFl" to 128,
                    "_i" to identity.rpId,
                    "_idsID" to credentials.clientId,
                    "_pubID" to identity.publicId,
                    "_sf" to 256, "_sv" to "170.18",
                    "model" to identity.model,
                    "name" to identity.name,
                ))
                request("_touchStart", mapOf("_height" to 1000.0, "_tFl" to 0, "_width" to 1000.0))
                request("_sessionStart", mapOf("_srvT" to "com.apple.tvremoteservices", "_sid" to Random.nextLong(0, 0xFFFFFFFFL)))
                runCatching { request("TVRCSessionStart", mapOf("ProtocolVersionKey" to "1.2")) }
                isConnected = true
            } catch (e: Exception) {
                closeLocked()
                throw if (e is AppleTvException) e else AppleTvException("Could not connect: ${e.message}", e)
            }
        }
    }

    private suspend fun verify(credentials: CompanionCredentials) {
        val ephemeral = HapCrypto.X25519KeyPair()
        val m2 = authExchange(
            CompanionConnection.FrameType.PV_Start,
            mapOf("_pd" to Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(1), Tlv8.PUBLIC_KEY to ephemeral.publicKey), "_auTy" to 4),
        )
        val serverKey = m2[Tlv8.PUBLIC_KEY] ?: throw AppleTvException("Apple TV sent no session key")
        val encrypted = m2[Tlv8.ENCRYPTED_DATA] ?: throw AppleTvException("Apple TV sent no proof")
        val shared = ephemeral.agree(serverKey)
        val sessionKey = HapCrypto.hkdfSha512(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")

        val device = Tlv8.read(HapCrypto.open(sessionKey, HapCrypto.labelNonce("PV-Msg02"), encrypted))
        val deviceId = device[Tlv8.IDENTIFIER] ?: throw AppleTvException("Apple TV sent no identifier")
        val deviceSig = device[Tlv8.SIGNATURE] ?: throw AppleTvException("Apple TV sent no signature")
        if (!deviceId.contentEquals(credentials.atvId)) {
            throw AppleTvException("This is a different Apple TV than the one paired")
        }
        if (!HapCrypto.ed25519Verify(credentials.ltpk, serverKey + deviceId + ephemeral.publicKey, deviceSig)) {
            throw AppleTvException("Apple TV signature check failed")
        }

        val signature = HapCrypto.Ed25519KeyPair(credentials.ltsk).sign(ephemeral.publicKey + credentials.clientId + serverKey)
        val m3 = HapCrypto.seal(
            sessionKey, HapCrypto.labelNonce("PV-Msg03"),
            Tlv8.write(Tlv8.IDENTIFIER to credentials.clientId, Tlv8.SIGNATURE to signature),
        )
        authExchange(
            CompanionConnection.FrameType.PV_Next,
            mapOf("_pd" to Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(3), Tlv8.ENCRYPTED_DATA to m3)),
        )
        connection!!.enableEncryption(
            HapCrypto.hkdfSha512(shared, "", "ClientEncrypt-main"),
            HapCrypto.hkdfSha512(shared, "", "ServerEncrypt-main"),
        )
    }

    // ---- Commands --------------------------------------------------------------------------

    /** Sends just the press ([down] = true) or just the release of a button, for press-and-hold. */
    suspend fun hid(command: HidCommand, down: Boolean) {
        request("_hidC", mapOf("_hBtS" to if (down) 1 else 2, "_hidC" to command.code))
    }

    /** Presses and releases a button. */
    suspend fun press(command: HidCommand, holdMs: Long = 0) {
        request("_hidC", mapOf("_hBtS" to 1, "_hidC" to command.code))
        if (holdMs > 0) delay(holdMs)
        request("_hidC", mapOf("_hBtS" to 2, "_hidC" to command.code))
    }

    /** Wakes the Apple TV (and, through HDMI-CEC, usually the TV). */
    suspend fun wake() = request("_hidC", mapOf("_hBtS" to 2, "_hidC" to HidCommand.Wake.code))

    suspend fun sleep() = request("_hidC", mapOf("_hBtS" to 2, "_hidC" to HidCommand.Sleep.code))

    suspend fun launchApp(bundleId: String) {
        request("_launchApp", mapOf("_bundleID" to bundleId))
    }

    /** Installed apps as bundle id to display name. */
    suspend fun apps(): Map<String, String> {
        val response = request("FetchLaunchableApplicationsEvent", emptyMap())
        val content = response["_c"] as? Map<*, *> ?: return emptyMap()
        return content.entries.mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }.toMap()
    }

    fun close() {
        scope.launch { lock.withLock { closeLocked() } }
    }

    // ---- Plumbing --------------------------------------------------------------------------

    private fun openLocked() {
        val conn = CompanionConnection(host, port)
        try {
            conn.open()
        } catch (e: IOException) {
            throw AppleTvException("Can't reach the Apple TV at $host:$port", e)
        }
        connection = conn
        authResponses = Channel(Channel.UNLIMITED)
        val responses = authResponses
        readerJob = scope.launch {
            try {
                while (true) {
                    val frame = conn.receive()
                    val type = frame.type ?: continue
                    if (frame.payload.isEmpty()) continue
                    val message = runCatching { Opack.unpackMap(frame.payload) }.getOrNull() ?: continue
                    when (type) {
                        CompanionConnection.FrameType.PS_Start, CompanionConnection.FrameType.PS_Next,
                        CompanionConnection.FrameType.PV_Start, CompanionConnection.FrameType.PV_Next,
                        -> responses.trySend(message)
                        else -> if (message["_t"] == 3L) {
                            (message["_x"] as? Long)?.let { pending.remove(it)?.complete(message) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Apple TV connection ended: $e")
                val wasConnected = isConnected
                isConnected = false
                if (wasConnected) onDisconnected?.invoke()
                responses.close(AppleTvException("Connection to Apple TV closed", e))
                val error = AppleTvException("Connection to Apple TV closed", e)
                pending.values.forEach { it.completeExceptionally(error) }
                pending.clear()
            }
        }
    }

    private fun closeLocked() {
        if (connection != null) Log.i(TAG, "closing Apple TV connection")
        isConnected = false
        connection?.close()
        connection = null
        readerJob?.cancel()
        readerJob = null
    }

    private suspend fun authExchange(type: CompanionConnection.FrameType, message: Map<String, Any?>): Map<Int, ByteArray> {
        val conn = connection ?: throw AppleTvException("Not connected")
        Log.i(TAG, ">> auth ${type.name} keys=${message.keys}")
        conn.send(type, Opack.pack(message))
        val reply = withTimeout(TIMEOUT_MS) { authResponses.receive() }
        val data = reply["_pd"] as? ByteArray ?: throw AppleTvException("Apple TV sent no pairing data")
        val tlv = Tlv8.read(data)
        Tlv8.describeError(tlv)?.let { throw AppleTvException(it.replaceFirstChar(Char::uppercase)) }
        return tlv
    }

    private suspend fun request(identifier: String, content: Map<String, Any?>): Map<String, Any?> {
        val conn = connection ?: throw AppleTvException("Not connected")
        val id = synchronized(this) { xid++ }
        val waiter = CompletableDeferred<Map<String, Any?>>()
        pending[id] = waiter
        try {
            conn.send(
                CompanionConnection.FrameType.E_OPACK,
                Opack.pack(mapOf("_i" to identifier, "_t" to 2, "_c" to content, "_x" to id)),
            )
            return withTimeout(TIMEOUT_MS) { waiter.await() }
        } catch (e: IOException) {
            isConnected = false
            throw AppleTvException("Lost connection to Apple TV", e)
        } finally {
            pending.remove(id)
        }
    }

    companion object {
        private const val TAG = "ClickerATV"
        const val DEFAULT_PORT = 49153
        private const val TIMEOUT_MS = 6000L
    }
}
