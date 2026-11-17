package com.toyremote.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Runs in the background while START is on. Waits for button presses in
 * Telegram and drives the toy over Bluetooth.
 */
class RemoteService : Service() {

    companion object {
        const val ACTION_START = "com.toyremote.app.START"
        const val ACTION_STOP = "com.toyremote.app.STOP"
        const val ACTION_PANIC = "com.toyremote.app.PANIC"
        const val ACTION_TEST_TOY = "com.toyremote.app.TEST_TOY"
        const val ACTION_TEST_TG = "com.toyremote.app.TEST_TG"
        const val ACTION_APPROVED = "com.toyremote.app.APPROVED"
        const val EXTRA_ID = "id"
        private const val CHANNEL = "toyremote"

        val MODE_LABELS = arrayOf(
            "🌊 Mode 1", "⚡ Mode 2", "💓 Mode 3",
            "📈 Mode 4", "💥 Mode 5", "🔥 Mode 6",
            "🎲 Mode 7", "🌀 Mode 8", "✨ Mode 9"
        )
        val STRENGTHS = arrayOf("Low", "Med", "High")
        /** Seconds per tap. 0 = keep going until the next tap (up to CONTINUOUS_LIMIT). */
        val DURATIONS = intArrayOf(30, 60, 0)

        /** Choices offered to remote people for showing times in their own zone. */
        val ZONES = arrayOf(
            "Pacific (US)" to "America/Los_Angeles",
            "Mountain (US)" to "America/Denver",
            "Central (US)" to "America/Chicago",
            "Eastern (US)" to "America/New_York",
            "UK" to "Europe/London",
            "Central Europe" to "Europe/Berlin",
            "Eastern Europe" to "Europe/Athens",
            "Sydney" to "Australia/Sydney"
        )
        private const val CONTINUOUS_LIMIT = 30 * 60 // safety: stop after 30 minutes with no taps
        private val SPACER = "⠀".repeat(42) // braille blank: shows as empty space but isn't trimmed
    }

    /** What one remote person has picked on their buttons. */
    private class Choice {
        @Volatile var strength = 2
        @Volatile var seconds = 0
    }

    /** What the toy is doing right now. */
    private class Running(
        val pattern: Pattern, val label: String, val strength: Int,
        val endAt: Long, val seconds: Int, val who: String?
    )

    private lateinit var broadcaster: Broadcaster
    private var tg: Telegram? = null
    private var out: ExecutorService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val rnd = Random()

    private val bleLock = Any()
    private val genLock = Any()
    @Volatile private var gen = 0
    @Volatile private var current: Running? = null
    @Volatile private var alive = false

    private val choices = ConcurrentHashMap<Long, Choice>()
    private val panels = ConcurrentHashMap<Long, Long>() // person id -> message id of their buttons

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        broadcaster = Broadcaster(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Toy Remote", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action != ACTION_START && !alive) {
            // Buttons other than START do nothing while the remote is off.
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_STOP -> stopSelf()
            ACTION_PANIC -> stopToy("Emergency stop.")
            ACTION_TEST_TOY -> {
                Bus.log("Test: buzzing for 3 seconds.")
                startPattern(Patterns.STEADY, 1, 3, "Test buzz", null)
            }
            ACTION_TEST_TG -> testTelegram()
            ACTION_APPROVED -> {
                val id = intent?.getLongExtra(EXTRA_ID, 0L) ?: 0L
                if (id != 0L) post { welcome(id) }
            }
            else -> begin()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        if (alive) return
        try {
            val n = buildNotification("Waiting for button presses")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(1, n)
            }
        } catch (e: Exception) {
            Bus.log("Couldn't start in the background: ${e.message}")
            stopSelf()
            return
        }

        if (Store.token.isBlank()) {
            Bus.log("No bot code saved. Paste your bot code and press START.")
            stopSelf()
            return
        }
        val problem = broadcaster.problem()
        if (problem != null) {
            Bus.log(problem)
            stopSelf()
            return
        }

        alive = true
        tg = Telegram(Store.token)
        out = Executors.newSingleThreadExecutor()
        Bus.running = true
        Bus.telegram = "Connecting..."
        Bus.ble = "Ready"
        Bus.log("Started.")

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ToyRemote::run").also {
            it.acquire(12 * 60 * 60 * 1000L)
        }
        thread(isDaemon = true) { pollLoop() }
        thread(isDaemon = true) { heartbeatLoop() }
    }

    /** Refreshes the "checked" time on everyone's buttons once a minute. */
    private fun heartbeatLoop() {
        while (alive) {
            if (!pause(60_000)) return
            refreshPanels()
        }
    }

    override fun onDestroy() {
        val wasAlive = alive
        alive = false
        synchronized(genLock) {
            gen++
            current = null
        }
        tg?.cancel()
        if (wasAlive) {
            val b = broadcaster
            thread(isDaemon = true) {
                synchronized(bleLock) {
                    b.send(LoveSpouse.STOP)
                    try {
                        Thread.sleep(1500)
                    } catch (ignored: InterruptedException) {
                    }
                    b.stop()
                }
            }
            // Tell remote people the buttons are off for now.
            val who = Store.nameForRemote()
            val offText = if (who.isNotBlank()) "🔴 Disconnected from $who\n\nThe toy remote is switched off for now."
            else "🔴 Disconnected\n\nThe toy remote is switched off for now."
            val t = tg
            val ex = out
            if (t != null && ex != null) {
                for ((id, msg) in panels) {
                    ex.execute {
                        safe { t.edit(id, msg, offText, null) }
                    }
                }
                ex.shutdown()
            }
        }
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (ignored: Exception) {
        }
        Bus.running = false
        Bus.telegram = "Not started"
        Bus.ble = "Not started"
        if (wasAlive) Bus.log("Stopped.")
        Bus.changed()
        super.onDestroy()
    }

    // ---- small helpers ----

    /** Runs Telegram sends one at a time, off the polling thread. */
    private fun post(job: () -> Unit) {
        try {
            out?.execute { if (alive) job() }
        } catch (ignored: Exception) {
        }
    }

    private fun safe(job: () -> Unit) {
        try {
            job()
        } catch (e: TelegramError) {
            // "message is not modified" just means nothing changed on screen.
            if (e.message?.contains("not modified") != true) Bus.log("Telegram: ${e.message}")
        } catch (e: Exception) {
            Bus.log("Couldn't reach Telegram: ${e.message}")
        }
    }

    private fun pause(ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            alive
        } catch (ignored: InterruptedException) {
            false
        }
    }

    /** Time with its zone (e.g. "3:31 PM PST"), since the remote person may be elsewhere. */
    private fun clock(zone: String? = null): String {
        val f = SimpleDateFormat("h:mm a z", Locale.US)
        if (zone != null) f.timeZone = java.util.TimeZone.getTimeZone(zone)
        return f.format(Date())
    }

    // ---- Telegram: receiving ----

    private fun pollLoop() {
        val t = tg ?: return
        var offset = 0L
        var ready = false
        var announced = false
        var backoff = 1000L
        while (alive) {
            try {
                if (!ready) {
                    Bus.telegram = "Connecting..."
                    Bus.changed()
                    val me = t.call("getMe").getJSONObject("result")
                    Bus.botName = me.optString("username", "")
                    // Skip anything pressed while the app was off, so old taps can't start the toy.
                    val old = t.call("getUpdates", JSONObject().put("offset", -1).put("timeout", 0))
                        .optJSONArray("result")
                    if (old != null && old.length() > 0) {
                        offset = old.getJSONObject(old.length() - 1).getLong("update_id") + 1
                    }
                    ready = true
                    if (!announced) {
                        announced = true
                        for (id in Store.approvedIds()) {
                            if (id != Store.ownerId) post { sendPanel(id) }
                        }
                    }
                    Bus.telegram = "Connected as @${Bus.botName}"
                    Bus.log("Connected to Telegram as @${Bus.botName}.")
                    Bus.changed()
                }
                val p = JSONObject()
                    .put("offset", offset)
                    .put("timeout", 50)
                    .put("allowed_updates", JSONArray().put("message").put("callback_query"))
                val updates = t.call("getUpdates", p, 65000).optJSONArray("result") ?: JSONArray()
                if (!Bus.telegram.startsWith("Connected")) {
                    Bus.telegram = "Connected as @${Bus.botName}"
                    Bus.log("Reconnected to Telegram.")
                    Bus.changed()
                }
                for (i in 0 until updates.length()) {
                    val u = updates.getJSONObject(i)
                    offset = maxOf(offset, u.getLong("update_id") + 1)
                    try {
                        handleUpdate(u)
                    } catch (e: Exception) {
                        Bus.log("Couldn't handle a message: ${e.message}")
                    }
                }
                backoff = 1000L
            } catch (ignored: SocketTimeoutException) {
                continue // a quiet minute is normal
            } catch (e: TelegramError) {
                if (!alive) return
                when (e.code) {
                    401, 404 -> {
                        Bus.telegram = "Problem: bot code is wrong"
                        Bus.log("Telegram didn't accept your bot code. Check it was copied correctly.")
                        ready = false
                        Bus.changed()
                        if (!pause(15000)) return
                    }
                    409 -> {
                        Bus.telegram = "Problem: bot in use elsewhere"
                        Bus.log("Another app or phone is using this bot. Close it there and this will reconnect.")
                        Bus.changed()
                        if (!pause(10000)) return
                    }
                    else -> {
                        Bus.telegram = "Problem"
                        Bus.log("Telegram error: ${e.message}. Retrying...")
                        Bus.changed()
                        if (!pause(backoff)) return
                        backoff = minOf(backoff * 2, 30000L)
                    }
                }
            } catch (e: Exception) {
                if (!alive) return
                Bus.telegram = "Problem: no connection"
                Bus.log("Can't reach Telegram (${e.message}). Retrying...")
                Bus.changed()
                if (!pause(backoff)) return
                backoff = minOf(backoff * 2, 30000L)
            }
        }
    }

    private fun handleUpdate(u: JSONObject) {
        val msg = u.optJSONObject("message")
        if (msg != null) {
            handleMessage(msg)
            return
        }
        val cq = u.optJSONObject("callback_query") ?: return
        handleButton(cq)
    }

    private fun handleMessage(msg: JSONObject) {
        val chat = msg.optJSONObject("chat") ?: return
        if (chat.optString("type") != "private") return // ignore groups
        val from = msg.optJSONObject("from") ?: return
        val id = from.optLong("id")
        if (id == 0L || from.optBoolean("is_bot")) return
        val name = Telegram.displayName(from)
        val text = msg.optString("text", "").trim().lowercase()
        val t = tg ?: return

        if (Store.isApproved(id)) {
            if (text == "/stop" || text == "stop") {
                stopToy("$name sent stop.")
                return
            }
            if (text == "/timezone" || text == "timezone") {
                post { sendZonePicker(id) }
                return
            }
            post { sendPanel(id) }
            return
        }
        if (Store.isDenied(id)) return
        if (Store.addPending(id, name)) {
            Bus.log("New request from $name. Allow or deny it in section 2.")
            post {
                safe {
                    t.send(id, "Hi! I've asked for permission. You'll get the controls here as soon as you're approved.")
                }
            }
        }
    }

    private fun handleButton(cq: JSONObject) {
        val from = cq.optJSONObject("from") ?: return
        val id = from.optLong("id")
        val cbId = cq.optString("id")
        val data = cq.optString("data", "")
        val name = Telegram.displayName(from)
        val t = tg ?: return

        if (!Store.isApproved(id)) {
            post { safe { t.answer(cbId, "You're not approved to control this toy.") } }
            return
        }
        // Confirm the tap straight away (no pop-up), so Telegram's "Loading..." clears quickly.
        // The panel itself then shows what changed.
        post { safe { t.answer(cbId, "") } }
        val panelMsg = cq.optJSONObject("message")?.optLong("message_id") ?: 0L
        if (panelMsg != 0L && data != "t" && !data.startsWith("z:")) panels[id] = panelMsg
        val c = choices.getOrPut(id) { Choice() }

        when {
            data == "x" -> {
                stopToy("$name pressed Stop.")
            }
            data == "t" -> {
                if (id == Store.ownerId) {
                    Bus.log("Telegram test reply received. Everything is connected.")
                    startPattern(Patterns.STEADY, 1, 3, "Test buzz", null)
                }
            }
            data.startsWith("m:") -> {
                val n = data.substring(2).toIntOrNull()
                if (n != null && n in 1..9) {
                    startPattern(Patterns.mode(n), c.strength, c.seconds, "Mode $n", name)
                }
            }
            data.startsWith("s:") -> {
                val n = data.substring(2).toIntOrNull()
                if (n != null && n in 1..3) {
                    c.strength = n
                    val now = current
                    if (now != null && now.who != null) {
                        // Something is running: change its strength, keep its time.
                        val left = if (now.seconds == 0) 0
                        else ((now.endAt - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(1)
                        startPattern(now.pattern, n, left, now.label, name)
                    } else {
                        startPattern(Patterns.STEADY, n, c.seconds, "Steady", name)
                    }
                }
            }
            data.startsWith("z:") -> {
                val n = data.substring(2).toIntOrNull()
                if (n != null && n in ZONES.indices) {
                    Store.setZone(id, ZONES[n].second)
                    // Swap the picker for a short confirmation, without the buttons.
                    if (panelMsg != 0L) {
                        val done = "✓ Times shown in ${ZONES[n].first} time.\nSend /timezone to change it.\n$SPACER"
                        post { safe { t.edit(id, panelMsg, done, null) } }
                    }
                    refreshPanels()
                }
            }
            data.startsWith("d:") -> {
                val n = data.substring(2).toIntOrNull()
                if (n != null && n in DURATIONS) {
                    c.seconds = n
                    val now = current
                    if (now != null && now.who != null) {
                        // Something is running: apply the new time to it straight away
                        // (restarts the countdown, or makes it run until changed).
                        startPattern(now.pattern, now.strength, n, now.label, name)
                    } else {
                        refreshPanels()
                    }
                }
            }
        }
    }

    // ---- Telegram: sending ----

    private fun panelText(id: Long): String {
        val now = current
        val c = choices[id] ?: Choice()
        val status = if (now == null) {
            "Toy is idle.\nTap a mode or a strength to start."
        } else {
            val by = if (now.who != null) "\nStarted by ${now.who}" else ""
            val strength = if (now.pattern.native != null) "" else " · ${STRENGTHS[now.strength - 1]}"
            "▶ ${now.label}$strength · ${timeText(now.seconds)}$by"
        }
        // An invisible full-width line keeps the message (and so the buttons)
        // the same width whatever the status says.
        var each = if (c.seconds == 0) "Each tap lasts until the next one." else "Each tap lasts ${c.seconds} seconds."
        if (now != null && now.seconds > 0 && now.who != null) {
            val left = ((now.endAt - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
            each += " · ⏱ ${left}s left"
        }
        val who = Store.nameForRemote()
        val to = if (who.isNotBlank()) " to $who" else ""
        return "🟢 Connected$to · checked ${clock(Store.zoneFor(id))}\n\n$status\n\n$each\n$SPACER"
    }

    private fun timeText(seconds: Int) = if (seconds == 0) "until changed" else "${seconds}s"

    private fun keyboard(id: Long): JSONArray {
        val c = choices[id] ?: Choice()
        val now = current
        val activeMode = now?.label?.removePrefix("Mode ")?.toIntOrNull()
        val kb = JSONArray()
        for (r in 0 until 3) {
            val row = JSONArray()
            for (col in 0 until 3) {
                val n = r * 3 + col + 1
                row.put(Telegram.button(MODE_LABELS[n - 1], "m:$n", if (n == activeMode) "primary" else null))
            }
            kb.put(row)
        }
        val strength = JSONArray()
        for (i in 1..3) {
            val mark = if (c.strength == i) "✓ " else ""
            strength.put(Telegram.button(mark + STRENGTHS[i - 1], "s:$i", if (c.strength == i) "primary" else null))
        }
        // Built-in modes have their own fixed intensity, so no strength row then.
        if (!Store.builtInModes) kb.put(strength)
        val time = JSONArray()
        for (d in DURATIONS) {
            val mark = if (c.seconds == d) "✓ " else ""
            time.put(Telegram.button(mark + (if (d == 0) "∞ Until changed" else "${d}s"), "d:$d", if (c.seconds == d) "primary" else null))
        }
        kb.put(time)
        kb.put(JSONArray().put(Telegram.button("⏹ Stop", "x", "danger")))
        return kb
    }

    private fun sendPanel(id: Long) {
        val t = tg ?: return
        safe {
            val msgId = t.send(id, panelText(id), keyboard(id))
            if (msgId != 0L) panels[id] = msgId
        }
    }

    private fun welcome(id: Long) {
        val t = tg ?: return
        safe { t.send(id, "You're approved! Here are the controls.") }
        sendPanel(id)
        if (Store.zoneFor(id) == null) sendZonePicker(id)
    }

    /** Asks once which time zone to show times in. Send /timezone to change it later. */
    private fun sendZonePicker(id: Long) {
        val t = tg ?: return
        val kb = JSONArray()
        var row = JSONArray()
        for ((i, z) in ZONES.withIndex()) {
            row.put(Telegram.button(z.first, "z:$i"))
            if (row.length() == 2) {
                kb.put(row)
                row = JSONArray()
            }
        }
        if (row.length() > 0) kb.put(row)
        safe {
            t.send(id, "Which time zone are you in?\n\nTimes on your remote will use it.\nSend /timezone to change it later.\n$SPACER", kb)
        }
    }

    /** Updates every approved person's buttons to show what the toy is doing. */
    private fun refreshPanels() {
        val t = tg ?: return
        for ((id, msg) in panels) {
            if (!Store.isApproved(id)) {
                panels.remove(id)
                continue
            }
            post { safe { t.edit(id, msg, panelText(id), keyboard(id)) } }
        }
    }

    private fun testTelegram() {
        val t = tg ?: return
        val owner = Store.ownerId
        if (owner == 0L) {
            val bot = if (Bus.botName.isNotBlank()) "@${Bus.botName}" else "your bot"
            Bus.log("First link your own Telegram: send any message to $bot, then tap \"This is me\" in section 2.")
            return
        }
        Bus.log("Sent a test message to your Telegram. Tap the button in it.")
        val kb = JSONArray().put(JSONArray().put(Telegram.button("✅ Test buzz", "t")))
        post {
            safe {
                t.send(owner, "Test from Toy Remote\n\nTap the button below. If the toy buzzes, everything works.\n$SPACER", kb)
            }
        }
    }

    // ---- the toy side ----

    private fun startPattern(p: Pattern, chosen: Int, seconds: Int, label: String, who: String?) {
        if (!alive) return
        val strength = minOf(chosen, Store.maxLevel).coerceIn(1, 3)
        val secs = if (seconds <= 0) 0 else seconds.coerceIn(1, 60)
        val millis = if (secs == 0) CONTINUOUS_LIMIT * 1000L else secs * 1000L
        val myGen: Int
        synchronized(genLock) {
            gen++
            myGen = gen
            current = Running(p, label, strength, System.currentTimeMillis() + millis, secs, who)
        }
        val desc = if (p.native != null) "$label, ${timeText(secs)}"
        else "$label, ${STRENGTHS[strength - 1].lowercase()}, ${timeText(secs)}"
        if (who != null) {
            Bus.lastPress = "$who: $desc at ${clock()}"
            Bus.log("$who: $desc")
        }
        Bus.changed()
        refreshPanels()
        thread(isDaemon = true) { runPattern(myGen, p, strength, millis) }
        if (secs > 0 && who != null) thread(isDaemon = true) { countdown(myGen, secs) }
    }

    /** Updates the panels every 5 seconds while a timed tap runs (Telegram limits faster edits). */
    private fun countdown(myGen: Int, secs: Int) {
        var shown = secs
        while (shown > 5) {
            if (!wait(myGen, 5000)) return
            shown -= 5
            refreshPanels()
        }
    }

    private fun runPattern(myGen: Int, p: Pattern, strength: Int, millis: Long) {
        val end = System.currentTimeMillis() + millis
        val native = p.native
        if (native != null) {
            if (!sendRaw(myGen, native)) return
            if (!wait(myGen, millis)) return
        } else {
            var last = -1
            var i = 0
            while (true) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                val step = p.step(i++, rnd)
                val level = Patterns.scale(step.first, strength)
                if (level != last) {
                    val payload = if (level == 0) LoveSpouse.STOP else LoveSpouse.LEVELS[level - 1]
                    if (!sendRaw(myGen, payload)) return
                    last = level
                }
                if (!wait(myGen, minOf(step.second.toLong(), left))) return
            }
        }
        // Time's up and nobody pressed anything new.
        var wasContinuous = false
        val finished = synchronized(genLock) {
            if (gen == myGen) {
                wasContinuous = current?.seconds == 0
                current = null
                true
            } else false
        }
        if (!finished) return
        if (wasContinuous) Bus.log("Stopped: no button presses for 30 minutes.")
        refreshPanels()
        quiet(myGen)
    }

    /** Sends one command, unless a newer press has taken over. */
    private fun sendRaw(myGen: Int, payload: ByteArray): Boolean {
        synchronized(bleLock) {
            if (!alive || gen != myGen) return false
            broadcaster.send(payload)
            return true
        }
    }

    /** Sleeps in small slices; returns false as soon as a newer press takes over. */
    private fun wait(myGen: Int, ms: Long): Boolean {
        var waited = 0L
        while (waited < ms) {
            if (!alive || gen != myGen) return false
            val slice = minOf(100L, ms - waited)
            try {
                Thread.sleep(slice)
            } catch (ignored: InterruptedException) {
                return false
            }
            waited += slice
        }
        return alive && gen == myGen
    }

    /** Stops the toy and then ends the broadcast. */
    private fun quiet(myGen: Int) {
        if (!sendRaw(myGen, LoveSpouse.STOP)) return
        if (!wait(myGen, 1500)) return
        synchronized(bleLock) {
            if (!alive || gen != myGen) return
            broadcaster.stop()
        }
        Bus.ble = "Ready"
        Bus.changed()
    }

    private fun stopToy(reason: String) {
        val myGen: Int
        synchronized(genLock) {
            gen++
            myGen = gen
            current = null
        }
        Bus.log(reason)
        refreshPanels()
        thread(isDaemon = true) { quiet(myGen) }
    }

    // ---- notification ----

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RemoteService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Toy Remote is running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .setOngoing(true)
            .build()
    }
}
