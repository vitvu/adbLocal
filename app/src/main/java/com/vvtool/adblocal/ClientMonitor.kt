package com.vvtool.adblocal

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Lists PCs connected to adbd by reading the kernel TCP table.
 * Android 9 and older: apps can read /proc/net/tcp directly.
 * Android 10+: it is hidden from apps, so we read it as the shell user over our own (paired) ADB connection.
 */
class ClientMonitor(private val ctx: Context) {
    private var adb: AdbClient? = null
    private val firstSeen = HashMap<String, Long>()

    fun poll(port: Int): List<ClientInfo>? {
        val table = readDirect() ?: readViaAdb(port) ?: return null
        val now = System.currentTimeMillis()
        val ips = parse(table, port)
        firstSeen.keys.retainAll(ips)
        return ips.map { ClientInfo(it, firstSeen.getOrPut(it) { now }) }.sortedBy { it.since }
    }

    fun close() {
        runCatching { adb?.close() }
        adb = null
    }

    private fun readDirect(): String? = runCatching {
        File("/proc/net/tcp").readText() + File("/proc/net/tcp6").readText()
    }.getOrNull()?.takeIf { it.lines().size > 2 }

    private fun readViaAdb(port: Int): String? {
        if (!AdbController.supported || !Prefs(ctx).paired) return null
        return try {
            val c = adb ?: AdbClient.create(ctx).also {
                it.setTimeout(8, TimeUnit.SECONDS)
                if (!it.connect(port)) throw IllegalStateException("connect failed")
                adb = it
            }
            c.openStream("shell:cat /proc/net/tcp /proc/net/tcp6").openInputStream().use { it.readBytes().decodeToString() }
        } catch (_: Exception) {
            close()
            null
        }
    }

    /** Remote IPs of ESTABLISHED connections whose local port is [port], loopback excluded. */
    private fun parse(table: String, port: Int): Set<String> {
        val portHex = "%04X".format(port)
        val out = HashSet<String>()
        for (line in table.lineSequence()) {
            val f = line.trim().split(Regex("\\s+"))
            if (f.size < 4 || f[3] != "01") continue
            if (!f[1].endsWith(":$portHex", ignoreCase = true)) continue
            val ip = decodeIp(f[2].substringBefore(':')) ?: continue
            if (!ip.startsWith("127.")) out += ip
        }
        return out
    }

    /** /proc/net stores IPv4 little-endian; IPv6 rows we care about are IPv4-mapped (last 8 hex digits). */
    private fun decodeIp(hex: String): String? {
        val v4 = when (hex.length) {
            8 -> hex
            32 -> if (hex.substring(0, 24).equals("0000000000000000FFFF0000", true)) hex.substring(24) else return null
            else -> return null
        }
        return (3 downTo 0).joinToString(".") { v4.substring(it * 2, it * 2 + 2).toInt(16).toString() }
    }
}
