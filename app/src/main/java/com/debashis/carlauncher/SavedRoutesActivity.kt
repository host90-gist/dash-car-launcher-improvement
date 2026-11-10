package com.debashis.carlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import kotlin.math.abs

/** Landscape-friendly browsing and management of locally imported routes/POIs. */
class SavedRoutesActivity : Activity() {
    companion object {
        const val EXTRA_ROUTE_FILE = "saved_route_file"
        const val EXTRA_POI_LAT = "saved_poi_lat"
        const val EXTRA_POI_LON = "saved_poi_lon"
        const val EXTRA_POI_TITLE = "saved_poi_title"
    }

    private data class Entry(val key: String, val title: String, val file: File,
        val poi: OfflineNavigationData.Poi? = null)

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var routesTab: TextView
    private lateinit var poiTab: TextView
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private var showRoutes = true
    private var entries: List<Entry> = emptyList()
    private var shown: List<Entry> = emptyList()
    private var lastHold: Runnable? = null
    private var holdFired = false

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun shape(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(8).toFloat()
    }
    private fun caption(label: String, size: Float): TextView = TextView(this).apply {
        text = label
        textSize = size
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    private fun dataDirectory() = File(filesDir, "navigation-data").apply { mkdirs() }
    private fun routes(): List<File> = dataDirectory().listFiles { file ->
        file.isFile && file.extension.equals("gpx", true) &&
            (file.name == "route.gpx" || file.name.startsWith("route-"))
    }?.sortedBy { it.name.lowercase(Locale.US) }.orEmpty()
    private fun poiFiles(): List<File> = dataDirectory().listFiles { file ->
        file.isFile && (file.name.startsWith("poi-") ||
            file.name in listOf("cameras.gpx", "cameras.csv", "destinations.vcf")) &&
            file.extension.lowercase(Locale.US) in listOf("gpx", "csv", "vcf", "txt")
    }?.sortedBy { it.name.lowercase(Locale.US) }.orEmpty()
    private fun displayName(file: File): String =
        file.nameWithoutExtension.removePrefix("route-").removePrefix("poi-")
            .replace('_', ' ').ifBlank { file.nameWithoutExtension }
    private fun readPoiFile(file: File): List<OfflineNavigationData.Poi> =
        when (file.extension.lowercase(Locale.US)) {
            "gpx" -> file.inputStream().use { OfflineNavigationData.parseGpx(it).waypoints }
            "vcf" -> OfflineNavigationData.parseVcards(file)
            else -> OfflineNavigationData.parsePoiCsv(file)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(13, 13, 13))
            setPadding(dp(22), dp(12), dp(22), dp(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(caption("Saved routes", 26f).apply {
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
        }, LinearLayout.LayoutParams(0, dp(60), 1f))
        header.addView(caption("Done", 18f).apply {
            setOnClickListener { finish() }
            background = shape(Color.rgb(65, 65, 65))
        }, LinearLayout.LayoutParams(dp(112), dp(54)))
        base.addView(header)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        routesTab = caption("Routes", 20f)
        poiTab = caption("POIs", 20f)
        tabs.addView(routesTab, LinearLayout.LayoutParams(0, dp(55), 1f))
        tabs.addView(poiTab, LinearLayout.LayoutParams(0, dp(55), 1f))
        base.addView(tabs)
        base.addView(caption("Tap to select. Hold for 2 seconds to move or delete.", 13f)
            .apply { setTextColor(Color.LTGRAY); gravity = Gravity.CENTER_VERTICAL },
            LinearLayout.LayoutParams(-1, dp(38)))
        search = EditText(this).apply {
            hint = "Search saved routes"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setSingleLine(true)
            textSize = 18f
            background = shape(Color.rgb(43, 43, 43))
            setPadding(dp(16), 0, dp(16), 0)
        }
        base.addView(search, LinearLayout.LayoutParams(-1, dp(50)))
        list = ListView(this).apply {
            dividerHeight = dp(1)
            setBackgroundColor(Color.rgb(21, 21, 21))
        }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        list.adapter = adapter
        base.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(base)

        routesTab.setOnClickListener { showRoutes = true; search.setText(""); reload() }
        poiTab.setOnClickListener { showRoutes = false; search.setText(""); reload() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        list.setOnItemClickListener { _, _, position, _ ->
            if (holdFired) { holdFired = false; return@setOnItemClickListener }
            val row = shown.getOrNull(position) ?: return@setOnItemClickListener
            val result = Intent()
            if (row.poi == null) {
                result.putExtra(EXTRA_ROUTE_FILE, row.file.name)
            } else {
                result.putExtra(EXTRA_POI_LAT, row.poi.point.latitude)
                    .putExtra(EXTRA_POI_LON, row.poi.point.longitude)
                    .putExtra(EXTRA_POI_TITLE, row.poi.title)
            }
            setResult(RESULT_OK, result)
            finish()
        }
        var downX = 0f
        var downY = 0f
        list.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastHold?.let { mainHandler.removeCallbacks(it) }
                    holdFired = false
                    downX = event.x
                    downY = event.y
                    val index = list.pointToPosition(event.x.toInt(), event.y.toInt())
                    shown.getOrNull(index)?.let { row ->
                        val action = Runnable {
                            holdFired = true
                            list.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            manage(row)
                        }
                        lastHold = action
                        mainHandler.postDelayed(action, 2000L)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - downX) > dp(14) ||
                        abs(event.y - downY) > dp(14)) cancelHold()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelHold()
            }
            false
        }
        reload()
    }

    private fun cancelHold() {
        lastHold?.let { mainHandler.removeCallbacks(it) }
        lastHold = null
    }

    private fun reload() {
        val all = if (showRoutes) {
            val files = routes()
            val lookup = files.associateBy { it.name }
            SavedNavigationOrder.ordered(this, true, files.map { it.name })
                .mapNotNull { key ->
                    lookup[key]?.let { Entry(key, displayName(it), it) }
                }
        } else {
            val points = ArrayList<Entry>()
            val hiddenPoiKeys = SavedNavigationOrder.hiddenPoiKeys(this)
            for (file in poiFiles().take(40)) {
                try {
                    readPoiFile(file).forEachIndexed { index, poi ->
                        val key = SavedNavigationOrder.pointKey(file.name, index)
                        if (points.size < 12000 &&
                            key !in hiddenPoiKeys) {
                            points.add(Entry(key, poi.title, file, poi))
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Cannot read " + file.name, Toast.LENGTH_SHORT).show()
                }
            }
            val lookup = points.associateBy { it.key }
            SavedNavigationOrder.ordered(this, false, points.map { it.key })
                .mapNotNull { lookup[it] }
        }
        entries = all
        routesTab.text = "Routes (" + routes().size + ")"
        poiTab.text = if (showRoutes) "POIs" else "POIs (" + entries.size + ")"
        routesTab.background = shape(if (showRoutes) Color.rgb(110, 35, 35)
            else Color.rgb(50, 50, 50))
        poiTab.background = shape(if (showRoutes) Color.rgb(50, 50, 50)
            else Color.rgb(110, 35, 35))
        search.hint = if (showRoutes) "Search routes" else "Search POI names"
        filter()
    }

    private fun filter() {
        val term = search.text?.toString()?.trim().orEmpty()
        shown = entries.filter { term.isBlank() ||
            it.title.contains(term, true) || it.file.name.contains(term, true) }
        adapter.clear()
        adapter.addAll(shown.map { row ->
            if (row.poi == null) {
                val active = Prefs.prefs(this).getString("qcar_active_route", "")
                (if (row.file.name == active) "●  " else "   ") + row.title
            } else row.title + "  ·  " + displayName(row.file)
        })
        adapter.notifyDataSetChanged()
    }

    private fun manage(row: Entry) {
        AlertDialog.Builder(this)
            .setTitle(row.title)
            .setItems(arrayOf("Move up", "Move down", "Delete")) { _, index ->
                when (index) {
                    0, 1 -> {
                        if (!SavedNavigationOrder.move(this, showRoutes,
                                entries.map { it.key }, row.key,
                                if (index == 0) -1 else 1)) {
                            Toast.makeText(this, "Already at the list boundary",
                                Toast.LENGTH_SHORT).show()
                        }
                        reload()
                    }
                    2 -> AlertDialog.Builder(this)
                        .setTitle("Delete " + row.title + "?")
                        .setMessage(if (row.poi == null)
                            "Delete this imported GPX route file?"
                            else "Remove this POI from the list and map? " +
                                "The original imported file will be preserved.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ ->
                            if (row.poi == null) {
                                if (!row.file.delete()) {
                                    Toast.makeText(this, "Could not delete route",
                                        Toast.LENGTH_LONG).show()
                                    return@setPositiveButton
                                }
                                SavedNavigationOrder.dropRoute(this,
                                    row.key, entries.map { it.key })
                                if (Prefs.prefs(this).getString("qcar_active_route", "") ==
                                    row.key) Prefs.prefs(this).edit()
                                    .remove("qcar_active_route").apply()
                            } else SavedNavigationOrder.hidePoi(this, row.key)
                            reload()
                        }.show()
                }
            }.show()
    }

    override fun onDestroy() {
        cancelHold()
        super.onDestroy()
    }
}
