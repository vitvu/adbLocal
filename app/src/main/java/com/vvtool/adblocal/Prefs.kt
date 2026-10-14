package com.vvtool.adblocal

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("adblocal", Context.MODE_PRIVATE)

    /** Fixed LAN port adbd listens on (adb tcpip <port>). */
    var port: Int
        get() = sp.getInt("port", 5555)
        set(v) = sp.edit { putInt("port", v) }

    /** Re-open the port automatically when Wi-Fi connects / after reboot. */
    var autoOnWifi: Boolean
        get() = sp.getBoolean("auto_wifi", true)
        set(v) = sp.edit { putBoolean("auto_wifi", v) }

    var startOnBoot: Boolean
        get() = sp.getBoolean("boot", true)
        set(v) = sp.edit { putBoolean("boot", v) }

    /** Whether the user wants the service running (restored after reboot). */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit { putBoolean("enabled", v) }

    /** Paired with this device's wireless debugging at least once. */
    var paired: Boolean
        get() = sp.getBoolean("paired", false)
        set(v) = sp.edit { putBoolean("paired", v) }
}
