package com.debashis.carlauncher

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the user can change, in one place.
 *
 * Stored as JSON in SharedPreferences using org.json, which is part of the Android framework,
 * so this still costs zero dependencies.
 */
object Prefs {

    private const val FILE = "dash"

    const val KEY_DOCK = "dock"
    const val KEY_DRIVE_DOCK = "drive_dock"
    const val KEY_DRIVE_ENABLED = "drive_enabled"
    const val KEY_DRIVE_ON = "drive_on_kmh"
    const val KEY_DRIVE_OFF = "drive_off_kmh"
    const val KEY_NIGHT = "night_mode"             // "auto", "on", "off"
    const val KEY_NIGHT_START = "night_start_hour"
    const val KEY_NIGHT_END = "night_end_hour"
    const val KEY_NIGHT_LEVEL = "night_level"      // percent of black, 0 to 80
    const val KEY_IDLE_DIM = "idle_dim_enabled"
    const val KEY_IDLE_MINUTES = "idle_minutes"
    const val KEY_ICON_PACK = "icon_pack"          // package name, empty means app icons
    const val KEY_SETUP_SHOWN = "setup_shown"
    const val KEY_CLOCK_FORMAT = "clock_format"   // -1 follow system, 12, or 24
    const val KEY_UNITS = "units"                 // "kmh" or "mph"
    const val KEY_DOCK_LABELS = "qcar_dock_labels" // false = icon-only dock

    const val CLOCK_SYSTEM = -1

    /**
     * Drive mode engages at [DEFAULT_DRIVE_ON] and disengages at [DEFAULT_DRIVE_OFF].
     * The gap is the point: a single threshold makes the interface flicker between modes
     * every time you hover around that speed in traffic. Stored in km/h regardless of the
     * display unit, so changing units never silently moves the threshold.
     */
    const val DEFAULT_DRIVE_ON = 30
    const val DEFAULT_DRIVE_OFF = 18

    const val NIGHT_AUTO = "auto"
    const val NIGHT_ON = "on"
    const val NIGHT_OFF = "off"

    /**
     * Auto night is a clock comparison, not a sun calculation.
     * An earlier version computed sunrise and sunset from the GPS fix. That was cleverness
     * for its own sake: it added a file, needed a location before it could decide anything,
     * and produced a worse answer than "is it evening yet".
     */
    const val DEFAULT_NIGHT_START = 19
    const val DEFAULT_NIGHT_END = 6
    const val DEFAULT_NIGHT_LEVEL = 45

    /** Dim after this many minutes without a touch, while the home screen is showing. */
    const val DEFAULT_IDLE_MINUTES = 3
    const val IDLE_LEVEL = 75

    /** Three slots while moving, plus the drawer, instead of eight. */
    const val DRIVE_SLOTS = 3
    const val UNITS_KMH = "kmh"
    const val UNITS_MPH = "mph"

    /** Marks the slot that opens the app drawer rather than an app. */
    const val DRAWER = "@drawer"

    /** Eight slots. An empty string means the slot is left blank and simply not drawn. */
    const val SLOTS = 8

    /**
     * One dock slot.
     *
     * [component] is either "" (empty), [DRAWER], a package name, or "package/class" when the
     * package exposes several launcher activities and the default one is wrong.
     * [label] overrides the app's own name. Blank means use whatever the app calls itself.
     */
    data class Slot(val component: String, val label: String = "")

    /**
     * Shipped defaults, matching the imotor firmware family this was built on.
     * On any other unit these resolve to nothing, the slots render dimmed, and the whole
     * point of the settings screen is that you can then fix them without a rebuild.
     */
    private val DEFAULTS = listOf(
        Slot("com.imotor.phoneconnect/com.imotor.phoneconnect.Carplay", "CarPlay"),
        Slot("com.netflix.mediaclient", "Netflix"),
        Slot("com.google.android.youtube", "YouTube"),
        Slot("com.imotor.music", "Music"),
        Slot("com.imotor.fmam", "Radio"),
        Slot("com.imotor.phoneconnect/com.imotor.phoneconnect.AndroidAuto", "Android Auto"),
        Slot("com.imotor.contacts/com.imotor.contacts.ui.MainActivity", "Phone"),
        Slot(DRAWER, "All apps")
    )

    private val DRIVE_DEFAULTS = listOf(
        Slot("com.imotor.phoneconnect/com.imotor.phoneconnect.Carplay", "CarPlay"),
        Slot("com.imotor.music", "Music"),
        Slot("com.imotor.contacts/com.imotor.contacts.ui.MainActivity", "Phone")
    )

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun dockLabelsVisible(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DOCK_LABELS, false)

    fun setDockLabelsVisible(context: Context, visible: Boolean) {
        prefs(context).edit().putBoolean(KEY_DOCK_LABELS, visible).apply()
    }

    // ------------------------------------------------------------------ dock

    fun dock(context: Context): List<Slot> {
        val raw = prefs(context).getString(KEY_DOCK, null) ?: return DEFAULTS
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<Slot>(SLOTS)
            for (i in 0 until minOf(array.length(), SLOTS)) {
                val o = array.getJSONObject(i)
                out.add(Slot(o.optString("c", ""), o.optString("l", "")))
            }
            // Tolerate a short or corrupt list rather than crashing the home screen.
            while (out.size < SLOTS) out.add(Slot(""))
            out
        } catch (e: Exception) {
            DEFAULTS
        }
    }

    fun setSlot(context: Context, index: Int, slot: Slot) {
        if (index !in 0 until SLOTS) return
        val current = dock(context).toMutableList()
        current[index] = slot
        save(context, current)
    }

    fun resetDock(context: Context) {
        prefs(context).edit().remove(KEY_DOCK).apply()
    }

    private fun save(context: Context, slots: List<Slot>) {
        val array = JSONArray()
        for (s in slots) {
            array.put(JSONObject().put("c", s.component).put("l", s.label))
        }
        prefs(context).edit().putString(KEY_DOCK, array.toString()).apply()
    }

    // ----------------------------------------------------------------- drive

    fun driveEnabled(context: Context) = prefs(context).getBoolean(KEY_DRIVE_ENABLED, true)

    fun setDriveEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_DRIVE_ENABLED, value).apply()
    }

    fun driveOn(context: Context) = prefs(context).getInt(KEY_DRIVE_ON, DEFAULT_DRIVE_ON)
    fun driveOff(context: Context) = prefs(context).getInt(KEY_DRIVE_OFF, DEFAULT_DRIVE_OFF)

    fun setDriveThresholds(context: Context, on: Int, off: Int) {
        // Guarantee the gap survives whatever is passed in. Equal values would reintroduce
        // exactly the flicker the two thresholds exist to prevent.
        val safeOn = on.coerceIn(5, 200)
        val safeOff = off.coerceIn(0, safeOn - 5)
        prefs(context).edit().putInt(KEY_DRIVE_ON, safeOn).putInt(KEY_DRIVE_OFF, safeOff).apply()
    }

    fun driveDock(context: Context): List<Slot> {
        val raw = prefs(context).getString(KEY_DRIVE_DOCK, null) ?: return DRIVE_DEFAULTS
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<Slot>(DRIVE_SLOTS)
            for (i in 0 until minOf(array.length(), DRIVE_SLOTS)) {
                val o = array.getJSONObject(i)
                out.add(Slot(o.optString("c", ""), o.optString("l", "")))
            }
            while (out.size < DRIVE_SLOTS) out.add(Slot(""))
            out
        } catch (e: Exception) {
            DRIVE_DEFAULTS
        }
    }

    fun setDriveSlot(context: Context, index: Int, slot: Slot) {
        if (index !in 0 until DRIVE_SLOTS) return
        val current = driveDock(context).toMutableList()
        current[index] = slot
        val array = JSONArray()
        for (s in current) array.put(JSONObject().put("c", s.component).put("l", s.label))
        prefs(context).edit().putString(KEY_DRIVE_DOCK, array.toString()).apply()
    }

    // ----------------------------------------------------------------- night

    fun nightMode(context: Context): String =
        prefs(context).getString(KEY_NIGHT, NIGHT_AUTO) ?: NIGHT_AUTO

    fun setNightMode(context: Context, value: String) {
        prefs(context).edit().putString(KEY_NIGHT, value).apply()
    }

    fun nightStart(context: Context) = prefs(context).getInt(KEY_NIGHT_START, DEFAULT_NIGHT_START)
    fun nightEnd(context: Context) = prefs(context).getInt(KEY_NIGHT_END, DEFAULT_NIGHT_END)

    fun nightLevel(context: Context) =
        prefs(context).getInt(KEY_NIGHT_LEVEL, DEFAULT_NIGHT_LEVEL).coerceIn(0, 80)

    fun setNightLevel(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_NIGHT_LEVEL, percent.coerceIn(0, 80)).apply()
    }

    fun idleDimEnabled(context: Context) = prefs(context).getBoolean(KEY_IDLE_DIM, true)

    fun setIdleDimEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_IDLE_DIM, value).apply()
    }

    fun idleMinutes(context: Context) =
        prefs(context).getInt(KEY_IDLE_MINUTES, DEFAULT_IDLE_MINUTES).coerceIn(1, 60)

    fun setIdleMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_IDLE_MINUTES, minutes.coerceIn(1, 60)).apply()
    }

    // ------------------------------------------------------------ icon pack

    fun iconPack(context: Context): String =
        prefs(context).getString(KEY_ICON_PACK, "") ?: ""

    fun setIconPack(context: Context, packageName: String) {
        prefs(context).edit().putString(KEY_ICON_PACK, packageName).apply()
    }

    // ---------------------------------------------------------------- setup

    /**
     * Tracks whether first-run setup has been OFFERED, not whether it was accepted.
     * Gating on the dock existing instead would re-offer setup forever to anyone who chose
     * "Set up manually", since that path deliberately writes nothing.
     */
    fun setupShown(context: Context) = prefs(context).getBoolean(KEY_SETUP_SHOWN, false)

    fun setSetupShown(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SETUP_SHOWN, value).apply()
    }

    // --------------------------------------------------------------- display

    /** -1 follows the unit's own setting, which is the default. */
    fun clockFormat(context: Context): Int =
        prefs(context).getInt(KEY_CLOCK_FORMAT, CLOCK_SYSTEM)

    fun setClockFormat(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_CLOCK_FORMAT, value).apply()
    }

    fun units(context: Context): String =
        prefs(context).getString(KEY_UNITS, UNITS_KMH) ?: UNITS_KMH

    fun setUnits(context: Context, value: String) {
        prefs(context).edit().putString(KEY_UNITS, value).apply()
    }
}
