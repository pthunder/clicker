package dev.clicker.remote.lgtv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class WebOsException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * LG webOS TV control over the local network (the "SSAP" WebSocket API used by LG's phone app).
 *
 * First connection shows an "allow this device" prompt on the TV; the returned client key is
 * reused after that. Newer TVs only accept the secure port 3001, which uses a self-signed
 * certificate. That certificate is pinned on first use: its fingerprint is remembered after the
 * first successful pairing ([onCertificate]) and any other certificate is refused afterwards, so
 * a device impersonating the TV can't collect the client key.
 *
 * Protocol reference: aiowebostv (Apache-2.0).
 */
class WebOsClient(
    private val host: String,
    private val registrationTemplate: String,
    /** SHA-256 of the TV's certificate from an earlier pairing, or null if not paired yet. */
    private val pinnedCertificate: String? = null,
) {
    /** The fingerprint seen on this connection; the caller stores it after pairing succeeds. */
    @Volatile var seenCertificate: String? = null
        private set

    private val http: OkHttpClient = trustingClient()
    private val lock = Mutex()
    private var socket: WebSocket? = null
    private var inputSocket: WebSocket? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private var registration = Channel<JSONObject>(Channel.UNLIMITED)

    @Volatile var isConnected = false
        private set

    /** Called when an established connection drops (not on a deliberate close). */
    var onDisconnected: (() -> Unit)? = null

    /**
     * Connects and registers. Returns the client key to store; pass it back next time to skip
     * the prompt. [onPrompt] is called when the TV is showing its "allow" prompt.
     */
    suspend fun connect(clientKey: String?, onPrompt: () -> Unit = {}): String = withContext(Dispatchers.IO) {
        lock.withLock {
            if (isConnected) return@withLock clientKey ?: ""
            closeLocked()
            val opened = openSocket("wss://$host:3001") ?: openSocket("ws://$host:3000")
                ?: throw WebOsException("Can't reach the LG TV at $host (is it on?)")
            socket = opened
            try {
                var response = register(opened, clientKey, signed = true)
                if (response.optString("type") == "error") response = register(opened, clientKey, signed = false)
                if (response.optString("type") == "response" &&
                    response.optJSONObject("payload")?.optString("pairingType") == "PROMPT"
                ) {
                    onPrompt()
                    response = withTimeoutOrNull(60_000) { registration.receive() }
                        ?: throw WebOsException("Nobody accepted the prompt on the TV")
                }
                if (response.optString("type") != "registered") {
                    throw WebOsException("TV refused: ${response.optString("error", "pairing failed")}")
                }
                isConnected = true
                response.optJSONObject("payload")?.optString("client-key")?.takeIf { it.isNotEmpty() } ?: clientKey
                    ?: throw WebOsException("TV sent no client key")
            } catch (e: Exception) {
                closeLocked()
                throw e
            }
        }
    }

    private suspend fun register(ws: WebSocket, clientKey: String?, signed: Boolean): JSONObject {
        val message = JSONObject(registrationTemplate)
        val payload = message.getJSONObject("payload")
        if (!signed) {
            val manifest = payload.getJSONObject("manifest")
            manifest.remove("signatures")
            val merged = sortedSetOf<String>()
            manifest.getJSONArray("permissions").let { for (i in 0 until it.length()) merged += it.getString(i) }
            manifest.getJSONObject("signed").getJSONArray("permissions").let { for (i in 0 until it.length()) merged += it.getString(i) }
            manifest.remove("signed")
            manifest.put("permissions", JSONArray(merged.toList()))
        }
        if (!clientKey.isNullOrEmpty()) payload.put("client-key", clientKey)
        ws.send(message.toString())
        return withTimeoutOrNull(10_000) { registration.receive() }
            ?: throw WebOsException("TV did not answer the registration")
    }

    /** Sends a request such as "audio/volumeUp" and returns the payload of the reply. */
    suspend fun request(uri: String, payload: JSONObject = JSONObject()): JSONObject {
        val ws = socket ?: throw WebOsException("Not connected to the TV")
        val id = "req_${nextId.getAndIncrement()}"
        val waiter = CompletableDeferred<JSONObject>()
        pending[id] = waiter
        try {
            val message = JSONObject().put("id", id).put("type", "request").put("uri", "ssap://$uri").put("payload", payload)
            if (!ws.send(message.toString())) throw WebOsException("Connection to the TV closed")
            val reply = withTimeout(6_000) { waiter.await() }
            if (reply.optString("type") == "error") throw WebOsException(reply.optString("error", "TV returned an error"))
            return reply.optJSONObject("payload") ?: JSONObject()
        } finally {
            pending.remove(id)
        }
    }

    // ---- Commands -------------------------------------------------------------------------

    suspend fun volumeUp() { request("audio/volumeUp") }
    suspend fun volumeDown() { request("audio/volumeDown") }
    suspend fun setMute(mute: Boolean) { request("audio/setMute", JSONObject().put("mute", mute)) }
    suspend fun isMuted(): Boolean = request("audio/getStatus").optBoolean("mute", false)
    suspend fun powerOff() { request("system/turnOff") }

    suspend fun switchInput(inputId: String) { request("tv/switchInput", JSONObject().put("inputId", inputId)) }

    suspend fun inputs(): List<Pair<String, String>> {
        val devices = request("tv/getExternalInputList").optJSONArray("devices") ?: return emptyList()
        return (0 until devices.length()).map { devices.getJSONObject(it) }.map { it.optString("id") to it.optString("label") }
    }

    data class App(val id: String, val title: String, val icon: String?)

    /** Installed apps with their icon URLs (served by the TV). */
    suspend fun apps(): List<App> {
        val points = request("com.webos.applicationManager/listLaunchPoints").optJSONArray("launchPoints") ?: return emptyList()
        return (0 until points.length()).map { points.getJSONObject(it) }.map {
            App(it.optString("id"), it.optString("title"), it.optString("largeIcon").ifEmpty { it.optString("icon") }.ifEmpty { null })
        }
    }

    /** Downloads a resource served by this TV (such as an app icon), trusting its own certificate. */
    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        if (request.url.host != host) throw WebOsException("Not a URL on this TV")
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw WebOsException("HTTP ${response.code}")
            response.body?.bytes() ?: throw WebOsException("Empty response")
        }
    }

    suspend fun launch(appId: String) { request("system.launcher/launch", JSONObject().put("id", appId)) }

    /** Network MAC addresses of the TV, used later to wake it with Wake-on-LAN. */
    suspend fun macAddresses(): List<String> {
        val info = request("com.webos.service.connectionmanager/getinfo")
        return listOfNotNull(
            info.optJSONObject("wiredInfo")?.optString("macAddress"),
            info.optJSONObject("wifiInfo")?.optString("macAddress"),
        ).filter { it.isNotBlank() }
    }

    /** Remote-control button: UP, DOWN, LEFT, RIGHT, ENTER, BACK, HOME, EXIT, MENU, PLAY, PAUSE ... */
    suspend fun button(name: String) {
        val ws = inputSocket ?: openInputSocket()
        if (!ws.send("type:button\nname:$name\n\n")) {
            inputSocket = null
            openInputSocket().send("type:button\nname:$name\n\n")
        }
    }

    private suspend fun openInputSocket(): WebSocket = withContext(Dispatchers.IO) {
        val path = request("com.webos.service.networkinput/getPointerInputSocket").optString("socketPath")
        if (path.isEmpty()) throw WebOsException("TV gave no input socket")
        val ws = openSocket(path, listen = false) ?: throw WebOsException("Could not open the TV's input socket")
        inputSocket = ws
        ws
    }

    fun close() {
        closeLocked()
    }

    // ---- Plumbing ----------------------------------------------------------------------------

    private fun closeLocked() {
        isConnected = false
        inputSocket?.close(1000, null)
        inputSocket = null
        socket?.close(1000, null)
        socket = null
        pending.values.forEach { it.completeExceptionally(WebOsException("Disconnected")) }
        pending.clear()
        registration = Channel(Channel.UNLIMITED)
    }

    private suspend fun openSocket(url: String, listen: Boolean = true): WebSocket? {
        val opened = CompletableDeferred<Boolean>()
        val reg = registration
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(true) }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!listen) return
                val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                val id = message.optString("id")
                val waiter = pending[id]
                if (waiter != null) waiter.complete(message)
                else if (id.startsWith("register")) reg.trySend(message)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.complete(false)
                if (listen && webSocket == socket) {
                    if (isConnected) onDisconnected?.invoke()
                    isConnected = false
                    pending.values.forEach { it.completeExceptionally(WebOsException("Connection to the TV lost", t)) }
                }
                if (webSocket == inputSocket) inputSocket = null
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // The TV is closing the connection: finish the close and stop treating it as live.
                webSocket.close(1000, null)
                if (listen && webSocket == socket) {
                    if (isConnected) onDisconnected?.invoke()
                    isConnected = false
                    pending.values.forEach { it.completeExceptionally(WebOsException("The TV closed the connection")) }
                }
                if (webSocket == inputSocket) inputSocket = null
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (listen && webSocket == socket) {
                    if (isConnected) onDisconnected?.invoke()
                    isConnected = false
                }
                if (webSocket == inputSocket) inputSocket = null
            }
        }
        val ws = http.newWebSocket(Request.Builder().url(url).build(), listener)
        var ok = false
        try {
            ok = withTimeoutOrNull(5_000) { opened.await() } ?: false
        } finally {
            if (!ok) ws.cancel() // also on cancellation, so the socket never leaks
        }
        return if (ok) ws else null
    }

    private fun trustingClient(): OkHttpClient {
        // The TV presents a self-signed certificate: accept it only for this TV's address, and
        // once pinned, only that exact certificate.
        val pinning = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("No certificate")
                val fingerprint = fingerprint(leaf)
                if (pinnedCertificate != null && !pinnedCertificate.equals(fingerprint, ignoreCase = true)) {
                    throw CertificateException("The TV's certificate changed. Reconnect it in Settings if you trust it.")
                }
                seenCertificate = fingerprint
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val trustAll = pinning
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { hostname, _ -> hostname == host }
            .connectTimeout(4, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

        /** Sends a Wake-on-LAN magic packet. Works only if the TV allows "Turn on via Wi-Fi/LAN". */
        fun wakeOnLan(mac: String, broadcast: String = "255.255.255.255") {
            val macBytes = mac.split(':', '-').map { it.toInt(16).toByte() }.toByteArray()
            require(macBytes.size == 6) { "bad MAC address" }
            val packet = ByteArray(6) { 0xFF.toByte() } + (1..16).flatMap { macBytes.toList() }
            DatagramSocket().use { s ->
                s.broadcast = true
                for (port in intArrayOf(9, 7)) {
                    s.send(DatagramPacket(packet, packet.size, InetAddress.getByName(broadcast), port))
                }
            }
        }
    }
}
