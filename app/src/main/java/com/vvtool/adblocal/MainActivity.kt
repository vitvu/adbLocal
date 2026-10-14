package com.vvtool.adblocal

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var tvStatus: TextView
    private lateinit var tvAddress: TextView
    private lateinit var tvMessage: TextView
    private lateinit var dot: View
    private lateinit var btnToggle: MaterialButton
    private lateinit var etPort: TextInputEditText
    private lateinit var tvHelp: TextView

    private val steps = ArrayList<Step>()
    private val statusListener: (Status) -> Unit = { render(it) }

    /** One setup step: [state] returns true = done, false = to do. */
    private class Step(val status: TextView, val button: MaterialButton, val state: () -> Boolean, val buttonLabel: (Boolean) -> String)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
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
        tvHelp = findViewById(R.id.tvHelp)
        findViewById<TextView>(R.id.tvVersion).text = "v" + runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        findViewById<View>(R.id.tvRepo).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/vitvu/adblocal"))) }
        }

        btnToggle.setOnClickListener {
            if (AdbShareService.running) AdbShareService.stop(this) else withNotifications { AdbShareService.start(this) }
        }
        findViewById<View>(R.id.btnCopy).setOnClickListener { copy(connectCommand()) }

        setupSteps()
        setupSettings()
    }

    override fun onStart() {
        super.onStart()
        ShareState.observe(statusListener)
    }

    override fun onResume() {
        super.onResume()
        refreshSteps()
    }

    override fun onStop() {
        ShareState.remove(statusListener)
        super.onStop()
    }

    // ---------------- status ----------------

    private fun render(s: Status) {
        val ip = s.addresses.firstOrNull()?.ip ?: NetUtil.lanAddresses().firstOrNull()?.ip
        val port = prefs.port
        val (label, color) = when (s.phase) {
            Phase.SHARING -> "Đang chia sẻ ADB" to 0xFF7CFFB2.toInt()
            Phase.OFF -> "Đã tắt" to 0x99FFFFFF.toInt()
            Phase.ERROR -> "Có lỗi" to 0xFFFFB4AB.toInt()
            Phase.NEED_PAIR, Phase.NEED_WIRELESS -> "Cần thiết lập" to 0xFFFFE08A.toInt()
            Phase.PAIRING -> "Đang ghép đôi" to 0xFFFFE08A.toInt()
            else -> "Đang chuẩn bị" to 0xFFFFE08A.toInt()
        }
        tvStatus.text = label
        dot.background.setTint(color)
        tvAddress.text = if (ip != null) "$ip:$port" else "Chưa có Wi-Fi"
        tvMessage.text = if (s.serviceOn) s.message else "Nhấn Bắt đầu để mở ADB trên cổng $port"
        btnToggle.text = if (s.serviceOn) "Dừng" else "Bắt đầu"
        tvHelp.text = buildString {
            val addr = "${ip ?: "<ip>"}:$port"
            appendLine("1. PC và điện thoại cùng mạng Wi-Fi / LAN.")
            appendLine("2. Trên PC:  adb connect $addr")
            appendLine("3. Điện thoại hiện \"Cho phép gỡ lỗi USB?\" → chọn Luôn cho phép → Cho phép.")
            append("4. Truyền màn hình mượt:  scrcpy -s $addr --video-bit-rate 8M --max-fps 60")
        }
        refreshSteps()
    }

    private fun connectCommand(): String {
        val ip = ShareState.status.addresses.firstOrNull()?.ip ?: NetUtil.lanAddresses().firstOrNull()?.ip ?: "<ip>"
        return "adb connect $ip:${prefs.port}"
    }

    // ---------------- setup steps ----------------

    private fun setupSteps() {
        val list = findViewById<LinearLayout>(R.id.stepList)
        if (!AdbController.supported) {
            list.addView(text(
                "Android ${Build.VERSION.RELEASE} không có \"Gỡ lỗi không dây\" nên ứng dụng không thể tự mở ADB " +
                    "khi chưa root. Đây là giới hạn bảo mật của Android: cần Android 11 trở lên.\n\n" +
                    "App vẫn tự nhận ra nếu cổng ${prefs.port} đã được mở sẵn (adb tcpip từ PC).",
                14f, R.color.text_muted,
            ))
            return
        }
        addStep(list, "1. Bật Tùy chọn nhà phát triển",
            "Cài đặt › Thông tin điện thoại › nhấn \"Số hiệu bản tạo\" 7 lần",
            { Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 },
            { "Mở" }) {
            val dev = Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
            runCatching {
                startActivity(Intent(if (dev) Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS else Settings.ACTION_DEVICE_INFO_SETTINGS))
            }
        }
        addStep(list, "2. Bật Gỡ lỗi không dây",
            "Trong Tùy chọn nhà phát triển. Khi được hỏi, chọn \"Luôn cho phép trên mạng này\"",
            { AdbController.isWirelessDebuggingOn(this) }, { if (it) "Mở" else "Bật" }) {
            runCatching { startActivity(AdbController.wirelessSettingsIntent()) }
        }
        addStep(list, "3. Ghép đôi với chính máy này",
            "Nhấn Ghép đôi → trong Gỡ lỗi không dây chọn \"Ghép nối thiết bị bằng mã ghép nối\" → " +
                "nhập 6 số vào thông báo của ADB Local. Chỉ cần làm 1 lần",
            { prefs.paired }, { if (it) "Ghép lại" else "Ghép đôi" }) {
            withNotifications {
                AdbShareService.startPairing(this)
                runCatching { startActivity(AdbController.wirelessSettingsIntent()) }
                toast("Mở \"Ghép nối thiết bị bằng mã ghép nối\", rồi nhập mã ở thông báo")
            }
        }
    }

    private fun addStep(
        parent: LinearLayout, title: String, desc: String,
        state: () -> Boolean, buttonLabel: (Boolean) -> String, action: () -> Unit,
    ) {
        if (parent.childCount > 0) parent.addView(divider())
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(title, 15f, R.color.text, bold = true), LinearLayout.LayoutParams(0, wrap, 1f))
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
        steps += Step(status, btn, state, buttonLabel)
    }

    private fun refreshSteps() {
        for (s in steps) {
            val done = s.state()
            s.status.text = if (done) "Xong" else "Chưa"
            s.status.setTextColor(ContextCompat.getColor(this, if (done) R.color.green else R.color.amber))
            s.status.background.setTint(ContextCompat.getColor(this, if (done) R.color.green_soft else R.color.amber_soft))
            s.button.text = s.buttonLabel(done)
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

        val list = findViewById<LinearLayout>(R.id.switchList)
        addSwitch(list, "Tự bật ADB khi có Wi-Fi",
            "Tự mở lại cổng khi kết nối Wi-Fi hoặc sau khi khởi động lại máy", prefs.autoOnWifi) { prefs.autoOnWifi = it }
        addSwitch(list, "Khởi động cùng máy",
            "Tự chạy dịch vụ sau khi bật máy", prefs.startOnBoot) { prefs.startOnBoot = it }
    }

    private fun savePort() {
        val p = etPort.text?.toString()?.toIntOrNull()
        if (p == null || p !in 1024..65535) {
            etPort.error = "Cổng phải trong khoảng 1024 – 65535"
            return
        }
        if (p != prefs.port) {
            prefs.port = p
            if (AdbShareService.running) AdbShareService.start(this)
            toast("Đã lưu cổng $p")
        }
        etPort.clearFocus()
        render(ShareState.status)
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

    // ---------------- helpers ----------------

    private var pendingAfterPermission: (() -> Unit)? = null

    /** Notifications are needed: the pairing code is typed into one. */
    private fun withNotifications(block: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAfterPermission = block
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            block()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        pendingAfterPermission?.invoke()
        pendingAfterPermission = null
    }

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

    private fun copy(s: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", s))
        toast("Đã sao chép: $s")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
