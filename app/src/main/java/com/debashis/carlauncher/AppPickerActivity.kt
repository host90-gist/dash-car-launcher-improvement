package com.debashis.carlauncher

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView

/**
 * Pick one app for a dock slot.
 *
 * Reuses the drawer's grid and item layout rather than inventing a second browsing UI.
 * Adds two options the drawer has no use for: the app drawer itself, and empty.
 */
class AppPickerActivity : Activity() {

    companion object {
        const val EXTRA_SLOT = "slot"
        const val EXTRA_COMPONENT = "component"
        const val EXTRA_LABEL = "label"
    }

    private data class Entry(val label: String, val component: String, val icon: Drawable?)

    private val entries = mutableListOf<Entry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_picker)
        hideSystemBars()

        val slot = intent.getIntExtra(EXTRA_SLOT, 0)
        findViewById<TextView>(R.id.count).text = if (slot < 0) "Choose app" else "Slot ${slot + 1}"
        findViewById<View>(R.id.back).setOnClickListener { finish() }

        loadApps()
        buildGrid()
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

    private fun loadApps() {
        entries.clear()

        // Two slot options that are not apps.
        entries.add(Entry("All apps", Prefs.DRAWER, getDrawable(R.drawable.ic_all_apps)))
        entries.add(Entry("Leave empty", "", null))

        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = packageManager.queryIntentActivities(intent, 0)

        val apps = mutableListOf<Entry>()
        for (info in resolved) {
            val pkg = info.activityInfo.packageName
            if (pkg == packageName) continue
            // Component-level, not package-level: com.imotor.phoneconnect alone exposes
            // CarPlay, Android Auto, AndroidLink and Airplay as separate activities, and
            // picking "the package" would silently land on the wrong one.
            val component = "$pkg/${info.activityInfo.name}"
            apps.add(
                Entry(
                    label = info.loadLabel(packageManager).toString(),
                    component = component,
                    icon = info.loadIcon(packageManager)
                )
            )
        }
        apps.sortBy { it.label.lowercase() }
        entries.addAll(apps)
    }

    private fun buildGrid() {
        val grid = findViewById<GridView>(R.id.grid)
        grid.numColumns = 5
        grid.adapter = Adapter()
        grid.setOnItemClickListener { _, _, position, _ ->
            val entry = entries[position]
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_COMPONENT, entry.component)
                    // Keep the app's own name unless it is one of our two synthetic entries.
                    .putExtra(EXTRA_LABEL, if (entry.component == Prefs.DRAWER) entry.label else "")
            )
            finish()
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView
                ?: LayoutInflater.from(this@AppPickerActivity)
                    .inflate(R.layout.app_item_big, parent, false)

            val entry = entries[position]
            val icon = view.findViewById<ImageView>(R.id.icon)
            icon.setImageDrawable(entry.icon)
            icon.alpha = if (entry.icon == null) 0f else 1f
            view.findViewById<TextView>(R.id.label).text = entry.label
            return view
        }
    }
}
