package com.vvtool.adblocal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Brings the device's own adbd up on a TCP port and tells the proxy where it is.
 *
 *  - ROOT:     setprop service.adb.tcp.port <port> + restart adbd. Works on any interface.
 *  - WIRELESS: Android 11+ "Wireless debugging" (TLS). Needs WRITE_SECURE_SETTINGS to toggle it;
 *              adbd picks a random port, which we discover and forward to the fixed port.
 *  - LEGACY:   adbd already listening after `adb tcpip 5555` from a PC.
 */
object AdbController {
    const val MODE_AUTO = "auto"
    const val MODE_ROOT = "root"
    const val MODE_WIRELESS = "wireless"
    const val MODE_LEGACY = "legacy"

    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    class Backend(
        val label: String,
        /** adbd itself listens on the user port, no proxy needed. */
        val direct: Boolean,
        val targetPort: () -> Int,
        val error: String? = null,
    )

    private fun fail(msg: String) = Backend("", false, { -1 }, msg)

    fun hasSecureSettings(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isUsbDebuggingOn(ctx: Context) =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    fun isWirelessDebuggingOn(ctx: Context) =
        Settings.Global.getInt(ctx.contentResolver, ADB_WIFI_ENABLED, 0) == 1

    fun setUsbDebugging(ctx: Context, on: Boolean) = putGlobal(ctx, Settings.Global.ADB_ENABLED, on)

    fun setWirelessDebugging(ctx: Context, on: Boolean) = putGlobal(ctx, ADB_WIFI_ENABLED, on)

    private fun putGlobal(ctx: Context, key: String, on: Boolean): Boolean = try {
        Settings.Global.putInt(ctx.contentResolver, key, if (on) 1 else 0)
    } catch (_: SecurityException) {
        false
    }

    /** Self-grant WRITE_SECURE_SETTINGS when root is available. */
    fun grantSecureWithRoot(ctx: Context): Boolean =
        Shell.su("pm grant ${ctx.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}").ok &&
            hasSecureSettings(ctx)

    fun resolveMode(ctx: Context, mode: String): String {
        if (mode != MODE_AUTO) return mode
        return when {
            Shell.hasRoot() -> MODE_ROOT
            Build.VERSION.SDK_INT >= 30 && NetUtil.isWifiConnected(ctx) &&
                (hasSecureSettings(ctx) || isWirelessDebuggingOn(ctx)) -> MODE_WIRELESS
            else -> MODE_LEGACY
        }
    }

    /**
     * @param allowToggle may switch ADB / wireless debugging on (auto-on-Wi-Fi or explicit user start).
     */
    fun prepare(ctx: Context, prefs: Prefs, allowToggle: Boolean): Backend {
        val port = prefs.port
        if (allowToggle && hasSecureSettings(ctx) && !isUsbDebuggingOn(ctx)) setUsbDebugging(ctx, true)
        return when (resolveMode(ctx, prefs.mode)) {
            MODE_ROOT -> prepareRoot(port)
            MODE_WIRELESS -> prepareWireless(ctx, port, allowToggle)
            else -> prepareLegacy(prefs.legacyPort, port)
        }
    }

    private fun prepareRoot(port: Int): Backend {
        if (!Shell.hasRoot()) return fail("Không có quyền root")
        val current = Shell.su("getprop service.adb.tcp.port").out.trim()
        if (current != port.toString() || !NetUtil.isPortOpen(port)) {
            Shell.su("setprop service.adb.tcp.port $port; stop adbd; start adbd")
            if (!waitOpen(port, 6000)) return fail("adbd không mở cổng $port sau khi khởi động lại")
        }
        return Backend("Root · adbd TCP", direct = true, targetPort = { port })
    }

    private fun prepareWireless(ctx: Context, port: Int, allowToggle: Boolean): Backend {
        if (Build.VERSION.SDK_INT < 30) return fail("Gỡ lỗi không dây cần Android 11 trở lên")
        if (!NetUtil.isWifiConnected(ctx)) {
            return fail("Gỡ lỗi không dây chỉ chạy khi có Wi-Fi. Dùng chế độ Root cho Ethernet / USB / Hotspot")
        }
        if (!isWirelessDebuggingOn(ctx)) {
            if (!hasSecureSettings(ctx)) return fail("Chưa cấp WRITE_SECURE_SETTINGS để tự bật Gỡ lỗi không dây")
            if (!allowToggle) return fail("Gỡ lỗi không dây đang tắt. Bật \"Tự bật ADB khi có Wi-Fi\" hoặc nhấn Bắt đầu")
            setWirelessDebugging(ctx, true)
        }
        var cached = findTlsPort(ctx, 8000)
        if (cached <= 0) {
            return fail("Không tìm thấy cổng Gỡ lỗi không dây. Mở Gỡ lỗi không dây một lần và chọn \"Luôn cho phép trên mạng này\"")
        }
        return Backend("Gỡ lỗi không dây (TLS)", direct = cached == port, targetPort = {
            if (!NetUtil.isPortOpen(cached, 250)) {
                if (!isWirelessDebuggingOn(ctx) && hasSecureSettings(ctx)) setWirelessDebugging(ctx, true)
                cached = findTlsPort(ctx, 4000)
            }
            cached
        })
    }

    private fun prepareLegacy(adbdPort: Int, port: Int): Backend {
        if (!NetUtil.isPortOpen(adbdPort)) {
            return fail("adbd chưa mở TCP. Cắm USB và chạy \"adb tcpip $adbdPort\" một lần (mất sau khi khởi động lại), hoặc dùng Root / Không dây")
        }
        return Backend("adb tcpip :$adbdPort", direct = adbdPort == port, targetPort = { adbdPort })
    }

    private fun waitOpen(port: Int, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (NetUtil.isPortOpen(port, 300)) return true
            Thread.sleep(300)
        }
        return false
    }

    /** Wireless debugging port: system property first, then mDNS. */
    fun findTlsPort(ctx: Context, timeoutMs: Long): Int {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            propPort()?.let { if (NetUtil.isPortOpen(it)) return it }
            val p = nsdPort(ctx, 2500)
            if (p > 0 && NetUtil.isPortOpen(p)) return p
            Thread.sleep(500)
        }
        return -1
    }

    @SuppressLint("PrivateApi")
    private fun propPort(): Int? {
        val viaReflection = runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, "service.adb.tls.port") as String
        }.getOrNull()
        val v = viaReflection?.takeIf { it.isNotBlank() }
            ?: Shell.sh("getprop service.adb.tls.port").out.trim()
        return v.toIntOrNull()?.takeIf { it > 0 }
    }

    @Suppress("DEPRECATION")
    private fun nsdPort(ctx: Context, timeoutMs: Long): Int {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return -1
        val mine = NetUtil.lanAddresses().map { it.ip }.toSet()
        val latch = CountDownLatch(1)
        var found = -1
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                runCatching {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            if (si.host?.hostAddress in mine) {
                                found = si.port
                                latch.countDown()
                            }
                        }

                        override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}
                    })
                }
            }

            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = latch.countDown()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        return try {
            nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            found
        } catch (_: Exception) {
            -1
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }
}
