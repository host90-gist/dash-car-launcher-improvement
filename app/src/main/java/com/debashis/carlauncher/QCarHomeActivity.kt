package com.debashis.carlauncher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.util.Log
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.layer.overlay.FixedPixelCircle
import org.mapsforge.core.graphics.Style
import android.provider.OpenableColumns
import android.database.Cursor
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import java.io.File
import android.media.MediaMetadata
import android.app.NotificationManager
import android.os.Build
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import org.mapsforge.map.layer.overlay.Polyline
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.EditText
import android.widget.ListView
import android.widget.ArrayAdapter
import java.text.SimpleDateFormat
import java.io.FileOutputStream
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.pow

/**
 * QCar-inspired dashboard implemented with framework Views.
 *
 * Based on the layout structure and color/spacing measurements of QCarLauncherPreview:
 * a narrow left navigation rail; a large navigation panel; vehicle, AdBlue,
 * outside temperature and clock along the top right; music below.
 *
 * No Compose, map SDK, animated transitions, drawing loops or third-party libraries.
 * Unavailable CAN data is always shown as unavailable, not made up.
 */
class QCarHomeActivity : Activity() {
    private val bg = Color.rgb(0, 0, 0)
    private val cardColor = Color.argb(230, 14, 14, 14)
    private val accent = Color.rgb(177, 46, 46) // QCarLauncher #b12e2e
    private val white = Color.WHITE
    private val muted = Color.rgb(174, 174, 174)
    private val handler = Handler(Looper.getMainLooper())
    private var speedView: TextView? = null
    private var clockView: TextView? = null
    private var dateView: TextView? = null
    private var mediaTitle: TextView? = null
    private var mediaArtist: TextView? = null
    private var mediaArt: ImageView? = null
    private var mediaPlay: TextView? = null
    private var mediaProgress: ProgressBar? = null
    private var lastAlbumArt: Bitmap? = null
    private var savedAlbumArtLoaded = false
    private var lastSavedArtwork: Bitmap? = null
    private var mapView: MapView? = null
    private var mapCache: TileCache? = null
    private var mapData: MultiMapDataStore? = null
    private var loadedMapCount = 0
    private var locationDot: FixedPixelCircle? = null
    private var locationHalo: FixedPixelCircle? = null
    private var locationRim: FixedPixelCircle? = null
    private var mapStatus: TextView? = null
    private var lastGpsLocation: Location? = null
    private var lastCameraLocation: Location? = null
    private var followPosition = true
    private val routeSegments = ArrayList<List<LatLong>>()
    private val loadedPoiSources = ArrayList<String>()
    private val poiData = ArrayList<OfflineNavigationData.Poi>()
    private val overlayLayers = ArrayList<org.mapsforge.map.layer.Layer>()
    private var lastPoiAlertAt = 0L
    private var lastPoiAlertKey = ""
    private var autoZoomLastAt = 0L
    private var splitLastRatio = 0.35f
    private var mapPane: FrameLayout? = null
    private var tilesPane: LinearLayout? = null
    private var dataImportPending = false
    private var mapResizeRedraw: Runnable? = null
    private var mapFilePending = false
    private var importedMapsStatus: String? = null
    private var lastLocationAt = 0L
    private var lastSpeedKmh = 0f
    private var manager: LocationManager? = null
    private var sessionManager: MediaSessionManager? = null
    private var mediaController: MediaController? = null
    private var sessionRegistered = false
    private var powerampTitle: String? = null
    private var powerampArtist: String? = null
    private var powerampPaused: Boolean? = null
    private var powerampEventsRegistered = false

    /** Poweramp v3 broadcasts include metadata without a notification-listener grant. */
    private val powerampEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PowerampBridge.TRACK_CHANGED -> {
                    val track = intent.getBundleExtra("track")
                    if (track != null) {
                        powerampTitle = track.getString("title")
                        powerampArtist = track.getString("artist")
                        renderMedia()
                    }
                }
                PowerampBridge.STATUS_CHANGED -> {
                    powerampPaused = intent.getBooleanExtra("paused", true)
                    renderMedia()
                }
            }
        }
    }


    private val locationListener = LocationListener { location: Location ->
        lastLocationAt = SystemClock.elapsedRealtime()
        lastSpeedKmh = if (location.speed < 0.833f) 0f else location.speed * 3.6f
        lastGpsLocation = location
        updateMapLocation(location)
        updateSpeedZoom(location)
        alertNearbyCameras(location)
        renderLocation()
    }

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { selectSession(it) }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = renderMedia()
        override fun onPlaybackStateChanged(state: PlaybackState?) = renderMedia()
        override fun onSessionDestroyed() {
            mediaController = null
            renderMedia()
        }
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = renderTime()
    }

    private val tick = object : Runnable {
        override fun run() {
            renderTime()
            renderLocation()
            renderMediaProgress()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveMode()
        window.setWindowAnimations(0)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.setBackgroundDrawableResource(android.R.color.black)
        manager = getSystemService(LOCATION_SERVICE) as? LocationManager
        AndroidGraphicFactory.createInstance(application)
        setContentView(buildDashboard())
        renderTime()
        renderLocation()
        renderMedia()
        mediaArtist?.setOnClickListener {
            if (!notificationAccessGranted()) openNotificationAccess() else openPoweramp()
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 109)
    }

    /**
     * Request normal Android immersive mode. An edge swipe temporarily reveals
     * Android system bars; launcher focus/resume restores the fullscreen state.
     * Some head-unit vendors draw a separate, privileged bar above applications;
     * standard Android APIs cannot forcibly dismiss that system overlay.
     */
    private fun applyImmersiveMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= 30) {
            window.decorView.windowInsetsController?.apply {
                systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).roundToInt()

    private fun rounded(color: Int, radius: Int = 6): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun text(value: String, size: Float, color: Int = white, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            includeFontPadding = false
            if (bold) typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
            maxLines = 2
        }

    private fun lp(w: Int = -1, h: Int = -1) = LinearLayout.LayoutParams(
        if (w < 0) w else dp(w), if (h < 0) h else dp(h)
    )
    private fun flp(w: Int = -1, h: Int = -1, gravity: Int = Gravity.TOP) =
        FrameLayout.LayoutParams(if (w < 0) w else dp(w), if (h < 0) h else dp(h), gravity)

    private fun weighted(weight: Float) = LinearLayout.LayoutParams(0, -1, weight)
    private fun weightedHeight(weight: Float) = LinearLayout.LayoutParams(-1, 0, weight)

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
    }
    private fun card(): FrameLayout = FrameLayout(this).apply {
        background = rounded(cardColor)
        clipToOutline = true
    }

    private fun inset(view: View, width: Int = -1, height: Int = -1, weight: Float? = null): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            if (width == -1) -1 else if (width == 0) 0 else dp(width),
            if (height == -1) -1 else if (height == 0) 0 else dp(height),
            weight ?: 0f
        ).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }

    private fun buildDashboard(): View {
        val outer = row().apply { setBackgroundColor(bg); gravity = Gravity.TOP }
        val sidebar = column().apply {
            setBackgroundColor(Color.rgb(7, 7, 7))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(10), dp(4), dp(9))
        }
        outer.addView(sidebar, lp(72, -1))
        rail(sidebar, "⌂", "HOME") { /* Already home */ }
        rail(sidebar, "▦", "APPS") { launchNoAnimation(Intent(this, DrawerActivity::class.java)) }
        rail(sidebar, "⌖", "NAVI") { openPreferredNavigation() }
        rail(sidebar, "▣", "OEM") { openOemScreen() }
        rail(sidebar, "⚙", "SET") { launchNoAnimation(Intent(this, SettingsActivity::class.java)) }

        val main = column().apply { gravity = Gravity.TOP; setBackgroundColor(bg) }
        outer.addView(main, LinearLayout.LayoutParams(0, -1, 1f))

        val dashboard = row().apply { gravity = Gravity.TOP }
        main.addView(dashboard, LinearLayout.LayoutParams(-1, 0, 1f))
        val ratio = Prefs.prefs(this).getFloat("qcar_map_split_ratio", 0.35f)
            .coerceIn(0.20f, 0.75f)
        splitLastRatio = ratio
        val navigation = card()
        mapPane = navigation
        dashboard.addView(navigation, inset(navigation, 0, -1, ratio))
        // Drag the handle to resize map and tiles, without obscuring the dock.
        val divider = View(this).apply {
            background = rounded(Color.rgb(85, 85, 85), 3)
            contentDescription = "Drag to resize map and tiles"
            setOnTouchListener(object : View.OnTouchListener {
                private var startX = 0f
                private var startRatio = ratio
                override fun onTouch(view: View, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = event.rawX
                            startRatio = splitLastRatio
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val available = dashboard.width.toFloat()
                            if (available > 10f) {
                                setMapSplitRatio(
                                    startRatio + (event.rawX - startX) / available, false)
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            setMapSplitRatio(splitLastRatio, true)
                            return true
                        }
                    }
                    return false
                }
            })
        }
        dashboard.addView(divider, lp(12, -1))
        val right = column().apply { gravity = Gravity.TOP }
        tilesPane = right
        dashboard.addView(right, LinearLayout.LayoutParams(0, -1, 1f - ratio))
        buildNavigation(navigation)
        val upper = row().apply { gravity = Gravity.TOP }
        right.addView(upper, weightedHeight(0.36f))
        addVehicle(upper)
        addAdBlue(upper)
        addTemperature(upper)
        addClock(upper)

        val musicCard = card()
        right.addView(musicCard, inset(musicCard, -1, 0, 0.64f))
        buildMusic(musicCard)
        return outer
    }

    private fun setMapSplitRatio(value: Float, save: Boolean) {
        val safe = value.coerceIn(0.20f, 0.75f)
        splitLastRatio = safe
        (mapPane?.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.weight = safe
            mapPane?.layoutParams = it
        }
        (tilesPane?.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.weight = 1f - safe
            tilesPane?.layoutParams = it
        }
        if (save) {
            Prefs.prefs(this).edit()
                .putFloat("qcar_map_split_ratio", safe).apply()
            scheduleMapRedraw()
        }
    }

    private fun scheduleMapRedraw() {
        mapResizeRedraw?.let { handler.removeCallbacks(it) }
        val redraw = Runnable {
            val map = mapView ?: return@Runnable
            if (map.width <= 0 || map.height <= 0) return@Runnable
            map.layerManager.redrawLayers()
            map.repaint()
        }
        mapResizeRedraw = redraw
        handler.postDelayed(redraw, 180L)
    }

    private fun rail(parent: LinearLayout, symbol: String, label: String, onClick: () -> Unit) {
        val item = column().apply {
            background = rounded(if (label == "HOME") Color.rgb(45, 18, 18) else Color.TRANSPARENT)
            setOnClickListener { onClick() }
        }
        val showLabels = Prefs.dockLabelsVisible(this)
        item.contentDescription = when (label) {
            "SET" -> "Settings"
            "NAVI" -> "Navigation"
            else -> label
        }
        val sym = text(symbol, if (showLabels) 26f else 32.5f,
            if (label == "HOME") accent else white).apply {
            gravity = Gravity.CENTER
        }
        item.addView(sym, lp(58, if (showLabels) 43 else 61))
        if (showLabels) item.addView(text(label, 10f, muted, true), lp(58, 18))
        parent.addView(item, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            setMargins(0, dp(5), 0, dp(5))
        })
    }

    /**
     * Offline interactive OpenStreetMap panel powered by Mapsforge.
     * No Google SDK, credentials, Play services, network permissions or
     * recurring tile downloads. User selects a compatible .map file once.
     */
    private fun buildNavigation(parent: FrameLayout) {
        val view = MapView(this)
        mapView = view
        view.setBuiltInZoomControls(false)
        parent.addView(view, flp())
        // Force the Mapsforge framebuffer to refresh after a layout resize.
        view.addOnLayoutChangeListener { _, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft ||
                bottom - top != oldBottom - oldTop) scheduleMapRedraw()
        }
        migrateOldMap()
        loadOfflineMaps()

        val recenter = mapControl("⊙") { centerMapOnMyLocation() }
        parent.addView(recenter, flp(49, 49, Gravity.TOP or Gravity.RIGHT).apply {
            rightMargin = dp(12)
            topMargin = dp(12)
        })

        val zoom = column()
        zoom.addView(mapControl("+") {
            mapView?.let { map -> map.setZoomLevel((map.model.mapViewPosition.zoomLevel.toInt() + 1).coerceAtMost(20).toByte()) }
        }, lp(49, 49))
        zoom.addView(mapControl("−") {
            mapView?.let { map -> map.setZoomLevel((map.model.mapViewPosition.zoomLevel.toInt() - 1).coerceAtLeast(2).toByte()) }
        }, lp(49, 49))
        parent.addView(zoom, flp(51, 105, Gravity.RIGHT or Gravity.BOTTOM).apply {
            rightMargin = dp(12)
            bottomMargin = dp(12)
        })

        val attribution = text("© OpenStreetMap contributors", 9f, muted).apply {
            background = rounded(Color.argb(215, 15, 15, 15), 3)
        }
        parent.addView(attribution, flp(170, 23, Gravity.LEFT or Gravity.BOTTOM).apply {
            leftMargin = dp(8)
            bottomMargin = dp(8)
        })

        val bubble = column().apply {
            setPadding(dp(5), dp(4), dp(5), dp(4))
            background = rounded(Color.argb(230, 0, 0, 0), 25)
        }
        speedView = text("--", 23f, white, true)
        bubble.addView(speedView, lp(-1, 34))
        bubble.addView(text("km/h", 10f, muted), lp(-1, 15))
        parent.addView(bubble, flp(61, 61, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
            bottomMargin = dp(12)
        })

        // Collapsed menu keeps map controls compact while still accessible.
        val mapMenu = column().apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        val menuItems = column().apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            visibility = View.GONE
            background = rounded(Color.argb(245, 12, 12, 12), 7)
            setPadding(dp(3), dp(4), dp(3), dp(4))
        }
        val menuButton = mapControl("☰") {
            menuItems.visibility =
                if (menuItems.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        menuButton.textSize = 31f
        menuButton.contentDescription = "Open map options"
        mapMenu.addView(menuButton, lp(60, 53))
        fun addMenuChoice(name: String, description: String, action: () -> Unit) {
            val button = mapControl(name) {
                menuItems.visibility = View.GONE
                action()
            }
            button.textSize = 16.25f
            button.contentDescription = description
            menuItems.addView(button, LinearLayout.LayoutParams(dp(113), dp(54)).apply {
                topMargin = dp(3)
            })
        }
        addMenuChoice("MAP", "Import offline country map") { chooseOfflineMap() }
        addMenuChoice("GPX", "Import GPX route or track") { chooseDataImport(311) }
        addMenuChoice("SAVED", "Open Saved routes and POIs") {
            startActivityForResult(Intent(this, SavedRoutesActivity::class.java), 313)
        }
        addMenuChoice("POI", "Import GPX, CSV or VCF points") { chooseDataImport(312) }
        addMenuChoice("AUTO", "Toggle speed-based zoom") {
            val next = !Prefs.prefs(this).getBoolean("qcar_speed_zoom", false)
            Prefs.prefs(this).edit().putBoolean("qcar_speed_zoom", next).apply()
            android.widget.Toast.makeText(this,
                "Speed-based zoom " + if (next) "on" else "off",
                android.widget.Toast.LENGTH_SHORT).show()
            if (next) lastGpsLocation?.let { updateSpeedZoom(it, true) }
        }
        mapMenu.addView(menuItems, lp(-2, -2))
        parent.addView(mapMenu, flp(-2, -2, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = dp(12)
            topMargin = dp(12)
        })
        restoreNavigationOverlays()
    }

    private fun mapControl(label: String, click: () -> Unit): TextView =
        text(label, 28.75f, white, true).apply {
            background = rounded(Color.argb(235, 21, 21, 21), 5)
            setOnClickListener { click() }
            contentDescription = when (label) {
                "MAP" -> "Import additional offline OpenStreetMap maps"
                "GPX" -> "Import GPX track or route"
                "SAVED" -> "Open Saved routes library"
                "POI" -> "Import GPX waypoints, CSV camera locations or vCard GEO destinations"
                "AUTO" -> "Toggle automatic speed-based zoom"
                "⊙" -> "Recenter map on my location"
                "+" -> "Zoom in"
                else -> "Zoom out"
            }
        }

    private fun navDataFolder(): File = File(filesDir, "navigation-data").apply { mkdirs() }

    private fun chooseDataImport(request: Int) {
        if (dataImportPending || mapFilePending) return
        try {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, request)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this,
                "File manager unavailable", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Import a route or camera database to app-private storage; keep across updates. */
    private fun importNavigationData(uri: Uri, code: Int) {
        dataImportPending = true
        showMapStatus(if (code == 311) "Importing GPX route..." else "Importing POIs...")
        Thread {
            var error: String? = null
            var confirmation = ""
            val folder = navDataFolder()
            val temporary = File.createTempFile("nav-import", ".tmp", folder)
            try {
                val name = try {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
                        null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                } catch (_: Exception) { null } ?: uri.lastPathSegment.orEmpty()
                val isGpx = name.endsWith(".gpx", true)
                val isCsv = name.endsWith(".csv", true) ||
                    name.endsWith(".txt", true)
                val isVcf = name.endsWith(".vcf", true)
                if (code == 311 && !isGpx)
                    throw IllegalArgumentException("Choose a .gpx route file")
                if (code == 312 && !isGpx && !isCsv && !isVcf)
                    throw IllegalArgumentException("Choose a .gpx, .csv or .vcf POI file")
                contentResolver.openInputStream(uri)?.use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var copied = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            copied += n
                            if (copied > 80L * 1024L * 1024L)
                                throw IllegalArgumentException("Maximum import size is 80 MB")
                            output.write(buffer, 0, n)
                        }
                    }
                } ?: throw java.io.IOException("File not readable")
                if (code == 311) {
                    val parsed = temporary.inputStream().use {
                        OfflineNavigationData.parseGpx(it)
                    }
                    if (parsed.pointCount < 2)
                        throw IllegalArgumentException("GPX has no track or route line")
                    confirmation = "GPX route loaded: " + parsed.pointCount + " points"
                } else {
                    val points = if (isGpx)
                        temporary.inputStream().use { OfflineNavigationData.parseGpx(it).waypoints }
                    else if (isVcf) OfflineNavigationData.parseVcards(temporary)
                    else OfflineNavigationData.parsePoiCsv(temporary)
                    if (points.isEmpty()) throw IllegalArgumentException(
                        "No GPS waypoints / camera points found")
                    confirmation = "POIs loaded: " + points.size
                }
                // Preserve every imported file. Legacy files from older builds
                // remain usable. A unique filename prevents silent replacement.
                val safe = name.substringBeforeLast('.').replace(
                    Regex("[^a-zA-Z0-9._-]"), "_"
                ).take(65).trim('.').ifBlank { "imported" }
                val extension = if (isGpx) "gpx" else if (isVcf) "vcf" else "csv"
                val prefix = if (code == 311) "route-" else "poi-"
                var destination = File(folder, prefix + safe + "." + extension)
                var suffix = 2
                while (destination.exists()) {
                    destination = File(folder, prefix + safe + "-" + suffix++ + "." + extension)
                }
                temporary.copyTo(destination)
                if (code == 311) {
                    Prefs.prefs(this).edit().putString(
                        "qcar_active_route", destination.name
                    ).apply()
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                Log.e("QCarOfflineNav", "Navigation import failed", e)
            } finally {
                temporary.delete()
            }
            val failed = error
            val message = confirmation
            runOnUiThread {
                dataImportPending = false
                if (failed != null) {
                    showMapStatus("Import error: " + failed)
                } else {
                    restoreNavigationOverlays()
                    if (loadedMapCount > 0) mapStatus?.visibility = View.GONE
                    android.widget.Toast.makeText(
                        this, message, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun savedRouteFiles(): List<File> =
        navDataFolder().listFiles { file ->
            file.isFile && file.extension.equals("gpx", true) &&
                (file.name == "route.gpx" || file.name.startsWith("route-"))
        }?.sortedBy { it.name.lowercase(Locale.US) }.orEmpty()

    private fun savedPoiFiles(): List<File> =
        navDataFolder().listFiles { file ->
            file.isFile && (file.name.startsWith("poi-") ||
                file.name in listOf("cameras.gpx", "cameras.csv", "destinations.vcf")) &&
                file.extension.lowercase(Locale.US) in listOf("gpx", "csv", "txt", "vcf")
        }?.sortedBy { it.name.lowercase(Locale.US) }.orEmpty()

    private fun labelOfNavigationFile(file: File): String =
        file.nameWithoutExtension.removePrefix("route-").removePrefix("poi-")
            .replace('_', ' ').ifBlank { file.nameWithoutExtension }

    private fun readPoiFile(file: File): List<OfflineNavigationData.Poi> =
        when (file.extension.lowercase(Locale.US)) {
            "gpx" -> file.inputStream().use { OfflineNavigationData.parseGpx(it).waypoints }
            "vcf" -> OfflineNavigationData.parseVcards(file)
            else -> OfflineNavigationData.parsePoiCsv(file)
        }

    private fun restoreNavigationOverlays() {
        routeSegments.clear()
        poiData.clear()
        loadedPoiSources.clear()
        val routeFiles = savedRouteFiles()
        val byName = routeFiles.associateBy { it.name }
        val ordered = SavedNavigationOrder.ordered(
            this, true, routeFiles.map { it.name }
        )
        val selected = Prefs.prefs(this).getString("qcar_active_route", null)
        val activeFile = routeFiles.firstOrNull { it.name == selected }
            ?: ordered.firstOrNull()?.let { byName[it] }
        if (activeFile != null) {
            try {
                routeSegments.addAll(activeFile.inputStream().use {
                    OfflineNavigationData.parseGpx(it).segments
                })
            } catch (e: Exception) {
                Log.w("QCarOfflineNav", "Could not load route " + activeFile.name, e)
            }
        }
        // Respect POI removals and ordering without modifying the source files.
        val savedPoints = ArrayList<Pair<String, OfflineNavigationData.Poi>>()
        val hiddenPoiKeys = SavedNavigationOrder.hiddenPoiKeys(this)
        for (file in savedPoiFiles().take(40)) {
            try {
                readPoiFile(file).forEachIndexed { index, poi ->
                    val key = SavedNavigationOrder.pointKey(file.name, index)
                    if (savedPoints.size < 12000 &&
                        key !in hiddenPoiKeys) {
                        savedPoints.add(key to poi)
                    }
                }
                loadedPoiSources.add(file.name)
                if (savedPoints.size >= 12000) break
            } catch (e: Exception) {
                Log.w("QCarOfflineNav", "Could not load POIs " + file.name, e)
            }
        }
        val orderedPoints = SavedNavigationOrder.ordered(
            this, false, savedPoints.map { it.first }
        )
        val pointIndex = savedPoints.associate { it.first to it.second }
        poiData.addAll(orderedPoints.mapNotNull { pointIndex[it] })
        drawNavigationOverlays()
    }

    /** Browse imported routes and POIs without panning around the map. */
    private fun showSavedNavigationLibrary() {
        val routes = savedRouteFiles()
        val poiFiles = savedPoiFiles()
        if (routes.isEmpty() && poiFiles.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Saved routes & POIs")
                .setMessage("No saved routes or POIs yet. Use GPX or POI in the map menu to import them.")
                .setPositiveButton("OK", null).show()
            return
        }
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(4))
        }
        val search = EditText(this).apply {
            hint = "Search routes or POIs"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        screen.addView(search, LinearLayout.LayoutParams(-1, dp(52)))
        val listView = ListView(this)
        screen.addView(listView, LinearLayout.LayoutParams(-1, dp(350)))
        val entries = ArrayList<Pair<String, () -> Unit>>()
        for (file in routes) {
            entries.add(("Route: " + labelOfNavigationFile(file)) to {
                selectSavedRoute(file)
            })
        }
        // Display each imported POI as a selectable named destination, including
        // those not currently drawn (only nearest 350 markers are drawn).
        for (file in poiFiles.take(40)) {
            entries.add(("POI file: " + labelOfNavigationFile(file)) to {
                showSavedPoiFile(file)
            })
        }
        var visible = entries.toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            ArrayList(entries.map { it.first }))
        listView.adapter = adapter
        val dialog = AlertDialog.Builder(this)
            .setTitle("Saved routes & POIs")
            .setView(screen)
            .setNegativeButton("Close", null)
            .create()
        fun filter(value: String) {
            visible = entries.filter { it.first.contains(value, ignoreCase = true) }
            adapter.clear()
            adapter.addAll(visible.map { it.first })
            adapter.notifyDataSetChanged()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        listView.setOnItemClickListener { _, _, position, _ ->
            val action = visible.getOrNull(position)?.second ?: return@setOnItemClickListener
            dialog.dismiss()
            action()
        }
        dialog.show()
    }

    private fun selectSavedRoute(file: File) {
        try {
            val parsed = file.inputStream().use { OfflineNavigationData.parseGpx(it) }
            if (parsed.segments.isEmpty()) throw IllegalArgumentException("No route track found")
            Prefs.prefs(this).edit().putString("qcar_active_route", file.name).apply()
            routeSegments.clear()
            routeSegments.addAll(parsed.segments)
            drawNavigationOverlays()
            val first = parsed.segments.first().first()
            followPosition = false
            mapView?.setCenter(first)
            mapView?.setZoomLevel(13.toByte())
            android.widget.Toast.makeText(this,
                "Showing route: " + labelOfNavigationFile(file),
                android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Unable to read route: " + e.message,
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun showSavedPoiFile(file: File) {
        val points = try {
            readPoiFile(file)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this,
                "Unable to read POI file: " + e.message,
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (points.isEmpty()) {
            android.widget.Toast.makeText(this, "No POIs in this file",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        val search = EditText(this).apply {
            hint = "Search locations"
            setSingleLine(true)
        }
        screen.addView(search, LinearLayout.LayoutParams(-1, dp(52)))
        val list = ListView(this)
        screen.addView(list, LinearLayout.LayoutParams(-1, dp(350)))
        var visible = points.take(5000)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            ArrayList(visible.map { it.title }))
        list.adapter = adapter
        val dialog = AlertDialog.Builder(this)
            .setTitle(labelOfNavigationFile(file) + " (" + points.size + " POIs)")
            .setView(screen)
            .setNegativeButton("Close", null).create()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                visible = points.filter { it.title.contains(s?.toString().orEmpty(), true) }
                    .take(5000)
                adapter.clear()
                adapter.addAll(visible.map { it.title })
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val poi = visible.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            showPoiDetails(poi)
        }
        dialog.show()
    }

    /** Reinstall overlays after .map changes reset the map layer stack. */
    private fun drawNavigationOverlays() {
        val map = mapView ?: return
        for (old in overlayLayers) {
            try { map.layerManager.layers.remove(old) } catch (_: Exception) { }
        }
        overlayLayers.clear()
        if (loadedMapCount < 1) return
        val routePaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setColor(Color.rgb(24, 113, 252))
            setStyle(Style.STROKE)
            setStrokeWidth(dp(5).toFloat())
        }
        for (segment in routeSegments.take(60)) {
            val step = (segment.size / 12000).coerceAtLeast(1)
            val simplified = if (step == 1) segment
                else segment.filterIndexed { index, _ -> index % step == 0 } +
                    listOf(segment.last())
            if (simplified.size > 1) {
                val line = Polyline(routePaint, AndroidGraphicFactory.INSTANCE)
                line.setPoints(simplified)
                map.layerManager.layers.add(line)
                overlayLayers.add(line)
            }
        }
        val poiPaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setColor(Color.rgb(242, 180, 34))
            setStyle(Style.FILL)
        }
        val origin = lastGpsLocation
        val visible = if (origin == null) poiData.take(350)
            else poiData.sortedBy { poi ->
                val location = Location("poi").apply {
                    latitude = poi.point.latitude
                    longitude = poi.point.longitude
                }
                origin.distanceTo(location)
            }.take(350)
        for (poi in visible) {
            // Mapsforge distributes tap events to its overlay layers.
            // A larger touch tolerance than the visible marker makes POIs
            // usable on a head unit while preserving the offline map.
            val circle = object : FixedPixelCircle(poi.point, dp(9).toFloat(),
                poiPaint, null) {
                override fun onTap(tapLatLong: LatLong, layerXY: Point,
                    tapXY: Point): Boolean {
                    val metersPerPixel = 156543.03392 *
                        cos(Math.toRadians(poi.point.latitude)) /
                        2.0.pow(map.model.mapViewPosition.zoomLevel.toInt().toDouble())
                    val hitMeters = (dp(24) * metersPerPixel).coerceAtLeast(10.0)
                    val distance = FloatArray(1)
                    Location.distanceBetween(
                        tapLatLong.latitude, tapLatLong.longitude,
                        poi.point.latitude, poi.point.longitude, distance
                    )
                    if (distance[0] > hitMeters) return false
                    showPoiDetails(poi)
                    return true
                }
            }
            map.layerManager.layers.add(circle)
            overlayLayers.add(circle)
        }
        map.layerManager.redrawLayers()
    }

    /** Display the imported POI's information without leaving QCarDash. */
    private fun showPoiDetails(poi: OfflineNavigationData.Poi) {
        val lat = poi.point.latitude
        val lon = poi.point.longitude
        val formatted = String.format(Locale.US, "%.6f, %.6f", lat, lon)
        val distance = lastGpsLocation?.let { gps ->
            val result = FloatArray(1)
            Location.distanceBetween(gps.latitude, gps.longitude, lat, lon, result)
            if (result[0] >= 1000f)
                String.format(Locale.getDefault(), "%.1f km away", result[0] / 1000.0)
            else result[0].roundToInt().toString() + " m away"
        } ?: "GPS location unavailable"
        val source = if (File(navDataFolder(), "destinations.vcf").exists())
            "Imported destination"
        else "Imported POI (location not independently verified)"
        AlertDialog.Builder(this)
            .setTitle(poi.title.ifBlank { "Imported POI" })
            .setMessage(source + "\n" + distance + "\nCoordinates: " +
                formatted + "\n\nNavigate opens your chosen navigation app.")
            .setNegativeButton("Close", null)
            .setNeutralButton("Center map") { _, _ ->
                mapView?.setCenter(poi.point)
            }
            .setPositiveButton("Navigate") { _, _ ->
                navigateToPoi(poi)
            }
            .show()
    }

    /**
     * Navigation apps calculate the route. Do not start a package's default
     * activity as fallback: that can silently drop the destination or open
     * the OEM AV-in app.
     */
    private fun navigateToPoi(poi: OfflineNavigationData.Poi) {
        val selected = Prefs.prefs(this).getString("qcar_navigation_app", null)
        if (selected.isNullOrBlank()) {
            AlertDialog.Builder(this)
                .setTitle("Choose navigation app")
                .setMessage("Select Waze, Google Maps, Organic Maps or another " +
                    "navigation app in Settings → Navigation first.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open navigation settings") { _, _ ->
                    launchNoAnimation(Intent(this, SettingsActivity::class.java)
                        .putExtra(SettingsActivity.EXTRA_OPEN_SECTION, "NAVIGATION"))
                }
                .show()
            return
        }
        val packageName = selected.substringBefore('/').trim()
        if (packageName.isBlank()) return
        val coordinates = String.format(Locale.US, "%.7f,%.7f",
            poi.point.latitude, poi.point.longitude)
        val destination = when (packageName) {
            "com.waze" -> Intent(Intent.ACTION_VIEW,
                Uri.parse("https://waze.com/ul?ll=" + coordinates + "&navigate=yes"))
                    .setPackage(packageName)
            "com.google.android.apps.maps" -> Intent(Intent.ACTION_VIEW,
                Uri.parse("google.navigation:q=" + coordinates))
                    .setPackage(packageName)
            else -> Intent(Intent.ACTION_VIEW,
                Uri.parse("geo:0,0?q=" + coordinates))
                    .setPackage(packageName)
        }
        try {
            launchNoAnimation(destination)
        } catch (e: Exception) {
            Log.w("QCarOfflineNav", "Selected navigation app could not accept POI", e)
            AlertDialog.Builder(this)
                .setTitle("Navigation app unavailable")
                .setMessage("The chosen app (" + packageName + ") could not accept this " +
                    "destination. Select another app in Navigation settings. " +
                    "Some apps only open a location, then require you to tap Directions.")
                .setNegativeButton("Close", null)
                .setPositiveButton("Choose another app") { _, _ ->
                    launchNoAnimation(Intent(this, SettingsActivity::class.java)
                        .putExtra(SettingsActivity.EXTRA_OPEN_SECTION, "NAVIGATION"))
                }
                .show()
        }
    }

    private fun updateSpeedZoom(location: Location, force: Boolean = false) {
        if (!Prefs.prefs(this).getBoolean("qcar_speed_zoom", false) ||
            loadedMapCount < 1 || !location.hasSpeed()) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - autoZoomLastAt < 12000L) return
        val kmh = location.speed * 3.6f
        val zoom = when {
            kmh < 12f -> 17
            kmh < 35f -> 16
            kmh < 70f -> 15
            kmh < 105f -> 14
            else -> 13
        }
        mapView?.let { map ->
            if (map.model.mapViewPosition.zoomLevel.toInt() != zoom)
                map.setZoomLevel(zoom.toByte())
        }
        autoZoomLastAt = now
    }

    /** Offline warning for a nearby imported fixed POI, not live reporting. */
    private fun alertNearbyCameras(location: Location) {
        if (poiData.isEmpty() || !location.hasSpeed() || location.speed < 2.0f ||
            File(navDataFolder(), "destinations.vcf").exists()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPoiAlertAt < 90000L) return
        val closest = poiData.asSequence().map { poi ->
            val target = Location("poi").apply {
                latitude = poi.point.latitude
                longitude = poi.point.longitude
            }
            poi to location.distanceTo(target)
        }.filter { it.second < 350f }.minByOrNull { it.second } ?: return
        val key = closest.first.title + closest.first.point.toString()
        if (key == lastPoiAlertKey && now - lastPoiAlertAt < 300000L) return
        lastPoiAlertAt = now
        lastPoiAlertKey = key
        android.widget.Toast.makeText(
            this, "Camera / POI nearby: " + closest.first.title.take(35) +
                " (" + closest.second.toInt() + "m)",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    private fun showMapStatus(message: String) {
        val map = mapView ?: return
        if (mapStatus == null) {
            val status = text(message, 13f, white).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rounded(Color.argb(226, 20, 20, 20))
                setOnClickListener { if (!mapFilePending) chooseOfflineMap() }
                maxLines = 4
            }
            (map.parent as? FrameLayout)?.addView(status, flp(-1, -2, Gravity.CENTER))
            mapStatus = status
        }
        mapStatus?.text = message
        mapStatus?.visibility = View.VISIBLE
        Log.i("QCarOfflineMap", message)
    }

    private fun showMapImportHint() {
        showMapStatus("Tap MAP to import country .map files")
    }

    /** Installed offline regions survive launcher restarts and app updates. */
    private fun mapFolder(): File = File(filesDir, "osm-maps").apply { mkdirs() }

    private fun migrateOldMap() {
        val old = File(filesDir, "offline-osm.map")
        if (!old.exists()) return
        var destination = File(mapFolder(), "previous-offline.map")
        var suffix = 2
        while (destination.exists()) {
            destination = File(mapFolder(), "previous-offline-" + suffix++ + ".map")
        }
        if (!old.renameTo(destination))
            Log.w("QCarOfflineMap", "Old map could not be moved into the multi-map library")
    }

    private fun chooseOfflineMap() {
        if (mapFilePending) return
        try {
            // Many Android head units ship a vendor file manager but no
            // Android DocumentsUI. GET_CONTENT is compatible with both.
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(intent, 310)
        } catch (_: Exception) {
            android.widget.Toast.makeText(
                this, "Install a file manager to import offline maps",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    @Deprecated("Android 10 file picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 313) {
            restoreNavigationOverlays()
            if (resultCode == RESULT_OK && data != null) {
                val routeName = data.getStringExtra(SavedRoutesActivity.EXTRA_ROUTE_FILE)
                if (routeName != null) {
                    savedRouteFiles().firstOrNull { it.name == routeName }
                        ?.let { selectSavedRoute(it) }
                } else if (data.hasExtra(SavedRoutesActivity.EXTRA_POI_LAT) &&
                    data.hasExtra(SavedRoutesActivity.EXTRA_POI_LON)) {
                    val point = LatLong(
                        data.getDoubleExtra(SavedRoutesActivity.EXTRA_POI_LAT, 0.0),
                        data.getDoubleExtra(SavedRoutesActivity.EXTRA_POI_LON, 0.0)
                    )
                    showPoiDetails(OfflineNavigationData.Poi(
                        point, data.getStringExtra(SavedRoutesActivity.EXTRA_POI_TITLE)
                            ?: "Imported POI"
                    ))
                }
            }
            return
        }
        if (requestCode in 311..312) {
            if (resultCode == RESULT_OK && data?.data != null)
                importNavigationData(data.data!!, requestCode)
            return
        }
        if (requestCode != 310 || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.data?.let { uris.add(it) }
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        }
        val selected = uris.distinct()
        if (selected.isEmpty() || mapFilePending) return
        mapFilePending = true
        showMapStatus("Importing " + selected.size + " offline map(s)...")
        Thread {
            var imported = 0
            var failed = 0
            var lastFailure: String? = null
            for ((index, uri) in selected.withIndex()) {
                runOnUiThread {
                    showMapStatus(
                        "Importing map " + (index + 1) + "/" + selected.size +
                            " (large maps can take a while)..."
                    )
                }
                try {
                    importCountryMap(uri) { bytes ->
                        // Report progress periodically, not on every copied buffer.
                        val mb = bytes / (1024L * 1024L)
                        runOnUiThread {
                            showMapStatus(
                                "Importing " + (index + 1) + "/" + selected.size +
                                    ": " + mb + " MB copied..."
                            )
                        }
                    }
                    imported++
                } catch (e: Exception) {
                    failed++
                    lastFailure = e.message ?: e.javaClass.simpleName
                    Log.e("QCarOfflineMap", "Import failed: " + uri, e)
                }
            }
            val errors = lastFailure
            val added = imported
            val failures = failed
            runOnUiThread {
                mapFilePending = false
                loadOfflineMaps()
                if (failures > 0) {
                    val result = "Imported " + added + ", failed " + failures +
                        ": " + errors
                    if (loadedMapCount == 0) showMapStatus(result)
                    android.widget.Toast.makeText(this, result, android.widget.Toast.LENGTH_LONG).show()
                } else if (added > 0) {
                    android.widget.Toast.makeText(
                        this, "Loaded " + loadedMapCount + " offline map(s)",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /** Stream potentially large maps without holding them in RAM. */
    private fun importCountryMap(uri: Uri, onProgress: (Long) -> Unit) {
        val directory = mapFolder()
        val temp = File.createTempFile("import-", ".tmp", directory)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    var nextReport = 8L * 1024L * 1024L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        total += n
                        if (total >= nextReport) {
                            onProgress(total)
                            nextReport = total + 8L * 1024L * 1024L
                        }
                    }
                }
            } ?: throw java.io.IOException("Map cannot be read")
            if (temp.length() < 512L) throw java.io.IOException("Map is empty")
            // Constructing MapFile validates Mapsforge format before it becomes permanent.
            val probe = MapFile(temp)
            try { probe.boundingBox() } finally { probe.close() }
            var rawName = "country"
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) rawName = cursor.getString(0) ?: "country"
                    }
            } catch (_: Exception) { }
            val safe = rawName.replace(Regex("(?i)\\.map$"), "")
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .take(70).trim('.').ifBlank { "country" }
            var target = File(directory, safe + ".map")
            var count = 2
            while (target.exists()) {
                target = File(directory, safe + "-" + count++ + ".map")
            }
            if (!temp.renameTo(target)) throw java.io.IOException("Map could not be saved")
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /**
     * Combine neighboring countries in a single render layer. DEDUPLICATE
     * handles small overlaps around international borders.
     */
    private fun loadOfflineMaps() {
        val map = mapView ?: return
        val files = mapFolder().listFiles { file ->
            file.isFile && file.extension.equals("map", true)
        }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) {
            showMapImportHint()
            return
        }

        val combined = MultiMapDataStore(MultiMapDataStore.DataPolicy.DEDUPLICATE)
        var count = 0
        try {
            for (file in files) {
                try {
                    val reader = MapFile(file)
                    try {
                        combined.addMapDataStore(reader, count == 0, count == 0)
                        count++
                    } catch (e: Exception) {
                        reader.close()
                        throw e
                    }
                } catch (e: Exception) {
                    Log.w("QCarOfflineMap", "Skipping map: " + file.name, e)
                }
            }
            if (count == 0) {
                combined.close()
                showMapStatus("No valid Mapsforge .map files. Tap MAP to try again.")
                return
            }

            val cache = AndroidUtil.createTileCache(
                this, "qcar-multimap-cache", map.model.displayModel.tileSize, 1.0f,
                map.model.frameBufferModel.overdrawFactor
            )
            val layer = TileRendererLayer(
                cache, combined, map.model.mapViewPosition, AndroidGraphicFactory.INSTANCE
            )
            layer.setXmlRenderTheme(MapsforgeThemes.MOTORIDER)

            val previousCenter = if (loadedMapCount > 0)
                map.model.mapViewPosition.center else null
            val previousZoom = map.model.mapViewPosition.zoomLevel
            // Remove old layers before closing their data stores.
            map.layerManager.layers.clear()
            mapCache?.destroy()
            mapData?.close()
            map.layerManager.layers.add(layer)
            mapCache = cache
            mapData = combined
            loadedMapCount = count
            if (previousCenter != null) {
                map.setCenter(previousCenter)
                map.setZoomLevel(previousZoom)
            } else {
                map.setCenter(combined.startPosition())
                map.setZoomLevel(combined.startZoomLevel())
            }
            installPositionMarker(map)
            drawNavigationOverlays()
            mapStatus?.visibility = View.GONE
            lastGpsLocation?.let { updateMapLocation(it, force = true) }
            scheduleMapRedraw()
        } catch (e: Exception) {
            Log.w("QCarOfflineMap", "Could not load offline map library", e)
            combined.close()
            showMapStatus("Map load error: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** Fixed-size blue dot with white rim and translucent accuracy halo. */
    private fun installPositionMarker(map: MapView) {
        val gps = lastGpsLocation
        val position = gps?.let { LatLong(it.latitude, it.longitude) }
        val paintHalo = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setColor(Color.argb(80, 58, 146, 255))
            setStyle(Style.FILL)
        }
        val paintWhite = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setColor(Color.WHITE)
            setStyle(Style.FILL)
        }
        val paintBlue = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setColor(Color.rgb(37, 115, 245))
            setStyle(Style.FILL)
        }
        val halo = FixedPixelCircle(position, 17f, paintHalo, null)
        val rim = FixedPixelCircle(position, 10f, paintWhite, null)
        val dot = FixedPixelCircle(position, 7f, paintBlue, null)
        map.layerManager.layers.add(halo)
        map.layerManager.layers.add(rim)
        map.layerManager.layers.add(dot)
        locationHalo = halo
        locationDot = dot
        locationRim = rim
    }

    private fun updateMapLocation(location: Location, force: Boolean = false) {
        val map = mapView ?: return
        val point = LatLong(location.latitude, location.longitude)
        locationDot?.setLatLong(point)
        locationHalo?.setLatLong(point)
        locationRim?.setLatLong(point)
        map.layerManager.redrawLayers()
        if (loadedMapCount == 0 || (!followPosition && !force)) return
        if (!force && lastCameraLocation?.distanceTo(location)?.let { it < 20f } == true) return
        lastCameraLocation = Location(location)
        map.setCenter(point)
    }

    private fun centerMapOnMyLocation() {
        followPosition = true
        lastGpsLocation?.let { updateMapLocation(it, force = true); return }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED) {
            try {
                manager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                    lastGpsLocation = it
                    updateMapLocation(it, force = true)
                }
            } catch (_: SecurityException) { }
        }
    }

    private fun openOemScreen() {
        if (!OemScreenBridge.open(this)) {
            android.widget.Toast.makeText(
                this, "OEM screen unavailable on this head unit",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun addVehicle(parent: LinearLayout) {
        val box = card()
        parent.addView(box, inset(box, 0, -1, 4f))
        val content = column().apply {
            setPadding(dp(8), dp(12), dp(8), dp(12))
            gravity = Gravity.CENTER
        }
        box.addView(content, flp())
        content.addView(text("VEHICLE", 12f, muted, true), lp(-1, 30))
        content.addView(text("—", 34f, white, true), LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(text("GEAR  —", 12f, muted), lp(-1, 25))
        content.addView(text("RPM  —     °C  —", 12f, muted), lp(-1, 28))
    }

    private fun addAdBlue(parent: LinearLayout) {
        val box = card()
        parent.addView(box, inset(box, 0, -1, 2.5f))
        val content = column().apply { setPadding(dp(5), dp(8), dp(5), dp(8)) }
        box.addView(content, flp())
        content.addView(text("ADBLUE", 13f, white, true), lp(-1, 35))
        content.addView(text("◈", 35f, accent), LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(text("—", 29f, white, true), lp(-1, 35))
        content.addView(text("CAN DATA PENDING", 10f, muted), lp(-1, 25))
    }

    private fun addTemperature(parent: LinearLayout) {
        val box = card()
        parent.addView(box, inset(box, 0, -1, 3f))
        val content = column()
        box.addView(content, flp())
        content.addView(text("OUTSIDE", 12f, muted, true), lp(-1, 30))
        content.addView(text("—", 46f, white), LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(text("°C", 17f, muted), lp(-1, 35))
    }

    private fun addClock(parent: LinearLayout) {
        val box = card()
        parent.addView(box, inset(box, 0, -1, 4f))
        val content = column().apply { setPadding(dp(6), dp(10), dp(6), dp(10)) }
        box.addView(content, flp())
        clockView = text("--:--", 44f, white)
        dateView = text("", 15f, muted)
        content.addView(clockView, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(dateView, lp(-1, 45))
    }

    private fun buildMusic(parent: FrameLayout) {
        val wrap = column().apply {
            gravity = Gravity.TOP
            setPadding(dp(18), dp(15), dp(18), dp(15))
        }
        parent.addView(wrap, flp())
        val heading = row()
        heading.addView(text("POWERAMP", 14f, white, true),
            LinearLayout.LayoutParams(0, dp(35), 1f))
        val launch = text("OPEN  ↗", 13f, muted).apply { setOnClickListener { openPreferredMusic() } }
        heading.addView(launch, lp(90, 35))
        wrap.addView(heading, lp(-1, 42))

        val body = row().apply { gravity = Gravity.CENTER_VERTICAL }
        wrap.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        mediaArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Color.rgb(30, 30, 30))
            setOnClickListener { openPoweramp() }
        }
        body.addView(mediaArt, lp(164, 164))
        val details = column().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(3), 0)
        }
        body.addView(details, LinearLayout.LayoutParams(0, -1, 1f))
        mediaTitle = text("Poweramp not playing", 25f, white, true).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        details.addView(mediaTitle, lp(-1, 68))
        mediaArtist = text("Tap to start music", 17f, muted).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        details.addView(mediaArtist, lp(-1, 35))
        mediaProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.DKGRAY)
        }
        details.addView(mediaProgress, LinearLayout.LayoutParams(-1, dp(5)).apply {
            topMargin = dp(14)
        })
        val controls = row().apply { gravity = Gravity.CENTER }
        wrap.addView(controls, lp(-1, 68))
        val previous = musicButton("◀◀") { command(PowerampBridge.PREVIOUS) }
        mediaPlay = musicButton("▶") { toggleMusic() }
        val next = musicButton("▶▶") { command(PowerampBridge.NEXT) }
        controls.addView(previous, LinearLayout.LayoutParams(0, -1, 1f))
        controls.addView(mediaPlay, LinearLayout.LayoutParams(0, -1, 1f))
        controls.addView(next, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun musicButton(label: String, click: () -> Unit): TextView =
        text(label, 27f, white, true).apply { setOnClickListener { click() } }

    private fun launchNoAnimation(intent: Intent) {
        startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /** Launch the user's chosen external navigation application from the dock. */
    private fun openPreferredNavigation() {
        val choice = Prefs.prefs(this).getString("qcar_navigation_app", null)
        if (choice.isNullOrBlank()) {
            android.widget.Toast.makeText(
                this, "Choose a map app in Settings → Navigation",
                android.widget.Toast.LENGTH_LONG
            ).show()
            launchNoAnimation(
                Intent(this, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_OPEN_SECTION, "NAVIGATION")
            )
            return
        }
        try {
            val parts = choice.split('/', limit = 2)
            val target = if (parts.size == 2)
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(parts[0], parts[1])
            else packageManager.getLaunchIntentForPackage(parts[0])
            if (target == null) throw android.content.ActivityNotFoundException()
            launchNoAnimation(target)
        } catch (_: Exception) {
            android.widget.Toast.makeText(
                this, "Selected map app is unavailable; choose another in Settings",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Optional chosen app only changes the OPEN shortcut, not Poweramp controls. */
    private fun openPreferredMusic() {
        val choice = Prefs.prefs(this).getString("qcar_music_app", null)
        if (choice.isNullOrEmpty()) { openPoweramp(); return }
        try {
            val parts = choice.split('/', limit = 2)
            val target = if (parts.size == 2)
                Intent(Intent.ACTION_MAIN).setClassName(parts[0], parts[1])
            else packageManager.getLaunchIntentForPackage(parts[0])
            if (target == null) throw android.content.ActivityNotFoundException()
            launchNoAnimation(target)
        } catch (_: Exception) {
            android.widget.Toast.makeText(
                this, "Chosen music app unavailable", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * The HOME activity is the safe foreground entry point on vendor head
     * units. Android 10+ limits launching activities from background boot
     * receivers, so detect the system boot count here instead of hijacking
     * startup with an unreliable BOOT_COMPLETED receiver.
     */
    private fun autoOpenPowerampAfterBoot() {
        val prefs = Prefs.prefs(this)
        if (!prefs.getBoolean("qcar_poweramp_auto_open", false)) return
        val bootCount = Settings.Global.getInt(
            contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (bootCount < 0 ||
            prefs.getInt("qcar_poweramp_last_boot", bootCount) == bootCount) return
        // Record before launch: returning from Poweramp must never reopen it
        // in a loop, and launching Settings must not trigger another start.
        prefs.edit().putInt("qcar_poweramp_last_boot", bootCount).apply()
        val target = packageManager.getLaunchIntentForPackage(PowerampBridge.PACKAGE)
        if (target == null) {
            Log.w("QCarPoweramp", "Auto-open skipped: Poweramp not installed")
            return
        }
        try {
            launchNoAnimation(target)
            if (prefs.getBoolean("qcar_poweramp_keep_playing", false)) {
                // Give the app time to initialize its player after cold boot.
                handler.postDelayed({
                    PowerampBridge.command(this, PowerampBridge.PLAY)
                }, 3000L)
            }
        } catch (e: Exception) {
            Log.w("QCarPoweramp", "Auto-open unavailable", e)
        }
    }

    private fun openPoweramp() {
        packageManager.getLaunchIntentForPackage(PowerampBridge.PACKAGE)?.let { launchNoAnimation(it) }
    }

    private fun command(code: Int) {
        // Use media sessions when available; otherwise use Poweramp's public API.
        val controller = mediaController?.takeIf { it.packageName == PowerampBridge.PACKAGE }
        val actions = controller?.playbackState?.actions ?: 0L
        val required = when (code) {
            PowerampBridge.PREVIOUS -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
            PowerampBridge.NEXT -> PlaybackState.ACTION_SKIP_TO_NEXT
            PowerampBridge.PAUSE -> PlaybackState.ACTION_PAUSE
            else -> PlaybackState.ACTION_PLAY
        }
        if (controller != null && actions and required != 0L) {
            try {
                when (code) {
                    PowerampBridge.PREVIOUS -> controller.transportControls.skipToPrevious()
                    PowerampBridge.NEXT -> controller.transportControls.skipToNext()
                    PowerampBridge.PAUSE -> controller.transportControls.pause()
                    else -> controller.transportControls.play()
                }
                return
            } catch (_: Exception) { /* Fall through to direct API. */ }
        }
        if (!PowerampBridge.command(this, code)) {
            android.widget.Toast.makeText(
                this, "Unable to send Poweramp command", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun toggleMusic() {
        val playing = mediaController?.playbackState?.state == PlaybackState.STATE_PLAYING ||
            (mediaController == null && powerampPaused == false)
        command(if (playing) PowerampBridge.PAUSE else PowerampBridge.PLAY)
    }

    private fun notificationAccessGranted(): Boolean {
        val component = ComponentName(this, DashNotificationListener::class.java)
        return (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)
            ?.isNotificationListenerAccessGranted(component) == true
    }

    private fun openNotificationAccess() {
        try {
            launchNoAnimation(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            android.widget.Toast.makeText(
                this, "Open system settings and grant notification access",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun renderTime() {
        clockView?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        dateView?.text = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date())
    }

    private fun renderLocation() {
        val active = manager?.isLocationEnabled == true
        val valid = lastLocationAt > 0L && SystemClock.elapsedRealtime() - lastLocationAt < 5000L
        speedView?.text = if (active && valid) lastSpeedKmh.roundToInt().toString() else "--"
    }

    private fun startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return
        try {
            manager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
        } catch (_: Exception) { }
    }

    private fun stopGps() {
        try { manager?.removeUpdates(locationListener) } catch (_: Exception) { }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 109) startGps()
    }

    private fun selectSession(sessions: List<MediaController>?) {
        mediaController?.unregisterCallback(mediaCallback)
        mediaController = sessions?.firstOrNull {
            it.packageName == PowerampBridge.PACKAGE &&
                it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: sessions?.firstOrNull { it.packageName == PowerampBridge.PACKAGE }
        mediaController?.registerCallback(mediaCallback)
        renderMedia()
    }

    private fun startMedia() {
        // Sticky Poweramp broadcasts supply track data without notification access.
        if (!powerampEventsRegistered) {
            val filter = IntentFilter().apply {
                addAction(PowerampBridge.TRACK_CHANGED)
                addAction(PowerampBridge.STATUS_CHANGED)
            }
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(powerampEvents, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    registerReceiver(powerampEvents, filter)
                }
                powerampEventsRegistered = true
            } catch (_: Exception) { }
        }
        if (!notificationAccessGranted()) {
            renderMedia()
            return
        }
        val component = ComponentName(this, DashNotificationListener::class.java)
        sessionManager = getSystemService(MediaSessionManager::class.java)
        try {
            sessionManager?.addOnActiveSessionsChangedListener(sessionsChanged, component)
            sessionRegistered = true
            selectSession(sessionManager?.getActiveSessions(component))
        } catch (_: SecurityException) {
            renderMedia()
        }
    }

    private fun stopMedia() {
        if (powerampEventsRegistered) {
            try { unregisterReceiver(powerampEvents) } catch (_: Exception) { }
            powerampEventsRegistered = false
        }
        mediaController?.unregisterCallback(mediaCallback)
        mediaController = null
        if (sessionRegistered) {
            try { sessionManager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
            catch (_: Exception) { }
            sessionRegistered = false
        }
    }

    private fun renderMedia() {
        val controller = mediaController
        val metadata = controller?.metadata
        mediaTitle?.text = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() } ?: powerampTitle?.takeIf { it.isNotBlank() } ?: "Poweramp"
        mediaArtist?.text = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() } ?: powerampArtist?.takeIf { it.isNotBlank() }
            ?: if (!notificationAccessGranted())
                "Tap here for notification access (Android 14: allow restricted settings first)"
            else "Open Poweramp to start playback"
        val cover = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (cover != null) {
            // Poweramp may destroy its session on pause/stop. Do not discard
            // the last real cover just because the current metadata is null.
            lastAlbumArt = cover
            if (lastSavedArtwork !== cover) {
                lastSavedArtwork = cover
                cacheAlbumArt(cover)
            }
        } else if (!savedAlbumArtLoaded) {
            savedAlbumArtLoaded = true
            loadCachedAlbumArt()
        }
        mediaArt?.setImageBitmap(lastAlbumArt)
        mediaPlay?.text = if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING ||
            (controller == null && powerampPaused == false)) "Ⅱ" else "▶"
        renderMediaProgress()
    }

    /** Persist only the most recent album cover across launcher restarts. */
    private fun cacheAlbumArt(bitmap: Bitmap) {
        val file = File(filesDir, "poweramp-last-art.jpg")
        Thread {
            try {
                val temp = File(filesDir, "poweramp-last-art.tmp")
                FileOutputStream(temp).use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) {
                        throw java.io.IOException("Artwork encoding failed")
                    }
                }
                if (file.exists()) file.delete()
                if (!temp.renameTo(file)) temp.delete()
            } catch (e: Exception) {
                Log.w("QCarPoweramp", "Could not cache album cover", e)
            }
        }.start()
    }

    private fun loadCachedAlbumArt() {
        val file = File(filesDir, "poweramp-last-art.jpg")
        if (!file.exists()) return
        try {
            lastAlbumArt = BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.w("QCarPoweramp", "Could not restore last album cover", e)
        }
    }

    private fun renderMediaProgress() {
        val metadata = mediaController?.metadata ?: run {
            mediaProgress?.progress = 0
            return
        }
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (duration <= 0L) { mediaProgress?.progress = 0; return }
        val playback = mediaController?.playbackState ?: return
        val delta = if (playback.state == PlaybackState.STATE_PLAYING)
            SystemClock.elapsedRealtime() - playback.lastPositionUpdateTime else 0L
        val position = (playback.position + delta * playback.playbackSpeed).toLong()
        mediaProgress?.progress = ((position.coerceIn(0L, duration) * 1000L) / duration).toInt()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        startGps()
        startMedia()
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        stopGps()
        stopMedia()
        unregisterReceiver(timeReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        renderMedia()
        renderTime()
        renderLocation()
        autoOpenPowerampAfterBoot()
    }

    override fun onDestroy() {
        mapResizeRedraw?.let { handler.removeCallbacks(it) }
        mapResizeRedraw = null
        mapView?.destroyAll()
        mapView = null
        mapData = null
        mapCache = null
        overlayLayers.clear()
        locationDot = null
        locationHalo = null
        locationRim = null
        AndroidGraphicFactory.clearResourceMemoryCache()
        super.onDestroy()
    }

    @Deprecated("Use gesture navigation")
    override fun onBackPressed() {
        // HOME is the bottom of the task stack.
    }

}
