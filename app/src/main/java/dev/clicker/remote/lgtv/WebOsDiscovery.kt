package dev.clicker.remote.lgtv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Finds LG webOS TVs with an SSDP search. */
object WebOsDiscovery {
    data class Found(val host: String, val name: String)

    suspend fun scan(durationMs: Int = 3000): List<Found> = withContext(Dispatchers.IO) {
        val search = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: urn:lge-com:service:webos-second-screen:1\r\n\r\n"
            ).toByteArray()
        val found = LinkedHashMap<String, Found>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 300
            val group = InetAddress.getByName("239.255.255.250")
            repeat(2) { socket.send(DatagramPacket(search, search.size, group, 1900)) }
            val end = System.currentTimeMillis() + durationMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < end) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length)
                if (!text.contains("webos-second-screen", ignoreCase = true)) continue
                val host = packet.address.hostAddress ?: continue
                val server = text.lineSequence().firstOrNull { it.startsWith("SERVER:", true) }?.substringAfter(':')?.trim()
                found[host] = Found(host, server ?: "LG webOS TV")
            }
        }
        found.values.toList()
    }
}
