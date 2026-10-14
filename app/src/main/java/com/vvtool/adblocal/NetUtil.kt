package com.vvtool.adblocal

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

object NetUtil {
    data class Addr(val iface: String, val ip: String) {
        val kind: String
            get() = when {
                iface.startsWith("wlan0") -> "Wi-Fi"
                iface.startsWith("eth") -> "Ethernet"
                iface.startsWith("ap") || iface.startsWith("swlan") || iface.startsWith("wlan") -> "Hotspot"
                iface.startsWith("rndis") || iface.startsWith("usb") || iface.startsWith("ncm") -> "USB tether"
                iface.startsWith("bt-pan") -> "Bluetooth"
                iface.startsWith("p2p") -> "Wi-Fi Direct"
                else -> iface
            }
    }

    // Mobile data / VPN / virtual interfaces are not reachable from the LAN.
    private val skip = listOf("rmnet", "ccmni", "v4-", "dummy", "tun", "ipsec", "lo", "clat", "pdp", "seth", "ifb")

    /** IPv4 addresses a PC on the same LAN can reach, Wi-Fi first. */
    fun lanAddresses(): List<Addr> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .filter { nif -> skip.none { nif.name.startsWith(it) } }
            .flatMap { nif ->
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .map { Addr(nif.name, it.hostAddress ?: "") }
            }
            .sortedBy { if (it.iface.startsWith("wlan0")) 0 else if (it.iface.startsWith("eth")) 1 else 2 }
    } catch (_: Exception) {
        emptyList()
    }

    fun isWifiConnected(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return cm.allNetworks.any {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    fun isPortOpen(port: Int, timeoutMs: Int = 400): Boolean {
        if (port !in 1..65535) return false
        return try {
            Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), timeoutMs); true }
        } catch (_: Exception) {
            false
        }
    }
}
