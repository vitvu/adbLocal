package com.vvtool.adblocal

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class AdbTileService : TileService() {
    private val listener: (Status) -> Unit = { render(it) }

    override fun onStartListening() {
        ShareState.observe(listener)
    }

    override fun onStopListening() {
        ShareState.remove(listener)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (AdbShareService.running) {
            AdbShareService.stop(this)
            return
        }
        try {
            AdbShareService.start(this)
        } catch (_: Exception) {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
        }
    }

    private fun render(s: Status) {
        val tile = qsTile ?: return
        tile.state = if (s.serviceOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                s.phase == Phase.SHARING -> ":${s.port}"
                s.serviceOn -> "Đang chờ"
                else -> "Tắt"
            }
        }
        tile.updateTile()
    }
}
