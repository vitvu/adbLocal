package com.vvtool.adblocal

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

enum class Phase { OFF, WAITING_NETWORK, WORKING, NEED_SETUP, PAIRING, SHARING, ERROR }

data class ClientInfo(val ip: String, val since: Long)

data class Status(
    val serviceOn: Boolean = false,
    val phase: Phase = Phase.OFF,
    val port: Int = 0,
    val addresses: List<NetUtil.Addr> = emptyList(),
    val message: String = "",
    /** PCs connected to adbd; null when this device does not let us see them. */
    val clients: List<ClientInfo>? = null,
)

/** Process-wide observable status shared between the service, UI and tile. */
object ShareState {
    @Volatile
    var status = Status()
        private set

    private val listeners = CopyOnWriteArraySet<(Status) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun update(block: (Status) -> Status) {
        val s = synchronized(this) { block(status).also { status = it } }
        main.post { listeners.forEach { it(s) } }
    }

    fun observe(l: (Status) -> Unit) {
        listeners += l
        l(status)
    }

    fun remove(l: (Status) -> Unit) {
        listeners -= l
    }
}
