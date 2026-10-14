package com.vvtool.adblocal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.net.BindException

class AdbShareService : Service() {

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val ACTION_RELOAD = "reload"
        private const val CHANNEL = "share"
        private const val NOTIF_ID = 1

        @Volatile
        var running = false
            private set

        fun start(ctx: Context) = send(ctx, ACTION_START)
        fun reload(ctx: Context) { if (running) send(ctx, ACTION_RELOAD) }
        fun stop(ctx: Context) {
            Prefs(ctx).enabled = false
            if (running) ctx.startService(Intent(ctx, AdbShareService::class.java).setAction(ACTION_STOP))
        }

        private fun send(ctx: Context, action: String) {
            Prefs(ctx).enabled = true
            ContextCompat.startForegroundService(ctx, Intent(ctx, AdbShareService::class.java).setAction(action))
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private var proxy: AdbProxy? = null
    private var backend: AdbController.Backend? = null
    private var userKick = false
    private var lastNotif = ""
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var healthTicks = 0

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = schedule(1500)
        override fun onLost(network: Network) = schedule(800)
        override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) = schedule(1500)
    }

    private val evaluate = Runnable { evaluateNow() }
    private val tick = object : Runnable {
        override fun run() {
            publishStats()
            // Periodic health check: adbd restarted / port changed / ip changed.
            if (++healthTicks % 15 == 0) checkHealth()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        prefs = Prefs(this)
        worker = HandlerThread("adb-share").apply { start() }
        handler = Handler(worker.looper)
        createChannel()
        goForeground(buildNotification("Đang khởi động…", null))
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                .registerNetworkCallback(NetworkRequest.Builder().build(), netCallback)
        }
        ShareState.update { it.copy(serviceOn = true, message = "Đang khởi động…", error = false) }
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.enabled = false
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_RELOAD -> {
                userKick = true
                handler.post { stopProxy(); backend = null }
                schedule(0)
            }
            else -> schedule(0) // restarted by the system (START_STICKY) or boot
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) }
        handler.removeCallbacksAndMessages(null)
        handler.post {
            stopProxy()
            releaseLocks()
            worker.quitSafely()
        }
        ShareState.update { Status(addresses = NetUtil.lanAddresses(), listenPort = prefs.port) }
        super.onDestroy()
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(evaluate)
        handler.postDelayed(evaluate, delayMs)
    }

    private fun evaluateNow() {
        val addrs = NetUtil.lanAddresses()
        val port = prefs.port
        if (addrs.isEmpty()) {
            stopProxy()
            backend = null
            releaseLocks()
            setState(false, "Đang chờ kết nối Wi-Fi / LAN…", addrs, error = false)
            return
        }

        ShareState.update { it.copy(addresses = addrs, listenPort = port, message = "Đang bật ADB…", error = false) }
        val allowToggle = userKick || prefs.autoOnWifi
        userKick = false
        val b = AdbController.prepare(this, prefs, allowToggle)
        if (b.error != null) {
            stopProxy()
            backend = null
            setState(false, b.error, addrs, error = true)
            schedule(10_000) // retry
            return
        }
        backend = b

        if (b.direct) {
            stopProxy()
        } else if (proxy?.port != port) {
            stopProxy()
            val p = AdbProxy(port, b.targetPort) { publishStats() }
            try {
                p.start()
                proxy = p
            } catch (e: BindException) {
                setState(false, "Cổng $port đang bị ứng dụng khác dùng. Hãy đổi cổng", addrs, error = true)
                return
            } catch (e: Exception) {
                setState(false, "Không mở được cổng $port: ${e.message}", addrs, error = true)
                schedule(10_000)
                return
            }
        }
        acquireLocks()
        ShareState.update {
            it.copy(backend = b.label, direct = b.direct, targetPort = runCatching { b.targetPort() }.getOrDefault(-1))
        }
        setState(true, "Sẵn sàng kết nối", addrs, error = false)
    }

    private fun checkHealth() {
        val b = backend ?: return
        val addrs = NetUtil.lanAddresses()
        val ipsChanged = addrs != ShareState.status.addresses
        val target = runCatching { b.targetPort() }.getOrDefault(-1)
        if (ipsChanged || target <= 0 || !NetUtil.isPortOpen(target)) schedule(0)
        else if (target != ShareState.status.targetPort) ShareState.update { it.copy(targetPort = target) }
    }

    private fun publishStats() {
        val p = proxy
        ShareState.update {
            it.copy(
                clients = p?.clients.orEmpty(),
                bytesFromPc = p?.bytesFromPc?.get() ?: 0,
                bytesToPc = p?.bytesToPc?.get() ?: 0,
            )
        }
        refreshNotification()
    }

    private fun setState(sharing: Boolean, msg: String, addrs: List<NetUtil.Addr>, error: Boolean) {
        ShareState.update {
            it.copy(
                serviceOn = true, sharing = sharing, message = msg, error = error,
                addresses = addrs, listenPort = prefs.port,
                backend = if (sharing) it.backend else "",
            )
        }
        refreshNotification()
    }

    private fun stopProxy() {
        proxy?.stop()
        proxy = null
    }

    // ---- locks: keep Wi-Fi radio in high-performance mode for smooth scrcpy / shell ----

    private fun acquireLocks() {
        if (!prefs.highPerformance) return releaseLocks()
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm?.createWifiLock(mode, "adblocal:wifi")?.apply { setReferenceCounted(false); acquire() }
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "adblocal:cpu")
                ?.apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        wifiLock = null
        wakeLock = null
    }

    // ---- notification ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(CHANNEL, "Chia sẻ ADB", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun refreshNotification() {
        val s = ShareState.status
        val addr = s.addresses.firstOrNull()?.ip
        val title = if (s.sharing && addr != null) "ADB Local · $addr:${s.listenPort}" else "ADB Local"
        val text = when {
            s.sharing && s.clients.isNotEmpty() -> "${s.clients.size} máy tính đang kết nối · ${s.backend}"
            s.sharing -> "Sẵn sàng · adb connect $addr:${s.listenPort}"
            else -> s.message
        }
        val key = "$title|$text"
        if (key == lastNotif) return
        lastNotif = key
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text, title))
    }

    private fun buildNotification(text: String, title: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AdbShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_adb)
            .setColor(ContextCompat.getColor(this, R.color.blue))
            .setContentTitle(title ?: "ADB Local")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Dừng", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun goForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }
}
