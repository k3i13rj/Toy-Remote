package com.toyremote.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import java.util.Random

// ---------------------------------------------------------------------------
// Love Spouse / MuSe advertisement commands (same as Tip Bridge)
// Format from the Buttplug project's protocol notes (docs.buttplug.io issue #2):
// 11 bytes of manufacturer data = 8-byte prefix + 3-byte command.
// ---------------------------------------------------------------------------

object LoveSpouse {
    const val COMPANY_ID = 0xFFF0

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val PREFIX = bytes(0x6D, 0xB6, 0x43, 0xCE, 0x97, 0xFE, 0x42, 0x7C)

    val STOP: ByteArray = PREFIX + bytes(0xE5, 0x15, 0x7D)

    /** Index 0 = speed 1, 1 = speed 2, 2 = speed 3 (all channels). */
    val LEVELS: Array<ByteArray> = arrayOf(
        PREFIX + bytes(0xE4, 0x9C, 0x6C),
        PREFIX + bytes(0xE7, 0x07, 0x5E),
        PREFIX + bytes(0xE6, 0x8E, 0x4F)
    )

    /**
     * The toy's own built-in patterns: Mode 1-9 in the official Love Spouse
     * iPhone app, captured from its broadcasts on 2026-11-16 and converted to
     * this format. Each has its own fixed intensity. (The app's own stop is
     * E4 9C 6C; we keep Tip Bridge's stop sequence, which is proven on her egg.)
     */
    val NATIVE_MODES: Array<ByteArray?> = arrayOf(
        PREFIX + bytes(0xE5, 0x15, 0x7D), // Mode 1
        PREFIX + bytes(0xE6, 0x8E, 0x4F), // Mode 2
        PREFIX + bytes(0xE7, 0x07, 0x5E), // Mode 3
        PREFIX + bytes(0xE0, 0xB8, 0x2A), // Mode 4
        PREFIX + bytes(0xE1, 0x31, 0x3B), // Mode 5
        PREFIX + bytes(0xE2, 0xAA, 0x09), // Mode 6
        PREFIX + bytes(0xE3, 0x23, 0x18), // Mode 7
        PREFIX + bytes(0xEC, 0xD4, 0xE0), // Mode 8
        PREFIX + bytes(0xED, 0x5D, 0xF1)  // Mode 9
    )
}

class Broadcaster(ctx: Context) {
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertiseCallback? = null

    fun problem(): String? {
        if (Bus.simulate) return null
        val a = adapter ?: return "This phone doesn't have Bluetooth."
        if (!a.isEnabled) return "Bluetooth is turned off. Turn it on and press START again."
        if (a.bluetoothLeAdvertiser == null) {
            return "This phone can't send the Bluetooth broadcasts the toy needs."
        }
        return null
    }

    @SuppressLint("MissingPermission")
    fun send(payload: ByteArray) {
        if (Bus.simulate) {
            Bus.ble = "Simulated"
            Bus.changed()
            val native = LoveSpouse.NATIVE_MODES.indexOfFirst { it != null && it.contentEquals(payload) }
            val level = LoveSpouse.LEVELS.indexOfFirst { it.contentEquals(payload) }
            val what = when {
                payload.contentEquals(LoveSpouse.STOP) -> "stop"
                level >= 0 -> "level ${level + 1}"
                native >= 0 -> "built-in mode ${native + 1}"
                else -> "unknown"
            }
            android.util.Log.i("ToyRemote", "Simulated toy: $what")
            return
        }
        stop()
        try {
            Thread.sleep(120)
        } catch (ignored: InterruptedException) {
        }
        val adv = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED) // about 250 ms
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(LoveSpouse.COMPANY_ID, payload)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Bus.ble = "Broadcasting"
                Bus.changed()
            }

            override fun onStartFailure(errorCode: Int) {
                val why = when (errorCode) {
                    AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
                    AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many broadcasts running"
                    AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
                    AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
                    AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "this phone doesn't allow it"
                    else -> "code $errorCode"
                }
                Bus.ble = "Problem: $why"
                Bus.log("Bluetooth broadcast failed ($why).")
                Bus.changed()
            }
        }
        callback = cb
        advertiser = adv
        try {
            adv.startAdvertising(settings, data, cb)
        } catch (e: Exception) {
            Bus.ble = "Problem"
            Bus.log("Bluetooth broadcast failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (Bus.simulate) return
        try {
            callback?.let { advertiser?.stopAdvertising(it) }
        } catch (ignored: Exception) {
        }
        callback = null
    }
}

// ---------------------------------------------------------------------------
// Patterns. Each step is (level 0-3, milliseconds). Level 3 means "the
// strength the remote person picked"; lower levels scale down from it.
// Steps shorter than about 400 ms are unreliable with broadcast toys.
// ---------------------------------------------------------------------------

class Pattern(
    private val steps: List<Pair<Int, Int>>,
    private val random: Boolean = false,
    val native: ByteArray? = null
) {
    fun step(i: Int, rnd: Random): Pair<Int, Int> =
        if (random) Pair(1 + rnd.nextInt(3), 700) else steps[i % steps.size]
}

object Patterns {
    val STEADY = Pattern(listOf(3 to 1000))

    private val MADE = arrayOf(
        Pattern(listOf(3 to 600, 0 to 600)),                         // 1 pulse
        Pattern(listOf(3 to 400, 0 to 400)),                         // 2 fast pulse
        Pattern(listOf(1 to 800, 2 to 800, 3 to 800, 2 to 800)),     // 3 wave
        Pattern(listOf(1 to 2500, 2 to 2500, 3 to 2500)),            // 4 build up
        Pattern(listOf(3 to 400, 0 to 400, 3 to 400, 0 to 1400)),    // 5 heartbeat
        Pattern(listOf(3 to 2500, 1 to 1200)),                       // 6 surge
        Pattern(emptyList(), random = true),                         // 7 random
        Pattern(listOf(3 to 1200, 2 to 1200, 1 to 1200, 0 to 600)),  // 8 steps down
        Pattern(listOf(1 to 1500, 0 to 1200, 3 to 700, 0 to 1500))   // 9 tease
    )

    /** n = 1..9 */
    fun mode(n: Int): Pattern {
        val code = if (Store.builtInModes) LoveSpouse.NATIVE_MODES[n - 1] else null
        return if (code != null) Pattern(emptyList(), native = code) else MADE[n - 1]
    }

    /** Scale a pattern level (0-3) to the chosen strength (1-3). */
    fun scale(level: Int, strength: Int): Int =
        if (level <= 0) 0 else maxOf(1, Math.ceil(level * strength / 3.0).toInt())
}
