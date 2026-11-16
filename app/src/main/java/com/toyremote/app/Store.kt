package com.toyremote.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// Shared state between the service and the screen
// ---------------------------------------------------------------------------

object Bus {
    private val main = Handler(Looper.getMainLooper())
    private val lines = ArrayList<String>()

    @Volatile var onChange: (() -> Unit)? = null
    @Volatile var running = false
    @Volatile var telegram = "Not started"
    @Volatile var ble = "Not started"
    @Volatile var lastPress = "None yet"
    @Volatile var botName = ""

    fun snapshotLog(): String = synchronized(lines) { lines.joinToString("\n") }

    fun log(msg: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        synchronized(lines) {
            lines.add(0, "$stamp  $msg")
            while (lines.size > 100) lines.removeAt(lines.size - 1)
        }
        changed()
    }

    fun changed() {
        main.post { onChange?.invoke() }
    }
}

// ---------------------------------------------------------------------------
// Saved settings and the list of people who may control the toy
// ---------------------------------------------------------------------------

object Store {
    private var prefs: SharedPreferences? = null

    var token = ""
        private set
    var maxLevel = 3
        private set
    var ownerId = 0L
        private set
    var ownerName = ""
        private set

    private val allowed = LinkedHashMap<Long, String>()
    private val pending = LinkedHashMap<Long, String>()
    private val denied = HashSet<Long>()

    /** Goes up every time the people lists change, so the screen knows to redraw them. */
    @Volatile var version = 0
        private set

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("toyremote", Context.MODE_PRIVATE)
        prefs = p
        token = p.getString("token", "") ?: ""
        maxLevel = p.getInt("maxLevel", 3).coerceIn(1, 3)
        ownerId = p.getLong("ownerId", 0L)
        ownerName = p.getString("ownerName", "") ?: ""
        decode(p.getString("allowed", ""), allowed)
        decode(p.getString("pending", ""), pending)
        p.getString("denied", "")?.split(";")?.mapNotNull { it.toLongOrNull() }?.let { denied.addAll(it) }
    }

    private fun clean(name: String) = name.replace(Regex("[|;\\n]"), " ").take(40)

    private fun encode(m: Map<Long, String>) = m.entries.joinToString(";") { "${it.key}|${it.value}" }

    private fun decode(s: String?, into: MutableMap<Long, String>) {
        s?.split(";")?.forEach { part ->
            val x = part.split("|", limit = 2)
            val id = x.getOrNull(0)?.toLongOrNull()
            if (id != null) into[id] = x.getOrNull(1) ?: ""
        }
    }

    private fun save() {
        prefs?.edit()
            ?.putString("token", token)
            ?.putInt("maxLevel", maxLevel)
            ?.putLong("ownerId", ownerId)
            ?.putString("ownerName", ownerName)
            ?.putString("allowed", encode(allowed))
            ?.putString("pending", encode(pending))
            ?.putString("denied", denied.joinToString(";"))
            ?.apply()
        version++
        Bus.changed()
    }

    @Synchronized fun setSettings(newToken: String, newMax: Int) {
        if (newToken != token) {
            // A different bot means different people: start the lists fresh.
            ownerId = 0L; ownerName = ""
            allowed.clear(); pending.clear(); denied.clear()
        }
        token = newToken
        maxLevel = newMax.coerceIn(1, 3)
        save()
    }

    @Synchronized fun isApproved(id: Long) = id != 0L && (id == ownerId || allowed.containsKey(id))
    @Synchronized fun isDenied(id: Long) = denied.contains(id)
    @Synchronized fun approvedIds(): List<Long> =
        (if (ownerId != 0L) listOf(ownerId) else emptyList()) + allowed.keys
    @Synchronized fun allowedList(): List<Pair<Long, String>> = allowed.map { it.key to it.value }
    @Synchronized fun pendingList(): List<Pair<Long, String>> = pending.map { it.key to it.value }

    /** Returns true if this is a new request that should be shown on screen. */
    @Synchronized fun addPending(id: Long, name: String): Boolean {
        if (isApproved(id) || denied.contains(id) || pending.containsKey(id)) return false
        pending[id] = clean(name)
        save()
        return true
    }

    @Synchronized fun setOwner(id: Long) {
        val name = pending.remove(id) ?: allowed.remove(id) ?: ""
        ownerId = id
        ownerName = name
        denied.remove(id)
        save()
    }

    @Synchronized fun unlinkOwner() {
        ownerId = 0L
        ownerName = ""
        save()
    }

    @Synchronized fun allow(id: Long) {
        val name = pending.remove(id) ?: return
        allowed[id] = name
        save()
    }

    @Synchronized fun deny(id: Long) {
        pending.remove(id)
        denied.add(id)
        save()
    }

    @Synchronized fun remove(id: Long) {
        allowed.remove(id)
        save()
    }
}
