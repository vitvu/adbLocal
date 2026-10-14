package com.vvtool.adblocal

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Opens adbd on a fixed TCP port without root and without a PC:
 *  1. Wireless debugging (Android 11+) is on.
 *  2. The app pairs with it once (pairing code), like a PC would.
 *  3. Over that connection it runs `tcpip:<port>` → adbd restarts listening on <port>.
 * A PC then runs `adb connect ip:port` and the phone shows the normal "Allow USB debugging?" prompt.
 */
object AdbController {
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    sealed class Result {
        object Ok : Result()
        object NeedWireless : Result()
        object NeedPair : Result()
        object NoWifi : Result()
        object Unsupported : Result()
        class Error(val message: String) : Result()
    }

    val supported get() = Build.VERSION.SDK_INT >= 30

    init {
        // libadb reads Conscrypt's exportKeyingMaterial through reflection.
        if (Build.VERSION.SDK_INT >= 28) runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
    }

    fun hasSecureSettings(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isUsbDebuggingOn(ctx: Context) =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    fun isWirelessDebuggingOn(ctx: Context) =
        Settings.Global.getInt(ctx.contentResolver, ADB_WIFI_ENABLED, 0) == 1

    private fun putGlobal(ctx: Context, key: String, value: Int) = try {
        Settings.Global.putInt(ctx.contentResolver, key, value)
    } catch (_: SecurityException) {
        false
    }

    fun isListening(port: Int) = NetUtil.isPortOpen(port)

    /** Make adbd listen on [port]. Runs on a worker thread. */
    fun openPort(ctx: Context, port: Int): Result {
        if (isListening(port)) return Result.Ok
        if (!supported) return Result.Unsupported
        if (!NetUtil.isWifiConnected(ctx)) return Result.NoWifi

        if (!isWirelessDebuggingOn(ctx)) {
            if (!hasSecureSettings(ctx)) return Result.NeedWireless
            putGlobal(ctx, Settings.Global.ADB_ENABLED, 1)
            putGlobal(ctx, ADB_WIFI_ENABLED, 1)
        }

        val client = AdbClient.create(ctx)
        try {
            if (!client.connectTls(ctx, 12_000)) return Result.Error("Could not connect to Wireless debugging")
        } catch (_: AdbPairingRequiredException) {
            Prefs(ctx).paired = false
            return Result.NeedPair
        } catch (_: InterruptedException) {
            return Result.Error("Wireless debugging not found. Turn it on and choose \"Always allow on this network\"")
        } catch (e: Exception) {
            return Result.Error("ADB connection error: ${e.message}")
        }

        try {
            Prefs(ctx).paired = true
            // Let the app turn Wireless debugging on by itself next time (after reboot / Wi-Fi change).
            if (!hasSecureSettings(ctx)) {
                run(client, "shell:pm grant ${ctx.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}")
            }
            run(client, "tcpip:$port")
        } catch (e: Exception) {
            return Result.Error("Failed to open port: ${e.message}")
        } finally {
            runCatching { client.close() }
        }

        val end = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < end) {
            if (isListening(port)) return Result.Ok
            Thread.sleep(300)
        }
        return Result.Error("adbd did not open port $port")
    }

    private fun run(client: AdbClient, destination: String): String {
        val stream = client.openStream(destination)
        return stream.openInputStream().use { it.readBytes().decodeToString() }.also { runCatching { stream.close() } }
    }

    /** Finds the pairing port while the "Pair device with pairing code" dialog is open. */
    fun findPairingPort(ctx: Context, timeoutMs: Long): Int {
        val port = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(ctx, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, p ->
            if (p > 0) {
                port.set(p)
                latch.countDown()
            }
        }
        mdns.start()
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            mdns.stop()
        }
        return port.get()
    }

    fun pair(ctx: Context, port: Int, code: String): Boolean {
        val ok = AdbClient.create(ctx).use { it.pair(port, code) }
        if (ok) Prefs(ctx).paired = true
        return ok
    }

    /** Developer options, scrolled to the Wireless debugging entry where supported. */
    fun wirelessSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            putExtra(":settings:show_fragment_args", Bundle().apply {
                putString(":settings:fragment_args_key", "toggle_adb_wireless")
            })
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
