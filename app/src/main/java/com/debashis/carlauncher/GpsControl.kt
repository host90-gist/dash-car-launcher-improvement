package com.debashis.carlauncher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings

/** On Android 10 only privileged/ADB-authorized apps can directly change location mode. */
object GpsControl {
    fun enabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isLocationEnabled
    }

    fun toggleOrOpenSettings(context: Context) {
        val hasPrivilege = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        if (hasPrivilege) {
            try {
                @Suppress("DEPRECATION")
                Settings.Secure.putInt(
                    context.contentResolver,
                    Settings.Secure.LOCATION_MODE,
                    if (enabled(context)) Settings.Secure.LOCATION_MODE_OFF
                    else Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                )
                return
            } catch (_: SecurityException) { /* Device can still reject the request. */ }
        }
        try {
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { /* Some vendor ROMs omit the activity. */ }
    }
}
