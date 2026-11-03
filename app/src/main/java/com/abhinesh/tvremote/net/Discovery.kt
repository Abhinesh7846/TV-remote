package com.abhinesh.tvremote.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.abhinesh.tvremote.core.tls.ClientIdentity
import com.abhinesh.tvremote.core.tls.Tls
import com.abhinesh.tvremote.core.tls.TvCertificateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume

data class FoundTv(val name: String, val host: String, val port: Int, val btMac: String?, val via: String)

/** Finds Android TV Remote Services: mDNS/NSD first, a /24 port scan as fallback. */
class Discovery(private val context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    /** Null when the phone is on Wi-Fi or Ethernet; otherwise a reason to show. */
    fun networkProblem(): String? {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Your phone isn't connected to a network. Connect to the same Wi-Fi as the TV."
        val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return if (local) null else "Your phone isn't on Wi-Fi. Connect to the same Wi-Fi as the TV."
    }

    /** Calls [onAvailable] whenever the phone gets a (new) default network, e.g. Wi-Fi reconnects. */
    fun watchNetwork(onAvailable: () -> Unit) {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = onAvailable()
        })
    }

    fun discover(): Flow<List<FoundTv>> = callbackFlow {
        val found = LinkedHashMap<String, FoundTv>()
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        // Older Android allows only one resolve at a time, so resolve sequentially.
        launch {
            for (info in toResolve) {
                val resolved = resolve(info) ?: continue
                @Suppress("DEPRECATION")
                val host = resolved.host?.hostAddress ?: continue
                val bt = resolved.attributes["bt"]?.let { String(it) }
                found[resolved.serviceName] = FoundTv(resolved.serviceName, host, resolved.port, bt, "Wi-Fi discovery")
                trySend(found.values.toList())
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { toResolve.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) {
                if (found.remove(info.serviceName) != null) trySend(found.values.toList())
            }
        }
        trySend(emptyList())
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            toResolve.close()
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = withTimeoutOrNull(5_000) {
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null) }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) { if (cont.isActive) cont.resume(serviceInfo) }
            })
        }
    }

    /** Re-finds a paired TV whose IP changed, matching its Bluetooth address or service name. */
    suspend fun findHost(btMac: String?, name: String?): String? = withTimeoutOrNull(6_000) {
        discover().first { list -> list.any { (btMac != null && it.btMac.equals(btMac, true)) || it.name == name } }
            .first { (btMac != null && it.btMac.equals(btMac, true)) || it.name == name }.host
    }

    /**
     * Fallback when mDNS is blocked: probes the pairing port on every address of the phone's /24
     * and reads the model name from each TV's certificate.
     */
    suspend fun scanSubnet(identity: ClientIdentity, onProgress: (Int) -> Unit): List<FoundTv> = withContext(Dispatchers.IO) {
        val own = cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress } ?: return@withContext emptyList()
        val b = own.address
        val prefix = "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}."
        val gate = Semaphore(48)
        var done = 0
        coroutineScope {
            (1..254).map { i ->
                async {
                    gate.withPermit {
                        val host = prefix + i
                        val open = runCatching { Socket().use { it.connect(InetSocketAddress(host, 6467), 400); true } }.getOrDefault(false)
                        synchronized(this@Discovery) { done++; onProgress(done) }
                        if (!open) return@withPermit null
                        val info = runCatching { Tls.connect(host, 6467, identity, connectTimeoutMs = 1500).use { TvCertificateInfo.from(it.serverCertificate) } }.getOrNull()
                        FoundTv(info?.model ?: "Android TV", host, 6466, null, "network scan")
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp."
    }
}
