package com.glasscontrol.dyson.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.glasscontrol.dyson.core.logD
import com.glasscontrol.dyson.core.logW
import com.glasscontrol.dyson.domain.DiscoveredDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Finds Dyson machines on the local network over mDNS.
 *
 * Machines advertise `_dyson_mqtt._tcp` with a service name of the form
 * `<deviceType>_<serial>`, which is exactly the pair needed to build the MQTT
 * topics — so a discovered machine only still needs its credential.
 */
class DysonDiscovery(private val context: Context) {

    fun discover(timeoutMs: Long): Flow<DiscoveredDevice> = callbackFlow {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) {
            close()
            return@callbackFlow
        }

        // Resolving is serialised on older Android: NsdManager rejects a second
        // resolve while one is pending, so requests queue up here.
        val pending = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun emit(info: NsdServiceInfo) {
            val host = info.hostAddressCompat() ?: return
            val parsed = parseServiceName(info.serviceName) ?: return
            trySend(DiscoveredDevice(serial = parsed.second, deviceType = parsed.first, host = host))
        }

        lateinit var resolveNext: () -> Unit

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                logW("mDNS resolve failed ($errorCode)")
                resolving = false
                resolveNext()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                emit(serviceInfo)
                resolving = false
                resolveNext()
            }
        }

        resolveNext = {
            if (!resolving) {
                pending.removeFirstOrNull()?.let { next ->
                    resolving = true
                    @Suppress("DEPRECATION")
                    runCatching { nsdManager.resolveService(next, resolveListener) }
                        .onFailure { resolving = false }
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                logD("mDNS discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                pending.addLast(serviceInfo)
                resolveNext()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit

            override fun onDiscoveryStopped(serviceType: String?) = Unit

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                logW("mDNS discovery could not start ($errorCode)")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
        }

        runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure {
            logW("mDNS unavailable", it)
            close()
            return@callbackFlow
        }

        awaitClose {
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        }
    }.distinctUntilChanged()

    companion object {
        const val SERVICE_TYPE = "_dyson_mqtt._tcp."

        /**
         * Splits an advertised service name into device type and serial.
         *
         * @return type to serial, or null when the name is not a Dyson fan.
         */
        fun parseServiceName(serviceName: String): Pair<String, String>? {
            val separator = serviceName.indexOf('_')
            if (separator <= 0 || separator == serviceName.lastIndex) return null
            val deviceType = serviceName.substring(0, separator)
            val serial = serviceName.substring(separator + 1)
            if (deviceType.isBlank() || serial.isBlank()) return null
            return deviceType to serial
        }

        @Suppress("DEPRECATION")
        private fun NsdServiceInfo.hostAddressCompat(): String? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                hostAddresses.firstOrNull()?.hostAddress
            } else {
                host?.hostAddress
            }
    }
}
