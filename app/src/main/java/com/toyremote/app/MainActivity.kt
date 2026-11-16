package com.toyremote.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private val GREEN = Color.parseColor("#2E9E4F")
        private val RED = Color.parseColor("#D33030")
        private val GREY = Color.parseColor("#888888")
        private val ORANGE = Color.parseColor("#E0A000")
        private val BLUE = Color.parseColor("#3D6FB6")
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private val TOKEN_SHAPE = Regex("^\\d+:[A-Za-z0-9_-]{20,}$")
    }

    private lateinit var tokenEdit: EditText
    private lateinit var nameEdit: EditText
    private lateinit var maxSpinner: Spinner
    private lateinit var peopleBox: LinearLayout
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var panicBtn: Button
    private lateinit var testToyBtn: Button
    private lateinit var testTgBtn: Button
    private lateinit var tgText: TextView
    private lateinit var bleText: TextView
    private lateinit var lastText: TextView
    private lateinit var logText: TextView
    private var pendingStart = false
    private var shownVersion = -1

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(s: String, size: Float = 14f, bold: Boolean = false, color: Int = Color.DKGRAY) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun section(root: LinearLayout, title: String) {
        val t = tv(title, 15f, true, Color.BLACK)
        t.setPadding(0, dp(18), 0, dp(4))
        root.addView(t)
    }

    private fun btn(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setBackgroundColor(color)
        setOnClickListener { onClick() }
    }

    private fun smallBtn(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        setAllCaps(false)
        setMinHeight(0)
        setMinimumHeight(0)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        setTextColor(Color.WHITE)
        setBackgroundColor(color)
        setOnClickListener { onClick() }
    }

    private fun setBtn(b: Button, enabled: Boolean) {
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.4f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Store.init(this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }
        scroll.addView(root, LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(tv("Toy Remote", 26f, true, Color.BLACK))
        root.addView(tv("People you approve can control your toy from Telegram.", 13f))

        // 1. bot code
        section(root, "1. Your Telegram bot code")
        tokenEdit = EditText(this).apply {
            hint = "Paste your bot code here"
            setText(Store.token)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(tokenEdit, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(CheckBox(this).apply {
            text = "Show code"
            setOnCheckedChangeListener { _, checked ->
                tokenEdit.inputType = InputType.TYPE_CLASS_TEXT or
                    (if (checked) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    else InputType.TYPE_TEXT_VARIATION_PASSWORD)
            }
        })
        root.addView(
            tv(
                "To get one: in Telegram, message @BotFather, send /newbot, pick a name, " +
                    "and paste the code it gives you here.",
                12f
            )
        )
        root.addView(tv("Keep this code secret. Anyone who has it can take over your bot.", 12f, false, Color.parseColor("#A05000")))

        // name shown on the remote
        section(root, "Your name on the remote (optional)")
        nameEdit = EditText(this).apply {
            hint = "e.g. Anna"
            setText(Store.shownName)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(nameEdit, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(tv("People controlling your toy see \"Connected to\" this name. Leave it empty to use your Telegram first name.", 12f))

        // 2. people
        section(root, "2. Who can control the toy")
        peopleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(peopleBox, LinearLayout.LayoutParams(MATCH, WRAP))

        // 3. max level
        section(root, "3. Strongest the toy is ever allowed to go")
        maxSpinner = Spinner(this)
        maxSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Level 1 (gentlest)", "Level 2", "Level 3 (strongest)")
        )
        maxSpinner.setSelection((Store.maxLevel - 1).coerceIn(0, 2))
        root.addView(maxSpinner, LinearLayout.LayoutParams(MATCH, WRAP))

        // buttons
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = btn("START", GREEN) { startPressed() }
        stopBtn = btn("STOP", Color.parseColor("#555555")) { stopPressed() }
        bar.addView(startBtn, LinearLayout.LayoutParams(0, dp(64), 1f).apply { setMargins(0, 0, dp(6), 0) })
        bar.addView(stopBtn, LinearLayout.LayoutParams(0, dp(64), 1f))
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(20), 0, 0) })

        panicBtn = btn("EMERGENCY STOP", RED) { send(RemoteService.ACTION_PANIC) }
        root.addView(panicBtn, LinearLayout.LayoutParams(MATCH, dp(56)).apply { setMargins(0, dp(8), 0, 0) })

        // status
        section(root, "Status")
        tgText = tv("", 15f)
        bleText = tv("", 15f)
        lastText = tv("", 13f)
        root.addView(tgText)
        root.addView(bleText)
        root.addView(lastText)

        // tests
        section(root, "Tests")
        testToyBtn = btn("Test toy: buzz for 3 seconds", BLUE) { send(RemoteService.ACTION_TEST_TOY) }
        root.addView(testToyBtn, LinearLayout.LayoutParams(MATCH, dp(48)))
        testTgBtn = btn("Test Telegram: send me a test message", BLUE) { send(RemoteService.ACTION_TEST_TG) }
        root.addView(testTgBtn, LinearLayout.LayoutParams(MATCH, dp(48)).apply { setMargins(0, dp(8), 0, 0) })

        // phone tips
        section(root, "Keep it running")
        root.addView(
            tv(
                "Keep the phone plugged in, this app open, and the phone close to the toy. " +
                    "If the phone closes the app by itself, open the battery settings below and " +
                    "allow Toy Remote to run without limits. Don't run Tip Bridge at the same time.",
                12f
            )
        )
        val batt = btn("Open battery settings", Color.parseColor("#777777")) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (ignored: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        root.addView(batt, LinearLayout.LayoutParams(MATCH, dp(44)).apply { setMargins(0, dp(8), 0, 0) })

        // log
        section(root, "What's happening")
        logText = tv("", 12f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(logText)

        setContentView(scroll)

        if (BuildConfig.TEST_MODE) {
            Bus.simulate = true
            intent?.getStringExtra("test_token")?.let { tokenEdit.setText(it) }
            if (intent?.getBooleanExtra("test_autostart", false) == true && !Bus.running) startPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        Bus.onChange = { refresh() }
        refresh()
    }

    override fun onPause() {
        Bus.onChange = null
        super.onPause()
    }

    // ---- display ----

    private fun refresh() {
        val running = Bus.running
        tokenEdit.isEnabled = !running
        nameEdit.isEnabled = !running
        maxSpinner.isEnabled = !running
        setBtn(startBtn, !running)
        setBtn(stopBtn, running)
        setBtn(panicBtn, running)
        setBtn(testToyBtn, running)
        setBtn(testTgBtn, running)
        setStatus(tgText, "Telegram", Bus.telegram)
        setStatus(bleText, "Toy broadcast", Bus.ble)
        lastText.text = "Last button press: ${Bus.lastPress}"
        if (Store.version != shownVersion) buildPeople()
        logText.text = Bus.snapshotLog()
    }

    private fun setStatus(t: TextView, name: String, value: String) {
        val color = when {
            value.startsWith("Connected") || value == "Ready" || value == "Broadcasting" -> GREEN
            value.startsWith("Problem") -> RED
            value == "Not started" -> GREY
            else -> ORANGE
        }
        t.text = "● $name: $value"
        t.setTextColor(color)
    }

    private fun personRow(label: String, vararg buttons: Button) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(tv(label, 14f, false, Color.BLACK), LinearLayout.LayoutParams(0, WRAP, 1f))
        for (b in buttons) {
            row.addView(b, LinearLayout.LayoutParams(WRAP, dp(34)).apply { setMargins(dp(6), 0, 0, 0) })
        }
        peopleBox.addView(row)
    }

    private fun buildPeople() {
        shownVersion = Store.version
        peopleBox.removeAllViews()
        val owner = Store.ownerId
        if (owner != 0L) {
            val name = Store.ownerName.ifBlank { "linked" }
            personRow("You: $name", smallBtn("Unlink", GREY) { Store.unlinkOwner() })
        }
        for ((id, name) in Store.allowedList()) {
            personRow(name.ifBlank { "Someone" }, smallBtn("Remove", RED) {
                Store.remove(id)
                Bus.log("Removed ${name.ifBlank { "someone" }}. Their buttons no longer work.")
            })
        }
        for ((id, name) in Store.pendingList()) {
            val shown = name.ifBlank { "Someone" }
            val buttons = ArrayList<Button>()
            if (owner == 0L) {
                buttons.add(smallBtn("This is me", BLUE) {
                    Store.setOwner(id)
                    Bus.log("Linked your own Telegram.")
                    approved(id)
                })
            }
            buttons.add(smallBtn("Allow", GREEN) {
                Store.allow(id)
                Bus.log("Allowed $shown.")
                approved(id)
            })
            buttons.add(smallBtn("Deny", RED) {
                Store.deny(id)
                Bus.log("Denied $shown.")
            })
            personRow("Request: $shown", *buttons.toTypedArray())
        }
        if (peopleBox.childCount == 0) {
            val bot = if (Bus.botName.isNotBlank()) "@${Bus.botName}" else "your bot"
            peopleBox.addView(
                tv(
                    "Nobody yet. Press START, then send any message to $bot from your own Telegram " +
                        "and tap \"This is me\". Ask the other person to do the same, then tap Allow.",
                    12f
                )
            )
        }
    }

    // ---- actions ----

    private fun approved(id: Long) {
        if (Bus.running) {
            startService(Intent(this, RemoteService::class.java)
                .setAction(RemoteService.ACTION_APPROVED)
                .putExtra(RemoteService.EXTRA_ID, id))
        } else {
            Bus.log("They'll get the buttons the next time they message your bot while the remote is on.")
        }
    }

    private fun send(action: String) {
        if (Bus.running) {
            startService(Intent(this, RemoteService::class.java).setAction(action))
        } else {
            Toast.makeText(this, "Press START first.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun neededPermissions(): Array<String> {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        return need.toTypedArray()
    }

    private fun startPressed() {
        val token = tokenEdit.text.toString().trim()
        if (!TOKEN_SHAPE.matches(token)) {
            Toast.makeText(
                this,
                "That doesn't look like a bot code. It looks like 123456789:ABC... Copy it again from @BotFather.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        Store.setSettings(token, maxSpinner.selectedItemPosition + 1)
        Store.setShownName(nameEdit.text.toString())
        val need = neededPermissions()
        if (need.isNotEmpty()) {
            pendingStart = true
            requestPermissions(need, 1)
            return
        }
        launchService()
    }

    private fun launchService() {
        startForegroundService(Intent(this, RemoteService::class.java).setAction(RemoteService.ACTION_START))
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!pendingStart) return
        pendingStart = false
        val advertiseOk = Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        if (advertiseOk) {
            launchService()
        } else {
            Toast.makeText(
                this,
                "Toy Remote needs the Bluetooth (\"Nearby devices\") permission to talk to the toy.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopPressed() {
        stopService(Intent(this, RemoteService::class.java))
    }
}
