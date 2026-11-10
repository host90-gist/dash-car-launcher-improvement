package com.debashis.carlauncher

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * First-run firmware detection. Not wired into the manifest or into [HomeActivity] yet:
 * someone else owns that integration. To turn this on, add to AndroidManifest.xml:
 *
 * ```xml
 * <activity
 *     android:name=".SetupActivity"
 *     android:exported="false"
 *     android:screenOrientation="landscape"
 *     android:configChanges="orientation|keyboardHidden|screenSize|uiMode"
 *     android:excludeFromRecents="true"
 *     android:theme="@style/DashTheme.Opaque" />
 * ```
 *
 * Launch condition: first run only, before the dock is ever shown. The natural gate is
 * whether [Prefs.KEY_DOCK] is unset (`prefs(context).contains(Prefs.KEY_DOCK) == false`),
 * since that is also exactly the condition under which [Prefs.dock] is currently serving the
 * imotor-shaped [Prefs] defaults rather than something the user chose. [HomeActivity.onCreate]
 * would check that and route here instead of drawing the dock. Once the user taps either
 * button this activity should finish and never be shown again on that install, because a
 * detection screen that reappears after the user already chose "Set up manually" would just
 * be a second, redundant settings screen.
 */
class SetupActivity : Activity() {

    private lateinit var content: LinearLayout
    private lateinit var result: VendorProfiles.Result

    private val roleLabels = mapOf(
        VendorProfiles.Role.PHONE_MIRRORING to "Phone mirroring",
        VendorProfiles.Role.MUSIC to "Music",
        VendorProfiles.Role.RADIO to "Radio",
        VendorProfiles.Role.PHONE to "Phone",
        VendorProfiles.Role.BLUETOOTH to "Bluetooth",
        VendorProfiles.Role.AUX to "AUX / AV-IN",
        VendorProfiles.Role.VIDEO to "Video",
        VendorProfiles.Role.SETTINGS to "Settings"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Detection never throws, but this screen exists precisely for unfamiliar hardware,
        // so guard it anyway rather than trust that promise blindly on first run.
        result = try {
            VendorProfiles.detect(packageManager)
        } catch (e: Exception) {
            VendorProfiles.Result("Generic Android", 0, 0, emptyMap())
        }

        // setContentView before hideSystemBars: the latter reaches through to the DecorView,
        // which does not exist until a content view is set, and the getter throws rather than
        // returning null. This crashed every launch of this screen in v1.2.
        setContentView(buildRoot())
        hideSystemBars()
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    // ---------------------------------------------------------------- layout

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            setPadding(dp(32), dp(28), dp(32), dp(24))
        }

        val heading = TextView(this).apply {
            text = "Set up your dock"
            setTextColor(getColor(R.color.ink))
            textSize = 26f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        root.addView(heading)

        val summary = TextView(this).apply {
            text = summaryLine()
            setTextColor(getColor(R.color.ink_soft))
            textSize = 15f
            setPadding(0, dp(6), 0, dp(18))
        }
        root.addView(summary)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.group_bg)
            clipToOutline = true
        }
        for (role in listOf(
            VendorProfiles.Role.PHONE_MIRRORING, VendorProfiles.Role.MUSIC,
            VendorProfiles.Role.RADIO, VendorProfiles.Role.PHONE,
            VendorProfiles.Role.BLUETOOTH, VendorProfiles.Role.AUX,
            VendorProfiles.Role.VIDEO, VendorProfiles.Role.SETTINGS
        )) {
            content.addView(buildRoleRow(role))
        }
        scroll.addView(content)
        root.addView(scroll)

        root.addView(buildButtons())
        return root
    }

    private fun summaryLine(): String {
        val family = result.family
        return if (result.filled == 0) {
            "$family firmware: no apps matched automatically"
        } else {
            "$family firmware detected, ${result.filled} of ${result.attempted} apps matched"
        }
    }

    private fun buildRoleRow(role: VendorProfiles.Role): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.row_bg)
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }

        val title = TextView(this).apply {
            text = roleLabels[role] ?: role.name
            setTextColor(getColor(R.color.ink))
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(title)

        val matched = result.resolved[role]
        val value = TextView(this).apply {
            textSize = 14f
            if (matched != null) {
                text = matched.substringBefore('/')
                setTextColor(getColor(R.color.muted))
            } else {
                text = "Not matched"
                setTextColor(getColor(R.color.accent))
            }
        }
        row.addView(value)
        return row
    }

    private fun buildButtons(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20), 0, 0)
        }

        val skip = TextView(this).apply {
            text = "Set up manually"
            setTextColor(getColor(R.color.ink_soft))
            textSize = 16f
            gravity = Gravity.CENTER
            minimumHeight = dp(72)
            setBackgroundResource(R.drawable.row_bg)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(12)
            }
            setOnClickListener { finish() }
        }
        row.addView(skip)

        val use = TextView(this).apply {
            text = "Use this"
            setTextColor(getColor(R.color.accent_ink))
            textSize = 16f
            gravity = Gravity.CENTER
            minimumHeight = dp(72)
            setBackgroundColor(getColor(R.color.accent))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                applyDock()
                finish()
            }
        }
        row.addView(use)

        return row
    }

    private fun applyDock() {
        val components = VendorProfiles.toDockComponents(result)
        for (i in components.indices) {
            Prefs.setSlot(this, i, Prefs.Slot(components[i]))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
