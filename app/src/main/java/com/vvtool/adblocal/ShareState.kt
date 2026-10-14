package com.vvtool.adblocal

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

data class ClientInfo(val id: Int, val address: String, val since: Long)

data class Status(
    val serviceOn: Boolean = false,
    val sharing: Boolean = false,
    val backend: String = "",
    val listenPort: Int = 0,
    val targetPort: Int = 0,
    val direct: Boolean = false,
    val addresses: List<NetUtil.Addr> = emptyList(),
    val clients: List<ClientInfo> = emptyList(),
    val bytesFromPc: Long = 0,
    val bytesToPc: Long = 0,
    val message: String = "",
    val error: Boolean = false,
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
