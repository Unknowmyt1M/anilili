package com.anilili.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean

/** Process-lifetime native callbacks for network transitions and thermal throttling. */
object DiagnosticsSystemMonitor {
    private val installed = AtomicBoolean(false)

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        installNetworkMonitor(context.applicationContext)
        installThermalMonitor(context.applicationContext)
    }

    private fun installNetworkMonitor(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    DiagnosticsLog.event("network", "default.available")
                }

                override fun onLost(network: Network) {
                    DiagnosticsLog.event("network", "default.lost")
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    DiagnosticsLog.event(
                        category = "network",
                        name = "default.capabilities_changed",
                        attributes = mapOf(
                            "transport" to transport(capabilities),
                            "validated" to capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                            "metered" to !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                            "captivePortal" to capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                            "vpn" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                            "downKbps" to capabilities.linkDownstreamBandwidthKbps,
                            "upKbps" to capabilities.linkUpstreamBandwidthKbps,
                        ),
                    )
                }
            })
        }.onFailure { DiagnosticsLog.throwable("default network monitor unavailable", it) }
    }

    private fun installThermalMonitor(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ThermalApi29.install(context)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private object ThermalApi29 {
        fun install(context: Context) {
            val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            runCatching {
                manager.addThermalStatusListener { status ->
                    DiagnosticsLog.event("system", "thermal.changed", mapOf("status" to status))
                }
            }.onFailure { DiagnosticsLog.throwable("thermal monitor unavailable", it) }
        }
    }

    private fun transport(capabilities: NetworkCapabilities): String = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
        else -> "other"
    }
}
