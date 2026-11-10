package com.debashis.carlauncher

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * All apps.
 *
 * Paged left to right, ten big icons per page, flick to change page.
 * Disabled packages are not listed at all.
 *
 * Icons are loaded per page rather than all at once. Pages are real view hierarchies, not a
 * recycling adapter, so decoding every icon up front meant every installed app's bitmap sat
 * resident for the life of the process whether you opened the drawer or not.
 */
class DrawerActivity : Activity() {

    /** [icon] is deliberately not held here. It is attached and detached as pages come and go. */
    private data class Entry(val label: String, val pkg: String, val info: ResolveInfo)

    private companion object {
        const val COLUMNS = 5
        const val ROWS = 2
        const val PER_PAGE = COLUMNS * ROWS
        const val SIDE_PADDING_DP = 40

        /** Keep the current page plus one either side decoded, so a flick is never blank. */
        const val NEIGHBOURS = 1
    }

    private val entries = mutableListOf<Entry>()
    private val dots = mutableListOf<View>()

    /** One list of icon views per page, index-aligned with [entries] chunks. */
    private val pageIcons = mutableListOf<List<ImageView>>()
    private var loadedPage = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_drawer)
        hideSystemBars()

        findViewById<View>(R.id.back).setOnClickListener { finish() }

        loadApps()
        findViewById<TextView>(R.id.count).text = "${entries.size} apps"
        buildPages()
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
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        // Default flags exclude disabled components, which is what we want: a debloated
        // package should simply not appear here.
        val resolved: List<ResolveInfo> = packageManager.queryIntentActivities(intent, 0)

        val seen = HashSet<String>()
        for (info in resolved) {
            val pkg = info.activityInfo.packageName
            if (pkg == packageName) continue      // do not list ourselves
            if (!seen.add(pkg)) continue          // one row per package
            entries.add(
                Entry(
                    label = info.loadLabel(packageManager).toString(),
                    pkg = pkg,
                    info = info
                )
            )
        }
        entries.sortBy { it.label.lowercase() }
    }

    private fun buildPages() {
        val pager = findViewById<PagerScrollView>(R.id.pager)
        val pages = findViewById<LinearLayout>(R.id.pages)
        val dotBar = findViewById<LinearLayout>(R.id.dots)
        val inflater = LayoutInflater.from(this)

        pages.removeAllViews()
        dotBar.removeAllViews()
        dots.clear()
        pageIcons.clear()
        loadedPage = -1

        val pageWidth = resources.displayMetrics.widthPixels
        val sidePadding = dp(SIDE_PADDING_DP)
        val cellWidth = (pageWidth - sidePadding * 2) / COLUMNS

        val chunks = entries.chunked(PER_PAGE)
        for (chunk in chunks) {
            val grid = GridLayout(this).apply {
                columnCount = COLUMNS
                rowCount = ROWS
                setPadding(sidePadding, dp(12), sidePadding, dp(12))
                layoutParams = LinearLayout.LayoutParams(pageWidth, LinearLayout.LayoutParams.MATCH_PARENT)
            }

            val icons = ArrayList<ImageView>(chunk.size)
            for ((index, entry) in chunk.withIndex()) {
                val view = inflater.inflate(R.layout.app_item_big, grid, false)
                // No icon set here on purpose. See loadIconsAround.
                view.findViewById<TextView>(R.id.label).text = entry.label
                view.setOnClickListener {
                    packageManager.getLaunchIntentForPackage(entry.pkg)?.let { startActivity(it) }
                }
                icons.add(view.findViewById(R.id.icon))

                val params = GridLayout.LayoutParams(
                    GridLayout.spec(index / COLUMNS, 1f),
                    GridLayout.spec(index % COLUMNS, 1f)
                ).apply {
                    width = cellWidth
                    height = 0                 // 0 with a row weight means share the height
                }
                grid.addView(view, params)
            }
            pageIcons.add(icons)
            pages.addView(grid)
        }

        pager.pageCount = chunks.size.coerceAtLeast(1)
        pager.onPageChanged = { page ->
            setActiveDot(page)
            loadIconsAround(page)
        }

        if (chunks.size > 1) {
            for (i in chunks.indices) {
                val dot = View(this)
                val params = LinearLayout.LayoutParams(dp(10), dp(10))
                params.marginStart = dp(6)
                params.marginEnd = dp(6)
                dot.layoutParams = params
                dot.setBackgroundResource(if (i == 0) R.drawable.dot_active else R.drawable.dot)
                dotBar.addView(dot)
                dots.add(dot)
            }
            dotBar.gravity = Gravity.CENTER
        }

        loadIconsAround(0)
    }

    /**
     * Decode icons for [page] and its neighbours, and drop everything else.
     *
     * Dropping matters as much as loading: without it this becomes the same
     * everything-resident behaviour with extra steps.
     */
    private fun loadIconsAround(page: Int) {
        if (page == loadedPage) return
        loadedPage = page

        for (p in pageIcons.indices) {
            val wanted = p in (page - NEIGHBOURS)..(page + NEIGHBOURS)
            val icons = pageIcons[p]
            val offset = p * PER_PAGE

            for ((i, view) in icons.withIndex()) {
                if (wanted) {
                    if (view.drawable == null) {
                        val entry = entries.getOrNull(offset + i) ?: continue
                        val component =
                            "${entry.info.activityInfo.packageName}/${entry.info.activityInfo.name}"
                        val themed = IconPacks.drawableFor(component)
                        view.setImageDrawable(themed ?: entry.info.loadIcon(packageManager))
                    }
                } else if (view.drawable != null) {
                    view.setImageDrawable(null)
                }
            }
        }
    }

    private fun setActiveDot(page: Int) {
        for ((i, dot) in dots.withIndex()) {
            dot.setBackgroundResource(if (i == page) R.drawable.dot_active else R.drawable.dot)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        // Apps may have been installed, enabled or disabled while we were away.
        val before = entries.map { it.pkg }
        loadApps()
        if (entries.map { it.pkg } != before) {
            findViewById<TextView>(R.id.count).text = "${entries.size} apps"
            buildPages()
        }
    }

    override fun onStop() {
        super.onStop()
        // Nothing should hold app icons while the drawer is not on screen.
        for (icons in pageIcons) for (view in icons) view.setImageDrawable(null)
        loadedPage = -1
    }
}
