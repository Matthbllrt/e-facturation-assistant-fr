package com.glasscontrol.dyson.data.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import com.glasscontrol.dyson.core.logD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Finds machines listening on the Dyson MQTT port by sweeping the local subnet.
 *
 * mDNS is the intended way to locate a machine, but plenty of home networks
 * never deliver it — guest VLANs, AP isolation and routers that drop multicast
 * all break it, and Android's own NSD stack is unreliable besides. When that
 * happens the app is otherwise dead in the water, so this is the fallback:
 * knock on port 1883 across the subnet and let the caller check which responder
 * accepts the machine's credentials.
 */
class LanScanner(private val context: Context) {

    /**
     * @return addresses that accepted a TCP connection on [port], nearest first.
     */
    suspend fun scan(
        port: Int = 1883,
        connectTimeoutMs: Int = 400,
    ): List<String> = withContext(Dispatchers.IO) {
        val candidates = candidateAddresses() ?: return@withContext emptyList()
        logD("LAN sweep over ${candidates.size} addresses")

        // Bounded parallelism: enough to finish in a couple of seconds without
        // opening hundreds of sockets at once on a phone.
        val gate = Semaphore(PARALLELISM)
        coroutineScope {
            candidates.map { address ->
                async {
                    gate.withPermit {
                        if (isOpen(address, port, connectTimeoutMs)) address else null
                    }
                }
            }.awaitAll()
        }.filterNotNull()
    }

    private fun isOpen(address: String, port: Int, timeoutMs: Int): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, port), timeoutMs)
                true
            }
        }.getOrDefault(false)

    /**
     * Every host address on the phone's own IPv4 subnet, excluding itself.
     *
     * Anything wider than a /22 is skipped: sweeping it would take far too long
     * to be useful, and home networks are practically always /24.
     */
    private fun candidateAddresses(): List<String>? {
        val link = localLinkAddress() ?: return null
        val prefix = link.prefixLength
        if (prefix < MIN_PREFIX_LENGTH || prefix > 30) return null

        val self = link.address as? Inet4Address ?: return null
        val selfInt = self.address.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val network = selfInt and mask
        val broadcast = network or mask.inv()

        return ((network + 1) until broadcast)
            .filter { it != selfInt }
            .map { value ->
                "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}." +
                    "${(value ushr 8) and 0xFF}.${value and 0xFF}"
            }
    }

    private fun localLinkAddress(): LinkAddress? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = manager.activeNetwork ?: return null
        val properties = manager.getLinkProperties(network) ?: return null
        return properties.linkAddresses.firstOrNull { it.address is Inet4Address }
    }

    private companion object {
        const val PARALLELISM = 64
        const val MIN_PREFIX_LENGTH = 22
    }
}
