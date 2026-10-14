package com.vvtool.adblocal

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("adblocal", Context.MODE_PRIVATE)

    /** Fixed LAN port the PC connects to. */
    var port: Int
        get() = sp.getInt("port", DEFAULT_PORT)
        set(v) = sp.edit { putInt("port", v) }

    /** Port adbd listens on after `adb tcpip` (manual mode). */
    var legacyPort: Int
        get() = sp.getInt("legacy_port", 5555)
        set(v) = sp.edit { putInt("legacy_port", v) }

    var mode: String
        get() = sp.getString("mode", AdbController.MODE_AUTO) ?: AdbController.MODE_AUTO
        set(v) = sp.edit { putString("mode", v) }

    /** Turn ADB / wireless debugging on automatically whenever Wi-Fi connects. */
    var autoOnWifi: Boolean
        get() = sp.getBoolean("auto_wifi", true)
        set(v) = sp.edit { putBoolean("auto_wifi", v) }

    var startOnBoot: Boolean
        get() = sp.getBoolean("boot", true)
        set(v) = sp.edit { putBoolean("boot", v) }

    /** Hold a Wi-Fi low-latency lock + CPU wake lock while sharing. */
    var highPerformance: Boolean
        get() = sp.getBoolean("perf", true)
        set(v) = sp.edit { putBoolean("perf", v) }

    /** Whether the user wants the service running (restored after reboot). */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit { putBoolean("enabled", v) }

    companion object {
        const val DEFAULT_PORT = 5555
    }
}
