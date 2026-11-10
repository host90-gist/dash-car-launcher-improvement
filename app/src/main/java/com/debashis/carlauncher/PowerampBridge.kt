package com.debashis.carlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/** Direct Poweramp v3 commands via its public broadcast receiver. */
object PowerampBridge {
    const val PACKAGE = "com.maxmpz.audioplayer"
    private const val RECEIVER = "com.maxmpz.audioplayer.player.PowerampAPIReceiver"
    private const val ACTION = "com.maxmpz.audioplayer.API_COMMAND"
    const val TRACK_CHANGED = "com.maxmpz.audioplayer.TRACK_CHANGED"
    const val STATUS_CHANGED = "com.maxmpz.audioplayer.STATUS_CHANGED"
    const val PAUSE = 2
    const val PLAY = 3
    const val NEXT = 4
    const val PREVIOUS = 5

    fun command(context: Context, code: Int): Boolean = try {
        context.sendBroadcast(Intent(ACTION).apply {
            component = ComponentName(PACKAGE, RECEIVER)
            putExtra("cmd", code)
            putExtra("package", context.packageName)
            putExtra("source", "qcardash")
        })
        true
    } catch (e: Exception) {
        Log.w("QCarPoweramp", "Poweramp API command failed: $code", e)
        false
    }
}
