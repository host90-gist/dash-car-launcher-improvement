package com.debashis.carlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Lets the user pick an icon pack, or "None" to keep each app's own icon.
 *
 * Not registered in the manifest yet. To wire it in, add this inside <application> in
 * AndroidManifest.xml, matching the other internal picker/settings screens:
 *
 * ```
 * <activity
 *     android:name=".IconPackPickerActivity"
 *     android:exported="false"
 *     android:screenOrientation="landscape"
 *     android:configChanges="orientation|keyboardHidden|screenSize|uiMode"
 *     android:excludeFromRecents="true"
 *     android:theme="@style/DashTheme.Opaque" />
 * ```
 *
 * Launch with startActivityForResult and read [EXTRA_PACKAGE] from the result Intent on
 * RESULT_OK. An empty string means "None". This screen only returns a choice: it does not
 * touch Prefs and it does not call IconPacks.load() itself, both left to the integrator, since
 * load() has to run off the main thread and where the choice is persisted is a Prefs decision
 * this file was told not to make.
 */
class IconPackPickerActivity : Activity() {

    companion object {
        const val EXTRA_PACKAGE = "icon_pack_package"

        /** Used in a car, tapped while parked. See SettingsActivity for the same rule. */
        private const val ROW_HEIGHT_DP = 72
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // setContentView first: hideSystemBars reaches through to the DecorView, which does
        // not exist until a content view is set, and the getter throws rather than returning
        // null. The XML-layout screens already order it this way.
        setContentView(buildRoot())
        hideSystemBars()
    }

    // Copied verbatim from SettingsActivity so every internal screen hides chrome the same way.
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

    // ---------------------------------------------------------------- layout, built in code

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // installed() only queries PackageManager and loads a handful of launcher icons, the
        // same main-thread cost DrawerActivity and AppPickerActivity already pay for the full
        // app list. It does not touch any pack's appfilter.xml, so it is fine here.
        val packs = IconPacks.installed(packageManager)

        root.addView(buildHeader(packs.size))
        root.addView(if (packs.isEmpty()) buildEmptyState() else buildList(packs))
        return root
    }

    private fun buildHeader(packCount: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(88)
            )
            setPadding(dp(40), 0, dp(40), 0)

            addView(TextView(this@IconPackPickerActivity).apply {
                text = "Cancel"
                setTextColor(getColor(R.color.ink_soft))
                textSize = 20f
                isClickable = true
                isFocusable = true
                setPadding(0, 0, dp(24), 0)
                setOnClickListener {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            })

            addView(TextView(this@IconPackPickerActivity).apply {
                text = "Icon pack"
                setTextColor(getColor(R.color.ink))
                textSize = 24f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })

            addView(TextView(this@IconPackPickerActivity).apply {
                text = if (packCount == 1) "1 pack" else "$packCount packs"
                setTextColor(getColor(R.color.muted))
                textSize = 15f
            })
        }
    }

    private fun buildList(packs: List<IconPacks.Pack>): View {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.group_bg)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // "None" always sits first and outside the alphabetical pack order: it is how you
        // undo picking a pack, and burying it below however many packs happen to be
        // installed would make that the one option nobody can find.
        group.addView(buildRow(null, "None", "Use each app's own icon") {
            finishWith("")
        })

        for (pack in packs) {
            group.addView(buildRow(pack.icon, pack.label, pack.packageName) {
                finishWith(pack.packageName)
            })
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isFillViewport = true
        }
        val padded = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(20))
            addView(group)
        }
        scroll.addView(padded)
        return scroll
    }

    private fun buildEmptyState(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setPadding(dp(60), dp(40), dp(60), dp(40))

            addView(TextView(this@IconPackPickerActivity).apply {
                text = "No icon packs installed"
                setTextColor(getColor(R.color.ink))
                textSize = 20f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@IconPackPickerActivity).apply {
                text = "Icon packs are installed from the Play Store like any other app. Any " +
                    "pack made for Nova Launcher or ADW Launcher works here too, since they " +
                    "all share the same appfilter format."
                setTextColor(getColor(R.color.muted))
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            })
        }
    }

    private fun buildRow(
        icon: Drawable?,
        title: String,
        subtitle: String,
        onPick: () -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ROW_HEIGHT_DP)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setBackgroundResource(R.drawable.row_bg)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onPick() }
        }

        row.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                marginEnd = dp(16)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(icon)
            contentDescription = null
        })

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.ink))
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (subtitle.isNotEmpty()) {
            textColumn.addView(TextView(this).apply {
                text = subtitle
                setTextColor(getColor(R.color.muted))
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        row.addView(textColumn)

        return row
    }

    private fun finishWith(packageName: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PACKAGE, packageName))
        finish()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
