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
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
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
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.text.SimpleDateFormat
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
    private var mapView: MapView? = null
    private var googleMap: GoogleMap? = null
    private var lastGpsLocation: Location? = null
    private var lastCameraLocation: Location? = null
    private var followPosition = true
    private var savedMapState: Bundle? = null
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
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        window.setWindowAnimations(0)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.setBackgroundDrawableResource(android.R.color.black)
        manager = getSystemService(LOCATION_SERVICE) as? LocationManager
        savedMapState = savedInstanceState?.getBundle("google_map_state")
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
        val sym = text(symbol, 26f, if (label == "HOME") accent else white)
        item.addView(sym, lp(58, 43))
        item.addView(text(label, 10f, muted, true), lp(58, 18))
        parent.addView(item, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            setMargins(0, dp(5), 0, dp(5))
        })
    }

    /** Lightweight static navigation tile: tapping it opens Google Maps externally. */
    private fun buildNavigation(parent: FrameLayout) {
        if (hasMapsApiKey()) {
            try {
                val options = GoogleMapOptions().mapType(GoogleMap.MAP_TYPE_NORMAL)
                    .zoomControlsEnabled(false).compassEnabled(false)
                val view = MapView(this, options)
                mapView = view
                parent.addView(view, flp())
                view.onCreate(savedMapState)
                view.getMapAsync { ready ->
                    googleMap = ready
                    ready.mapType = GoogleMap.MAP_TYPE_NORMAL
                    ready.uiSettings.isMapToolbarEnabled = false
                    ready.uiSettings.isCompassEnabled = false
                    ready.uiSettings.isZoomControlsEnabled = false
                    ready.uiSettings.isMyLocationButtonEnabled = false
                    try {
                        ready.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.qcar_map_dark))
                    } catch (_: Exception) { /* Keep normal tiles if styling is unavailable. */ }
                    ready.setOnCameraMoveStartedListener { reason ->
                        if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                            followPosition = false
                        }
                    }
                    lastGpsLocation?.let { updateMapLocation(it, force = true) }
                }
            } catch (_: Exception) {
                mapView = null
                showMapPlaceholder(parent, "Google Maps could not initialize")
            }
        } else {
            showMapPlaceholder(parent, "Configure MAPS_API_KEY to show the live Google Map")
        }

        // QCar-style controls overlay; the rest of the panel retains full map gestures.
        val compass = mapControl("⌖") {
            googleMap?.let { map ->
                map.moveCamera(CameraUpdateFactory.newCameraPosition(
                    com.google.android.gms.maps.model.CameraPosition.Builder(map.cameraPosition)
                        .bearing(0f).tilt(0f).build()
                ))
            }
        }
        parent.addView(compass, flp(39, 39, Gravity.TOP or Gravity.LEFT).apply {
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
            googleMap?.moveCamera(CameraUpdateFactory.zoomIn())
        }, lp(39, 39))
        zoom.addView(mapControl("−") {
            googleMap?.moveCamera(CameraUpdateFactory.zoomOut())
        }, lp(39, 39))
        parent.addView(zoom, flp(41, 84, Gravity.RIGHT or Gravity.BOTTOM).apply {
            rightMargin = dp(12)
            bottomMargin = dp(12)
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
        text(label, 24f, white, true).apply {
            background = rounded(Color.argb(235, 21, 21, 21), 5)
            setOnClickListener { click() }
            isFocusable = true
            contentDescription = when (label) {
                "+" -> "Zoom in"
                "−" -> "Zoom out"
                "⊙" -> "Center on my location"
                else -> "Reset compass"
            }
        }

    private fun showMapPlaceholder(parent: FrameLayout, message: String) {
        parent.addView(FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(21, 27, 34))
            val label = text(message, 14f, muted).apply {
                setPadding(dp(18), dp(18), dp(18), dp(18))
            }
            addView(label, flp(-1, -2, Gravity.CENTER))
        }, flp())
    }

    private fun hasMapsApiKey(): Boolean = try {
        val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        !info.metaData?.getString("com.google.android.geo.API_KEY").isNullOrBlank()
    } catch (_: Exception) { false }

    private fun updateMapLocation(location: Location, force: Boolean = false) {
        val map = googleMap ?: return
        if (!followPosition && !force) return
        val previous = lastCameraLocation
        if (!force && previous != null && previous.distanceTo(location) < 30f) return
        lastCameraLocation = Location(location)
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(
            LatLng(location.latitude, location.longitude),
            if (force) 16f else map.cameraPosition.zoom.coerceAtLeast(15f)
        ))
    }

    private fun centerMapOnMyLocation() {
        followPosition = true
        val last = lastGpsLocation
        if (last != null) updateMapLocation(last, force = true)
        else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
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
        val launch = text("OPEN  ↗", 13f, muted).apply { setOnClickListener { openPoweramp() } }
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
        mediaArt?.setImageBitmap(cover)
        mediaPlay?.text = if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING ||
            (controller == null && powerampPaused == false)) "Ⅱ" else "▶"
        renderMediaProgress()
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
        mapView?.onStart()
        startGps()
        startMedia()
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        mapView?.onStop()
        stopGps()
        stopMedia()
        unregisterReceiver(timeReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        renderTime()
        renderLocation()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        mapView?.onDestroy()
        googleMap = null
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val mapState = Bundle()
        mapView?.onSaveInstanceState(mapState)
        outState.putBundle("google_map_state", mapState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Use gesture navigation")
    override fun onBackPressed() {
        // HOME is the bottom of the task stack.
    }

}
