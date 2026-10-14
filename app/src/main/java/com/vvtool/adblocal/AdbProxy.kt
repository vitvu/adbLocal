package com.vvtool.adblocal

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Low-latency TCP relay: 0.0.0.0:[port] -> 127.0.0.1:<adbd port>.
 * ADB auth/TLS stays end-to-end between the PC and adbd; this only moves bytes.
 */
class AdbProxy(
    val port: Int,
    private val target: () -> Int,
    private val onClientsChanged: () -> Unit,
) {
    private class Conn(val info: ClientInfo, val pc: Socket, val adbd: Socket)

    private val conns = ConcurrentHashMap<Int, Conn>()
    private val ids = AtomicInteger()
    val bytesFromPc = AtomicLong()
    val bytesToPc = AtomicLong()

    @Volatile
    private var server: ServerSocket? = null

    val clients: List<ClientInfo> get() = conns.values.map { it.info }.sortedBy { it.since }

    @Throws(IOException::class)
    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.receiveBufferSize = BUF_SOCKET
        s.bind(InetSocketAddress(port), 64)
        server = s
        thread(name = "adb-accept", isDaemon = true) {
            while (!s.isClosed) {
                val c = try {
                    s.accept()
                } catch (_: IOException) {
                    break
                }
                thread(name = "adb-conn", isDaemon = true) { handle(c) }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        conns.values.forEach { close(it.pc); close(it.adbd) }
        conns.clear()
    }

    private fun handle(pc: Socket) {
        val adbd = Socket()
        try {
            tune(pc)
            tune(adbd)
            val p = target()
            if (p <= 0) throw IOException("adbd not available")
            adbd.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), p), 3000)
        } catch (_: IOException) {
            close(pc); close(adbd)
            return
        }
        val id = ids.incrementAndGet()
        conns[id] = Conn(ClientInfo(id, pc.inetAddress?.hostAddress ?: "?", System.currentTimeMillis()), pc, adbd)
        onClientsChanged()
        val up = thread(name = "adb-up-$id", isDaemon = true) { pump(pc, adbd, bytesFromPc) }
        pump(adbd, pc, bytesToPc)
        up.join()
        conns.remove(id)
        onClientsChanged()
    }

    private fun pump(from: Socket, to: Socket, counter: AtomicLong) {
        val buf = ByteArray(BUF_COPY)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                counter.addAndGet(n.toLong())
            }
        } catch (_: IOException) {
        } finally {
            // Closing both ends unblocks the opposite pump.
            close(from); close(to)
        }
    }

    private fun tune(s: Socket) {
        s.tcpNoDelay = true
        s.keepAlive = true
        s.sendBufferSize = BUF_SOCKET
        s.receiveBufferSize = BUF_SOCKET
        runCatching { s.trafficClass = 0x10 } // IPTOS_LOWDELAY
    }

    private fun close(s: Socket) = runCatching { s.close() }

    private companion object {
        const val BUF_COPY = 64 * 1024
        const val BUF_SOCKET = 512 * 1024
    }
}
