package com.vvtool.adblocal

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
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
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var etPort: TextInputEditText
    private val statusListener: (Status) -> Unit = { render(it) }

    private class Step(val title: String, val desc: String, val done: Boolean, val button: String, val action: () -> Unit)

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

        findViewById<TextView>(R.id.tvDevice).text = "Android ${Build.VERSION.RELEASE}"
        findViewById<View>(R.id.btnToggle).setOnClickListener {
            if (AdbShareService.running) AdbShareService.stop(this) else withNotifications { AdbShareService.start(this) }
        }
        findViewById<View>(R.id.btnCopy).setOnClickListener { copy("adb connect ${address()}") }

        etPort = findViewById(R.id.etPort)
        etPort.setText(prefs.port.toString())
        etPort.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) savePort()
            false
        }
        findViewById<View>(R.id.btnSavePort).setOnClickListener { savePort() }
        findViewById<MaterialSwitch>(R.id.swAuto).apply {
            isChecked = prefs.auto
            setOnCheckedChangeListener { _, on -> prefs.auto = on }
        }
    }

    override fun onStart() {
        super.onStart()
        ShareState.observe(statusListener)
    }

    override fun onResume() {
        super.onResume()
        render(ShareState.status)
    }

    override fun onStop() {
        ShareState.remove(statusListener)
        super.onStop()
    }

    private fun address(): String {
        val ip = ShareState.status.addresses.firstOrNull()?.ip ?: NetUtil.lanAddresses().firstOrNull()?.ip ?: "<ip>"
        return "$ip:${prefs.port}"
    }

    private fun render(s: Status) {
        val (label, color) = when (s.phase) {
            Phase.SHARING -> "Sharing" to 0xFF7CFFB2.toInt()
            Phase.OFF -> "Off" to 0x99FFFFFF.toInt()
            Phase.ERROR -> "Error" to 0xFFFFB4AB.toInt()
            Phase.NEED_SETUP -> "Setup needed" to 0xFFFFE08A.toInt()
            Phase.PAIRING -> "Pairing" to 0xFFFFE08A.toInt()
            else -> "Starting" to 0xFFFFE08A.toInt()
        }
        findViewById<TextView>(R.id.tvStatus).text = label
        findViewById<View>(R.id.dot).background.setTint(color)
        findViewById<TextView>(R.id.tvAddress).text = address()
        findViewById<TextView>(R.id.tvMessage).text = if (s.serviceOn) s.message else "Tap Start to share ADB"
        findViewById<MaterialButton>(R.id.btnToggle).text = if (s.serviceOn) "Stop" else "Start"
        renderSetup()
        renderClients(s)
    }

    // ---------------- setup: only the steps this device still needs ----------------

    private fun steps(): List<Step> {
        val devOn = Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        val openDev = {
            val action = if (devOn) Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS else Settings.ACTION_DEVICE_INFO_SETTINGS
            runCatching { startActivity(Intent(action)) }
            Unit
        }
        val devHint = if (devOn) "" else "First enable Developer options: About phone › tap Build number 7 times. "

        if (!AdbController.supported) {
            // Android 10 and older: adbd can only be switched to TCP from a PC (or root).
            if (AdbController.isListening(prefs.port)) return emptyList()
            return listOf(
                Step("Turn on USB debugging", devHint + "Developer options › USB debugging.",
                    AdbController.isUsbDebuggingOn(this), "Open", openDev),
                Step("Run once from a PC", "Plug in USB and run:\nadb tcpip ${prefs.port}\nRepeat after every reboot.",
                    false, "Copy") { copy("adb tcpip ${prefs.port}") },
            )
        }

        // Android 11+: pair once with the built-in Wireless debugging, then everything is automatic.
        if (prefs.paired) return emptyList()
        val wirelessOn = AdbController.isWirelessDebuggingOn(this)
        return listOf(
            Step("Turn on Wireless debugging", devHint + "Choose \"Always allow on this network\".",
                wirelessOn, "Open") { runCatching { startActivity(AdbController.wirelessSettingsIntent()) } },
            Step("Pair", "Tap Pair, choose \"Pair device with pairing code\", then type the code into the ADB Local notification.",
                false, "Pair") {
                withNotifications {
                    AdbShareService.startPairing(this)
                    runCatching { startActivity(AdbController.wirelessSettingsIntent()) }
                }
            },
        )
    }

    private fun renderSetup() {
        val steps = steps()
        val visible = if (steps.isEmpty()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tvSetupTitle).visibility = visible
        findViewById<View>(R.id.cardSetup).visibility = visible
        val list = findViewById<LinearLayout>(R.id.stepList)
        list.removeAllViews()
        steps.forEachIndexed { i, st ->
            if (i > 0) list.addView(divider())
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text((if (st.done) "✓ " else "${i + 1}. ") + st.title, 15f,
                if (st.done) R.color.green else R.color.text, bold = true))
            if (!st.done) texts.addView(text(st.desc, 13f, R.color.text_muted))
            row.addView(texts, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginEnd = dp(10) })
            if (!st.done) {
                row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                    text = st.button
                    setOnClickListener { st.action() }
                })
            }
            list.addView(row)
        }
    }

    // ---------------- connected PCs ----------------

    private fun renderClients(s: Status) {
        val clients = s.clients.takeIf { s.phase == Phase.SHARING }
        val visible = if (clients == null) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tvClientsTitle).visibility = visible
        findViewById<View>(R.id.cardClients).visibility = visible
        if (clients == null) return
        findViewById<TextView>(R.id.tvClientsTitle).text = "Connected PCs (${clients.size})"
        val list = findViewById<LinearLayout>(R.id.clientList)
        list.removeAllViews()
        if (clients.isEmpty()) {
            list.addView(text("None yet. On your PC: adb connect ${address()}", 14f, R.color.text_muted))
            return
        }
        clients.forEachIndexed { i, c ->
            if (i > 0) list.addView(divider())
            val row = LinearLayout(this).apply { setPadding(0, dp(8), 0, dp(8)) }
            row.addView(text(c.ip, 15f, R.color.text, bold = true), LinearLayout.LayoutParams(0, wrap, 1f))
            row.addView(text(DateUtils.getRelativeTimeSpanString(c.since).toString(), 13f, R.color.text_muted))
            list.addView(row)
        }
    }

    // ---------------- helpers ----------------

    private fun savePort() {
        val p = etPort.text?.toString()?.toIntOrNull()
        if (p == null || p !in 1024..65535) {
            etPort.error = "1024 – 65535"
            return
        }
        if (p != prefs.port) {
            prefs.port = p
            if (AdbShareService.running) AdbShareService.start(this)
        }
        etPort.clearFocus()
        render(ShareState.status)
    }

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
        Toast.makeText(this, "Copied: $s", Toast.LENGTH_SHORT).show()
    }
}
