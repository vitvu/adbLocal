package com.vvtool.adblocal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        val boot = intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        if (prefs.enabled && (!boot || prefs.startOnBoot)) {
            runCatching { AdbShareService.start(context) }
        }
    }
}
