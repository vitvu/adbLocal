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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class AdbShareService : Service() {

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val ACTION_PAIR = "pair"
        private const val ACTION_PAIR_CODE = "pair_code"
        private const val KEY_CODE = "code"
        private const val CHANNEL = "share"
        private const val CHANNEL_PAIR = "pair"
        private const val NOTIF_ID = 1
        private const val NOTIF_PAIR = 2

        @Volatile
        var running = false
            private set

        fun start(ctx: Context) = send(ctx, ACTION_START)

        /** Starts pairing: user opens "Pair device with pairing code" and types the code into the notification. */
        fun startPairing(ctx: Context) = send(ctx, ACTION_PAIR)

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
    private lateinit var nm: NotificationManager
    private var lastNotif = ""
    @Volatile
    private var pairing = false
    private var pairPort = -1

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = schedule(2000)
        override fun onLost(network: Network) = schedule(1000)
    }

    private val check = Runnable { checkNow() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        prefs = Prefs(this)
        nm = getSystemService(NotificationManager::class.java)
        worker = HandlerThread("adb-share").apply { start() }
        handler = Handler(worker.looper)
        createChannels()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("ADB Local", "Đang khởi động…"), type)
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                .registerNetworkCallback(NetworkRequest.Builder().build(), netCallback)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.enabled = false
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAIR -> handler.post { beginPairing() }
            ACTION_PAIR_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()?.trim()
                handler.post { finishPairing(code.orEmpty()) }
            }
            else -> schedule(0)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) }
        nm.cancel(NOTIF_PAIR)
        worker.quitSafely()
        ShareState.update { Status(port = prefs.port, addresses = NetUtil.lanAddresses()) }
        super.onDestroy()
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(check)
        handler.postDelayed(check, delayMs)
    }

    // ---- keep the port open ----

    private fun checkNow() {
        if (pairing) return
        val port = prefs.port
        val addrs = NetUtil.lanAddresses()

        if (AdbController.isListening(port)) {
            set(Phase.SHARING, "Sẵn sàng · adb connect ${addrs.firstOrNull()?.ip ?: "<ip>"}:$port", addrs)
            schedule(15_000)
            return
        }
        if (addrs.isEmpty()) {
            set(Phase.WAITING_NETWORK, "Đang chờ kết nối Wi-Fi…", addrs)
            return
        }

        set(Phase.WORKING, "Đang mở cổng $port…", addrs)
        when (val r = AdbController.openPort(this, port)) {
            AdbController.Result.Ok -> {
                set(Phase.SHARING, "Sẵn sàng · adb connect ${addrs.first().ip}:$port", addrs)
                schedule(15_000)
            }
            AdbController.Result.NoWifi -> set(Phase.WAITING_NETWORK, "Đang chờ kết nối Wi-Fi…", addrs)
            AdbController.Result.NeedWireless ->
                set(Phase.NEED_WIRELESS, "Hãy bật Gỡ lỗi không dây trong Tùy chọn nhà phát triển", addrs, retry = 5_000)
            AdbController.Result.NeedPair ->
                set(Phase.NEED_PAIR, "Cần ghép đôi 1 lần. Nhấn \"Ghép đôi\" trong app", addrs)
            AdbController.Result.Unsupported ->
                set(Phase.ERROR, "Android ${Build.VERSION.RELEASE} không có Gỡ lỗi không dây (cần Android 11+)", addrs)
            is AdbController.Result.Error -> set(Phase.ERROR, r.message, addrs, retry = 10_000)
        }
    }

    private fun set(phase: Phase, msg: String, addrs: List<NetUtil.Addr>, retry: Long = 0) {
        ShareState.update { Status(serviceOn = true, phase = phase, port = prefs.port, addresses = addrs, message = msg) }
        val title = if (phase == Phase.SHARING) "ADB Local · ${addrs.firstOrNull()?.ip}:${prefs.port}" else "ADB Local"
        val key = "$title|$msg"
        if (key != lastNotif) {
            lastNotif = key
            nm.notify(NOTIF_ID, buildNotification(title, msg))
        }
        if (retry > 0) schedule(retry)
    }

    // ---- pairing via notification (the Settings pairing dialog must stay on screen) ----

    private fun beginPairing() {
        if (!AdbController.supported) return checkNow()
        pairing = true
        handler.removeCallbacks(check)
        set(Phase.PAIRING, "Mở \"Ghép nối thiết bị bằng mã ghép nối\" trong Gỡ lỗi không dây", NetUtil.lanAddresses())
        showPairNotification("Đang tìm mã ghép đôi…", "Trong Gỡ lỗi không dây, nhấn \"Ghép nối thiết bị bằng mã ghép nối\"", false)
        pairPort = AdbController.findPairingPort(this, 120_000)
        if (pairPort <= 0) {
            nm.cancel(NOTIF_PAIR)
            pairing = false
            set(Phase.NEED_PAIR, "Hết thời gian chờ ghép đôi. Thử lại", NetUtil.lanAddresses())
            return
        }
        showPairNotification("Đã thấy yêu cầu ghép đôi", "Nhấn \"Nhập mã\" và gõ 6 số đang hiện trên màn hình", true)
    }

    private fun finishPairing(code: String) {
        if (pairPort <= 0 || code.length < 6) {
            showPairNotification("Mã không hợp lệ", "Nhập đúng 6 số đang hiện trên màn hình", true)
            return
        }
        showPairNotification("Đang ghép đôi…", "Mã $code", false)
        val ok = runCatching { AdbController.pair(this, pairPort, code) }.getOrDefault(false)
        if (!ok) {
            showPairNotification("Ghép đôi thất bại", "Sai mã hoặc hộp thoại đã đóng. Mở lại và nhập mã mới", true)
            beginPairing()
            return
        }
        pairing = false
        pairPort = -1
        nm.cancel(NOTIF_PAIR)
        nm.notify(NOTIF_PAIR, NotificationCompat.Builder(this, CHANNEL_PAIR)
            .setSmallIcon(R.drawable.ic_adb)
            .setContentTitle("Ghép đôi thành công")
            .setContentText("Đang mở cổng ADB ${prefs.port}…")
            .setTimeoutAfter(5000)
            .setAutoCancel(true)
            .build())
        checkNow()
    }

    private fun showPairNotification(title: String, text: String, withInput: Boolean) {
        val b = NotificationCompat.Builder(this, CHANNEL_PAIR)
            .setSmallIcon(R.drawable.ic_adb)
            .setColor(ContextCompat.getColor(this, R.color.blue))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(false)
            .setOngoing(true)
        if (withInput) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getService(
                this, 3, Intent(this, AdbShareService::class.java).setAction(ACTION_PAIR_CODE), flags,
            )
            val input = RemoteInput.Builder(KEY_CODE).setLabel("Mã ghép đôi 6 số").build()
            b.addAction(NotificationCompat.Action.Builder(R.drawable.ic_adb, "Nhập mã", pi).addRemoteInput(input).build())
        }
        nm.notify(NOTIF_PAIR, b.build())
    }

    // ---- notification ----

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Chia sẻ ADB", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PAIR, "Ghép đôi", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun buildNotification(title: String, text: String): Notification {
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
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Dừng", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
