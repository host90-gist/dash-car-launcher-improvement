package com.debashis.carlauncher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import java.util.concurrent.TimeUnit
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream
import com.carocean.navicar.Navi
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

    /**
     * Stock Yecon launcher Function.onSettings() opens this EXACT activity.
     * getLaunchIntentForPackage() on this firmware resolves to AV-in/Front
     * View, so NEVER use the package's generic launcher entry point here.
     */
    fun openStockSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                setClassName(STOCK_SETTINGS_PACKAGE, "com.carocean.settings.CustomSettingActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Stock CustomSettingActivity not accessible", e)
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
    /** Stock firmware stores both fields in one Java-serialized provider record. */
    private fun systemParameters(context: Context): Navi.Status.SystemParamInfo? {
        return try {
            val uri = Uri.parse(
                "content://com.carocean.status.provider/status/ST_SYSTEM_PARAM_INFO"
            )
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && cursor.columnCount > 1) {
                    val blob = cursor.getBlob(1) ?: return null
                    if (blob.size < 16 || blob.size > 1048576) return null
                    ObjectInputStream(ByteArrayInputStream(blob)).use { reader ->
                        reader.readObject() as? Navi.Status.SystemParamInfo
                    }
                } else null
            }
        } catch (error: Exception) {
            Log.d(TAG, "Stock MCU info provider not accessible", error)
            null
        }
    }

    fun mcuVersion(context: Context): String =
        systemParameters(context)?.mcu_version?.takeIf {
            it.isNotBlank() && it != "MCU-VERSION"
        } ?: property("persist.sys.mcu_version")
            ?: "Unavailable — check stock About"

    fun stockSystemVersion(context: Context): String =
        systemParameters(context)?.system_version?.takeIf {
            it.isNotBlank() && it != "android 9.0"
        } ?: firmwareVersion()
}
