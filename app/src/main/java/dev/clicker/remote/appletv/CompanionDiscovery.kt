package dev.clicker.remote.appletv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue

/** Finds Apple TVs on the local network by their Companion service (_companion-link._tcp). */
class CompanionDiscovery(context: Context) {

    data class Found(val name: String, val host: String, val port: Int)

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    suspend fun scan(durationMs: Long = 4000): List<Found> {
        val results = ConcurrentLinkedQueue<Found>()
        val toResolve = ConcurrentLinkedQueue<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { toResolve += info }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices("_companion-link._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        val end = System.currentTimeMillis() + durationMs
        // NsdManager resolves one service at a time, so resolve sequentially.
        while (System.currentTimeMillis() < end) {
            val next = toResolve.poll()
            if (next == null) { delay(100); continue }
            resolve(next)?.let { found -> if (results.none { it.host == found.host }) results += found }
        }
        runCatching { nsd.stopServiceDiscovery(listener) }
        return results.sortedBy { it.name }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): Found? {
        val result = CompletableDeferred<Found?>()
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { result.complete(null) }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress
                result.complete(
                    if (host != null && !host.contains(':')) Found(serviceInfo.serviceName, host, serviceInfo.port) else null,
                )
            }
        })
        return withTimeoutOrNull(2000) { result.await() }
    }
}
