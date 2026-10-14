package com.vvtool.adblocal

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var tvStatus: TextView
    private lateinit var tvAddress: TextView
    private lateinit var tvMessage: TextView
    private lateinit var dot: View
    private lateinit var btnToggle: MaterialButton
    private lateinit var etPort: TextInputEditText
    private lateinit var tvModeHint: TextView
    private lateinit var tvHelp: TextView
    private lateinit var tvClientsTitle: TextView
    private lateinit var clientList: LinearLayout

    private val info = LinkedHashMap<String, TextView>()
    private val perms = ArrayList<PermRow>()
    private val statusListener: (Status) -> Unit = { render(it) }

    private class PermRow(
        val status: TextView,
        val button: MaterialButton,
        val check: () -> Boolean?,
        val action: () -> Unit,
        val labels: (Boolean?) -> String,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        tvStatus = findViewById(R.id.tvStatus)
        tvAddress = findViewById(R.id.tvAddress)
        tvMessage = findViewById(R.id.tvMessage)
        dot = findViewById(R.id.dot)
        btnToggle = findViewById(R.id.btnToggle)
        etPort = findViewById(R.id.etPort)
        tvModeHint = findViewById(R.id.tvModeHint)
        tvHelp = findViewById(R.id.tvHelp)
        tvClientsTitle = findViewById(R.id.tvClientsTitle)
        clientList = findViewById(R.id.clientList)
        findViewById<TextView>(R.id.tvVersion).text = "v" + runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        findViewById<View>(R.id.tvRepo).setOnClickListener {
            openUri("https://github.com/vitvu/adblocal")
        }

        btnToggle.setOnClickListener { toggle() }
        findViewById<View>(R.id.btnCopy).setOnClickListener { copy(connectCommand(), "Đã sao chép lệnh kết nối") }

        setupInfo()
        setupSettings()
        setupPermissions()
    }

    override fun onStart() {
        super.onStart()
        ShareState.observe(statusListener)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    override fun onStop() {
        ShareState.remove(statusListener)
        super.onStop()
    }

    // ---------------- actions ----------------

    private fun toggle() {
        if (AdbShareService.running) {
            AdbShareService.stop(this)
            return
        }
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= 37 && !granted(PERM_LOCAL_NETWORK)) add(PERM_LOCAL_NETWORK)
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_START)
        else AdbShareService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_START) AdbShareService.start(this)
        refreshPermissions()
    }

    private fun savePort() {
        val p = etPort.text?.toString()?.toIntOrNull()
        if (p == null || p !in 1024..65535) {
            etPort.error = "Cổng phải trong khoảng 1024 – 65535"
            return
        }
        if (p != prefs.port) {
            prefs.port = p
            AdbShareService.reload(this)
            toast("Đã lưu cổng $p")
        }
        etPort.clearFocus()
        render(ShareState.status)
    }

    private fun connectCommand(): String {
        val ip = ShareState.status.addresses.firstOrNull()?.ip ?: NetUtil.lanAddresses().firstOrNull()?.ip ?: "<ip>"
        return "adb connect $ip:${prefs.port}"
    }

    // ---------------- status ----------------

    private fun setupInfo() {
        val list = findViewById<LinearLayout>(R.id.infoList)
        listOf("Chế độ", "Cổng chia sẻ", "Cổng adbd", "Mạng / IP", "Lưu lượng", "Gỡ lỗi USB", "Gỡ lỗi không dây", "Root")
            .forEachIndexed { i, label ->
                if (i > 0) list.addView(divider())
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(10), 0, dp(10))
                }
                row.addView(text(label, 14f, R.color.text_muted), LinearLayout.LayoutParams(0, wrap, 1f))
                val value = text("—", 14f, R.color.text, bold = true).apply { gravity = Gravity.END }
                row.addView(value, LinearLayout.LayoutParams(0, wrap, 1.4f))
                list.addView(row)
                info[label] = value
            }
    }

    private fun render(s: Status) {
        val addrs = s.addresses.ifEmpty { NetUtil.lanAddresses() }
        val ip = addrs.firstOrNull()?.ip
        val port = if (s.serviceOn && s.listenPort > 0) s.listenPort else prefs.port

        val (label, dotColor) = when {
            s.sharing && s.clients.isNotEmpty() -> "Đang điều khiển · ${s.clients.size} PC" to 0xFF7CFFB2.toInt()
            s.sharing -> "Đang chia sẻ" to 0xFF7CFFB2.toInt()
            s.serviceOn && s.error -> "Có lỗi" to 0xFFFFB4AB.toInt()
            s.serviceOn -> "Đang chuẩn bị" to 0xFFFFE08A.toInt()
            else -> "Đã tắt" to 0x99FFFFFF.toInt()
        }
        tvStatus.text = label
        dot.background.setTint(dotColor)
        tvAddress.text = if (ip != null) "$ip:$port" else "Không có mạng LAN"
        tvMessage.text = when {
            s.serviceOn -> if (s.sharing) "Trên PC: adb connect $ip:$port" else s.message
            else -> "Nhấn Bắt đầu để chia sẻ ADB trên cổng $port"
        }
        btnToggle.text = if (s.serviceOn) "Dừng chia sẻ" else "Bắt đầu chia sẻ"

        val modeName = mapOf(
            AdbController.MODE_AUTO to "Tự động", AdbController.MODE_ROOT to "Root",
            AdbController.MODE_WIRELESS to "Không dây", AdbController.MODE_LEGACY to "adb tcpip",
        )
        info["Chế độ"]?.text = s.backend.ifEmpty { modeName[prefs.mode] + if (s.sharing) "" else " (chưa chạy)" }
        info["Cổng chia sẻ"]?.text = port.toString()
        info["Cổng adbd"]?.text = when {
            !s.sharing -> "—"
            s.direct -> "${s.targetPort} (trực tiếp)"
            else -> "${s.targetPort} → proxy :$port"
        }
        info["Mạng / IP"]?.text = addrs.joinToString("\n") { "${it.kind} · ${it.ip}" }.ifEmpty { "Không có" }
        info["Lưu lượng"]?.text = "↓ ${bytes(s.bytesFromPc)}   ↑ ${bytes(s.bytesToPc)}"

        renderClients(s)
        renderHelp(ip, port)
        refreshPermissions()
    }

    private fun renderClients(s: Status) {
        clientList.removeAllViews()
        tvClientsTitle.text = "Máy tính đang kết nối (${s.clients.size})"
        if (s.clients.isEmpty()) {
            val msg = when {
                s.sharing && s.direct -> "adbd đang nhận kết nối trực tiếp (Root / tcpip), không qua proxy nên không liệt kê được PC"
                s.sharing -> "Chưa có PC nào kết nối"
                else -> "Dịch vụ chưa chạy"
            }
            clientList.addView(text(msg, 14f, R.color.text_muted))
            return
        }
        s.clients.forEachIndexed { i, c ->
            if (i > 0) clientList.addView(divider())
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8)) }
            row.addView(text("💻  ${c.address}", 15f, R.color.text, bold = true), LinearLayout.LayoutParams(0, wrap, 1f))
            row.addView(text(DateUtils.getRelativeTimeSpanString(c.since).toString(), 13f, R.color.text_muted))
            clientList.addView(row)
        }
    }

    private fun renderHelp(ip: String?, port: Int) {
        val addr = "${ip ?: "<ip>"}:$port"
        tvHelp.text = buildString {
            appendLine("1. PC và điện thoại cùng mạng LAN (Wi-Fi, Ethernet, hotspot, USB tether).")
            appendLine("2. Trên PC chạy:\n    adb connect $addr")
            appendLine("3. Lần đầu: chấp nhận khóa RSA trên điện thoại (\"Luôn cho phép\").")
            appendLine("   Chế độ Không dây: ghép đôi 1 lần bằng \"adb pair\" trong Cài đặt › Gỡ lỗi không dây.")
            appendLine("4. Truyền màn hình mượt (scrcpy):\n    scrcpy -s $addr --video-bit-rate 8M --max-fps 60")
            append("5. Kiểm tra: adb -s $addr shell getprop ro.product.model")
        }
    }

    // ---------------- settings ----------------

    private fun setupSettings() {
        etPort.setText(prefs.port.toString())
        etPort.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) savePort()
            false
        }
        findViewById<View>(R.id.btnSavePort).setOnClickListener { savePort() }

        val group = findViewById<MaterialButtonToggleGroup>(R.id.modeGroup)
        val ids = mapOf(
            AdbController.MODE_AUTO to R.id.modeAuto, AdbController.MODE_ROOT to R.id.modeRoot,
            AdbController.MODE_WIRELESS to R.id.modeWireless, AdbController.MODE_LEGACY to R.id.modeLegacy,
        )
        group.check(ids[prefs.mode] ?: R.id.modeAuto)
        updateModeHint()
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val mode = ids.entries.first { it.value == id }.key
            if (mode != prefs.mode) {
                prefs.mode = mode
                updateModeHint()
                AdbShareService.reload(this)
            }
        }

        val list = findViewById<LinearLayout>(R.id.switchList)
        addSwitch(list, "Tự bật ADB khi kết nối Wi-Fi",
            "Tự bật Gỡ lỗi USB / không dây mỗi khi có Wi-Fi (cần WRITE_SECURE_SETTINGS hoặc root)",
            prefs.autoOnWifi) { prefs.autoOnWifi = it; AdbShareService.reload(this) }
        addSwitch(list, "Khởi động cùng máy",
            "Tự chạy lại dịch vụ sau khi khởi động lại điện thoại", prefs.startOnBoot) { prefs.startOnBoot = it }
        addSwitch(list, "Hiệu năng cao",
            "Giữ Wi-Fi độ trễ thấp và CPU luôn thức khi chia sẻ, giúp scrcpy mượt và không rớt kết nối",
            prefs.highPerformance) { prefs.highPerformance = it; AdbShareService.reload(this) }
    }

    private fun updateModeHint() {
        tvModeHint.text = when (prefs.mode) {
            AdbController.MODE_ROOT -> "Đặt adbd lắng nghe TCP trực tiếp bằng root. Ổn định nhất, chạy trên mọi mạng (Wi-Fi, Ethernet, USB, hotspot)."
            AdbController.MODE_WIRELESS -> "Android 11+: tự bật Gỡ lỗi không dây, tìm cổng ngẫu nhiên và chuyển tiếp sang cổng cố định. Không cần root."
            AdbController.MODE_LEGACY -> "Dùng adbd sau lệnh \"adb tcpip ${prefs.legacyPort}\" (chạy 1 lần qua USB, mất khi khởi động lại) và chuyển tiếp sang cổng cố định."
            else -> "Ưu tiên Root → Gỡ lỗi không dây → adb tcpip, tùy thiết bị hỗ trợ."
        }
    }

    private fun addSwitch(parent: LinearLayout, title: String, desc: String, value: Boolean, onChange: (Boolean) -> Unit) {
        parent.addView(divider())
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 15f, R.color.text, bold = true))
        texts.addView(text(desc, 13f, R.color.text_muted))
        row.addView(texts, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginEnd = dp(12) })
        val sw = MaterialSwitch(this).apply { isChecked = value }
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        parent.addView(row)
    }

    // ---------------- permissions ----------------

    private fun setupPermissions() {
        val list = findViewById<LinearLayout>(R.id.permList)
        val grantedLabels: (Boolean?) -> String = { if (it == true) "Đã cấp" else if (it == false) "Chưa cấp" else "Chưa kiểm tra" }
        val onOff: (Boolean?) -> String = { if (it == true) "Đang bật" else "Đang tắt" }

        addPerm(list, "Quyền root", "Bật adbd TCP trực tiếp, ổn định nhất, không cần Wi-Fi",
            { Shell.rootGranted }, grantedLabels, { if (Shell.rootGranted == true) "Kiểm tra lại" else "Yêu cầu" }) {
            thread {
                val ok = Shell.hasRoot(force = true)
                if (ok && !AdbController.hasSecureSettings(this)) AdbController.grantSecureWithRoot(this)
                runOnUiThread {
                    toast(if (ok) "Đã có quyền root" else "Không có root hoặc bị từ chối")
                    refreshPermissions()
                }
            }
        }

        addPerm(list, "WRITE_SECURE_SETTINGS", "Cho phép app tự bật/tắt Gỡ lỗi USB và không dây. Cấp 1 lần từ PC",
            { AdbController.hasSecureSettings(this) }, grantedLabels,
            { if (Shell.rootGranted == true) "Cấp bằng root" else "Sao chép lệnh" }) {
            if (Shell.rootGranted == true) {
                thread {
                    val ok = AdbController.grantSecureWithRoot(this)
                    runOnUiThread { toast(if (ok) "Đã cấp" else "Cấp thất bại"); refreshPermissions() }
                }
            } else {
                copy("adb shell pm grant $packageName ${Manifest.permission.WRITE_SECURE_SETTINGS}",
                    "Đã sao chép. Chạy lệnh này trên PC khi đang cắm USB")
            }
        }

        addPerm(list, "Gỡ lỗi USB (ADB)", "Bắt buộc bật để adbd hoạt động",
            { AdbController.isUsbDebuggingOn(this) }, onOff, { if (it == true) "Tắt" else "Bật" }) {
            toggleSetting(AdbController.isUsbDebuggingOn(this)) { AdbController.setUsbDebugging(this, it) }
        }

        if (Build.VERSION.SDK_INT >= 30) {
            addPerm(list, "Gỡ lỗi không dây", "Android 11+: dùng cho chế độ Không dây (không cần root)",
                { AdbController.isWirelessDebuggingOn(this) }, onOff, { if (it == true) "Tắt" else "Bật" }) {
                toggleSetting(AdbController.isWirelessDebuggingOn(this)) { AdbController.setWirelessDebugging(this, it) }
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            addPerm(list, "Thông báo", "Hiển thị trạng thái và nút Dừng nhanh",
                { granted(Manifest.permission.POST_NOTIFICATIONS) }, grantedLabels, { if (it == true) "Cài đặt" else "Cấp" }) {
                if (granted(Manifest.permission.POST_NOTIFICATIONS)) openAppSettings()
                else requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERM)
            }
        }

        if (Build.VERSION.SDK_INT >= 37) {
            addPerm(list, "Mạng cục bộ", "Android 17+: cho phép PC trong mạng LAN kết nối tới app",
                { granted(PERM_LOCAL_NETWORK) }, grantedLabels, { if (it == true) "Cài đặt" else "Cấp" }) {
                if (granted(PERM_LOCAL_NETWORK)) openAppSettings()
                else requestPermissions(arrayOf(PERM_LOCAL_NETWORK), REQ_PERM)
            }
        }

        addPerm(list, "Bỏ tối ưu hóa pin", "Giữ dịch vụ chạy nền ổn định, không bị hệ thống tắt",
            { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) },
            grantedLabels, { if (it == true) "Cài đặt" else "Cấp" }) { requestBatteryExemption() }

        addPerm(list, "Tùy chọn nhà phát triển", "Mở cài đặt hệ thống để chỉnh ADB thủ công",
            { null }, { "" }, { "Mở" }) {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun toggleSetting(current: Boolean, set: (Boolean) -> Boolean) {
        if (AdbController.hasSecureSettings(this) && set(!current)) {
            refreshPermissions()
            AdbShareService.reload(this)
        } else {
            toast("Cần WRITE_SECURE_SETTINGS. Đang mở Tùy chọn nhà phát triển")
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        val intent = if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        }
        runCatching { startActivity(intent) }.onFailure { openAppSettings() }
    }

    private fun addPerm(
        parent: LinearLayout, title: String, desc: String,
        check: () -> Boolean?, labels: (Boolean?) -> String, buttonLabel: (Boolean?) -> String,
        action: () -> Unit,
    ) {
        if (parent.childCount > 0) parent.addView(divider())
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(title, 15f, R.color.text, bold = true))
        val status = text("", 11f, R.color.text, bold = true).apply {
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        head.addView(status, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(8) })
        texts.addView(head)
        texts.addView(text(desc, 13f, R.color.text_muted))
        row.addView(texts, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginEnd = dp(10) })
        val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { action() }
        }
        row.addView(btn)
        parent.addView(row)
        perms += PermRow(status, btn, check, action) { v -> labels(v) + "\u0000" + buttonLabel(v) }
    }

    private fun refreshPermissions() {
        for (p in perms) {
            val v = p.check()
            val (statusText, buttonText) = p.labels(v).split("\u0000")
            p.status.text = statusText
            p.status.visibility = if (statusText.isEmpty()) View.GONE else View.VISIBLE
            val (fg, bg) = when (v) {
                true -> R.color.green to R.color.green_soft
                false -> R.color.red to R.color.red_soft
                null -> R.color.amber to R.color.amber_soft
            }
            p.status.setTextColor(ContextCompat.getColor(this, fg))
            p.status.background.setTint(ContextCompat.getColor(this, bg))
            p.button.text = buttonText
        }
        val root = Shell.rootGranted
        info["Root"]?.text = when (root) { true -> "Có"; false -> "Không"; null -> "Chưa kiểm tra" }
        info["Gỡ lỗi USB"]?.text = if (AdbController.isUsbDebuggingOn(this)) "Bật" else "Tắt"
        info["Gỡ lỗi không dây"]?.text = when {
            Build.VERSION.SDK_INT < 30 -> "Không hỗ trợ"
            AdbController.isWirelessDebuggingOn(this) -> "Bật"
            else -> "Tắt"
        }
    }

    // ---------------- helpers ----------------

    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(ContextCompat.getColor(this@MainActivity, color))
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.outline))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun copy(s: String, msg: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", s))
        toast(msg)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun openUri(u: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }

    private fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
        else -> "$b B"
    }

    private companion object {
        const val REQ_START = 10
        const val REQ_PERM = 11
        const val PERM_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
