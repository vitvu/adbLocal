package com.vvtool.adblocal

import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object Shell {
    class Result(val code: Int, val out: String) {
        val ok get() = code == 0
    }

    /** null = not checked yet. */
    @Volatile
    var rootGranted: Boolean? = null
        private set

    fun hasRoot(force: Boolean = false): Boolean {
        rootGranted?.let { if (!force) return it }
        val r = run(arrayOf("su", "-c", "id"), 15)
        return (r.ok && r.out.contains("uid=0")).also { rootGranted = it }
    }

    fun su(cmd: String, timeoutSec: Long = 15): Result = run(arrayOf("su", "-c", cmd), timeoutSec)

    fun sh(cmd: String, timeoutSec: Long = 5): Result = run(arrayOf("sh", "-c", cmd), timeoutSec)

    private fun run(cmd: Array<String>, timeoutSec: Long): Result = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val sb = StringBuilder()
        val reader = thread(isDaemon = true) {
            runCatching { p.inputStream.bufferedReader().forEachLine { sb.appendLine(it) } }
        }
        reader.join(TimeUnit.SECONDS.toMillis(timeoutSec))
        if (reader.isAlive) {
            p.destroy()
            Result(-1, "timeout")
        } else {
            Result(p.waitFor(), sb.toString().trim())
        }
    } catch (e: Exception) {
        Result(-1, e.message ?: "error")
    }
}
