package com.debashis.carlauncher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * Read-only access to Yecon/Carocean firmware information.
 *
 * The stock launcher SDK exposes persist.sys.volume_media / volume_gis /
 * volume_bt, persist.sys.backcar_mute_enable, persist.sys.backcar_type,
 * persist.sys.360_auto_show, persist.sys.version and ro.build.chip.type.
 *
 * Writing these properties requires firmware privileges. Never fake a successful
 * change or issue potentially unsafe reverse-camera / MCU commands.
 */
object HeadUnitSettingsBridge {
    const val STOCK_SETTINGS_PACKAGE = "com.carocean.settings"
    private const val TAG = "QCarHeadUnit"

    fun property(key: String): String? {
        if (!key.matches(Regex("[a-zA-Z0-9_.-]{1,100}"))) return null
        return try {
            val process = ProcessBuilder("/system/bin/getprop", key)
                .redirectErrorStream(true).start()
            val done = process.waitFor(650, TimeUnit.MILLISECONDS)
            if (!done) {
                process.destroy()
                return null
            }
            process.inputStream.bufferedReader().use { it.readText().trim() }
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.d(TAG, "System property unavailable: $key", e)
            null
        }
    }

    fun openStockSettings(context: Context): Boolean {
        val pm = context.packageManager
        val pkg = STOCK_SETTINGS_PACKAGE
        return try {
            val target = pm.getLaunchIntentForPackage(pkg)
                ?: Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(pkg)
                    .let { query ->
                        pm.queryIntentActivities(query, 0).firstOrNull()?.activityInfo
                            ?.let { Intent(Intent.ACTION_MAIN).setClassName(it.packageName, it.name) }
                    }
                ?: return false
            context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not open stock settings", e)
            false
        }
    }

    /** Opens an ordinary Android settings screen, never broadcasts a wipe. */
    fun openFactoryResetOptions(context: Context): Boolean = try {
        context.startActivity(Intent(Settings.ACTION_PRIVACY_SETTINGS))
        true
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
            true
        } catch (_: Exception) { false }
    }

    fun deviceInfo(context: Context): String {
        val chip = property("ro.build.chip.type")
            ?: Build.HARDWARE.takeUnless { it.isBlank() || it == "unknown" }
            ?: Build.BOARD
        val totalMemory = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .let { manager -> ActivityManager.MemoryInfo().also(manager::getMemoryInfo).totalMem }
        val totalStorage = StatFs(Environment.getDataDirectory().absolutePath).totalBytes
        val gib = 1024.0 * 1024.0 * 1024.0
        return "$chip / ${ceil(totalMemory / gib).toInt()}GB RAM / ${ceil(totalStorage / gib).toInt()}GB Memory"
    }

    fun firmwareVersion(): String = property("persist.sys.version")
        ?: Build.DISPLAY.ifBlank { "Unavailable" }

    /**
     * MCU version is stored by this family in a serialized
     * ST_SYSTEM_PARAM_INFO record, not a public Android Build property.
     * Do not guess a version from Android's build identifier.
     */
    fun mcuVersion(): String = property("persist.sys.mcu_version")
        ?: "Unavailable to this app — see stock About"
}
