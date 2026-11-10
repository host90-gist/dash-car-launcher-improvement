package com.debashis.carlauncher

import android.Manifest
import android.app.Activity
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
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.io.FileOutputStream
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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
        val navigation = card()
        dashboard.addView(navigation, inset(navigation, 0, -1, 7f))
        buildNavigation(navigation)

        val right = column().apply { gravity = Gravity.TOP }
        dashboard.addView(right, LinearLayout.LayoutParams(0, -1, 13f))
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
        migrateOldMap()
        loadOfflineMaps()

        val chooseMap = mapControl("MAP") { chooseOfflineMap() }
        chooseMap.textSize = 11f
        parent.addView(chooseMap, flp(48, 39, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = dp(12)
            topMargin = dp(12)
        })

        val recenter = mapControl("⊙") { centerMapOnMyLocation() }
        parent.addView(recenter, flp(39, 39, Gravity.TOP or Gravity.RIGHT).apply {
            rightMargin = dp(12)
            topMargin = dp(12)
        })

        val zoom = column()
        zoom.addView(mapControl("+") {
            mapView?.let { map -> map.setZoomLevel((map.model.mapViewPosition.zoomLevel.toInt() + 1).coerceAtMost(20).toByte()) }
        }, lp(39, 39))
        zoom.addView(mapControl("−") {
            mapView?.let { map -> map.setZoomLevel((map.model.mapViewPosition.zoomLevel.toInt() - 1).coerceAtLeast(2).toByte()) }
        }, lp(39, 39))
        parent.addView(zoom, flp(41, 84, Gravity.RIGHT or Gravity.BOTTOM).apply {
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
    }

    private fun mapControl(label: String, click: () -> Unit): TextView =
        text(label, 23f, white, true).apply {
            background = rounded(Color.argb(235, 21, 21, 21), 5)
            setOnClickListener { click() }
            contentDescription = when (label) {
                "MAP" -> "Import additional offline OpenStreetMap maps"
                "⊙" -> "Recenter map on my location"
                "+" -> "Zoom in"
                else -> "Zoom out"
            }
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
                this, "qcar-multimap-cache", map.model.displayModel.tileSize, 0.45f,
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
            mapStatus?.visibility = View.GONE
            lastGpsLocation?.let { updateMapLocation(it, force = true) }
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
    }

    override fun onDestroy() {
        mapView?.destroyAll()
        mapView = null
        mapData = null
        mapCache = null
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
