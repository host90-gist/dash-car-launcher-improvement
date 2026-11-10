package com.debashis.carlauncher

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.media.AudioManager
import android.widget.SeekBar
import android.content.ComponentName
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.InputType
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Settings. Two pane, because a single scrolling list wastes 1280x720 landscape.
 *
 * Every row is at least 76dp tall: this gets tapped in a parked car, not on a desk.
 */
class SettingsActivity : Activity() {

    private enum class Section(val title: String, val subtitle: String) {
        DOCK("Dock", "Eight slots. Tap one to choose a different app."),
        DRIVING("Driving", "What the screen does while the car is moving."),
        VOLUME("Volume", "Music, navigation, BT call and reverse-camera sound."),
        NAVIGATION("Navigation", "Choose the navigation app."),
        BACKCAR("Backcar", "Factory reversing-camera controls."),
        WALLPAPER("Wallpaper", "Shown behind the clock. Landscape images work best."),
        DISPLAY("Display", "Clock format and speed units."),
        PERMISSIONS("Permissions", "Set up location and music metadata access."),
        ABOUT("About", "")
    }

    private companion object {
        const val REQ_PICK_APP = 10
        const val REQ_PICK_IMAGE = 11
        const val REQ_PICK_PACK = 12
        const val REQ_LOCATION_SETUP = 13
        const val REQ_PICK_NAVIGATION = 14
        const val REQ_PICK_MUSIC = 15
    }

    private var section = Section.DOCK
    private var editingSlot = -1
    private var editingDriveSlot = -1

    private lateinit var nav: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        hideSystemBars()

        nav = findViewById(R.id.nav)
        content = findViewById(R.id.content)
        titleView = findViewById(R.id.section_title)
        subView = findViewById(R.id.section_sub)

        findViewById<View>(R.id.close).setOnClickListener { finish() }

        buildNav()
        render()
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

    private fun buildNav() {
        nav.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (s in Section.values()) {
            val item = inflater.inflate(R.layout.settings_nav_item, nav, false) as TextView
            item.text = s.title
            item.isSelected = s == section
            item.setOnClickListener {
                section = s
                buildNav()
                render()
            }
            nav.addView(item)
        }
    }

    // ---------------------------------------------------------------- render

    private fun render() {
        titleView.text = section.title
        subView.text = section.subtitle
        subView.visibility = if (section.subtitle.isEmpty()) View.GONE else View.VISIBLE
        content.removeAllViews()
        when (section) {
            Section.DOCK -> renderDock()
            Section.DRIVING -> renderDriving()
            Section.VOLUME -> renderVolume()
            Section.NAVIGATION -> renderNavigation()
            Section.BACKCAR -> renderBackcar()
            Section.WALLPAPER -> renderWallpaper()
            Section.DISPLAY -> renderDisplay()
            Section.PERMISSIONS -> renderPermissions()
            Section.ABOUT -> renderAbout()
        }
    }

    private fun renderDock() {
        val group = newGroup()
        val slots = Prefs.dock(this)

        for ((index, slot) in slots.withIndex()) {
            val resolved = resolve(slot)
            val row = newRow(group)
            row.findViewById<TextView>(R.id.row_title).text = "Slot ${index + 1}"

            val sub = row.findViewById<TextView>(R.id.row_sub)
            val icon = row.findViewById<ImageView>(R.id.row_icon)
            val value = row.findViewById<TextView>(R.id.row_value)

            when {
                slot.component == Prefs.DRAWER -> {
                    icon.setImageResource(R.drawable.ic_all_apps)
                    sub.text = labelFor(slot, "All apps")
                    value.text = "App drawer"
                }
                slot.component.isEmpty() -> {
                    icon.setImageDrawable(null)
                    sub.text = "Empty"
                    value.text = ""
                }
                resolved == null -> {
                    icon.setImageDrawable(null)
                    // The important case: tells you WHY a tile is dimmed on the home screen
                    // instead of leaving you to guess.
                    sub.text = "Not installed on this unit"
                    sub.setTextColor(getColor(R.color.accent))
                    value.text = slot.component.substringBefore('/')
                }
                else -> {
                    icon.setImageDrawable(resolved)
                    sub.text = labelFor(slot, appLabel(slot.component))
                    value.text = slot.component.substringBefore('/')
                }
            }

            row.setOnClickListener {
                editingSlot = index
                startActivityForResult(
                    Intent(this, AppPickerActivity::class.java)
                        .putExtra(AppPickerActivity.EXTRA_SLOT, index),
                    REQ_PICK_APP
                )
            }
            group.addView(row)
        }
        content.addView(group)

        val reset = newGroup()
        val row = newRow(reset)
        row.findViewById<TextView>(R.id.row_title).apply {
            text = "Reset dock to defaults"
            setTextColor(getColor(R.color.accent))
        }
        row.findViewById<TextView>(R.id.row_sub).text = "Restores the eight shipped slots"
        row.setOnClickListener {
            Prefs.resetDock(this)
            render()
        }
        reset.addView(row)
        content.addView(spacer())
        content.addView(reset)

        val extras = newGroup()

        val packRow = newRow(extras)
        val pack = Prefs.iconPack(this)
        packRow.findViewById<TextView>(R.id.row_title).text = "Icon pack"
        packRow.findViewById<TextView>(R.id.row_sub).text =
            "Any Nova or ADW compatible pack. Apps the pack has no icon for keep their own."
        packRow.findViewById<TextView>(R.id.row_value).text =
            if (pack.isEmpty()) "None" else appLabel(pack)
        packRow.setOnClickListener {
            startActivityForResult(Intent(this, IconPackPickerActivity::class.java), REQ_PICK_PACK)
        }
        extras.addView(packRow)

        val setupRow = newRow(extras)
        setupRow.findViewById<TextView>(R.id.row_title).text = "Detect apps again"
        setupRow.findViewById<TextView>(R.id.row_sub).text =
            "Matches your unit against known firmware families and proposes a dock"
        setupRow.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        extras.addView(setupRow)

        content.addView(spacer())
        content.addView(header("Appearance"))
        content.addView(extras)
    }

    private fun renderDriving() {
        val onOff = newGroup()
        val enabled = Prefs.driveEnabled(this)
        val row = newRow(onOff)
        row.findViewById<TextView>(R.id.row_title).text = "Drive mode"
        row.findViewById<TextView>(R.id.row_sub).text =
            "Simplifies the screen while moving. Four large targets instead of eight."
        row.findViewById<TextView>(R.id.row_value).apply {
            text = if (enabled) "On" else "Off"
            setTextColor(getColor(R.color.accent))
        }
        row.setOnClickListener {
            Prefs.setDriveEnabled(this, !enabled)
            render()
        }
        onOff.addView(row)
        content.addView(onOff)

        // Thresholds remain editable while drive mode is disabled.
        val thresholds = newGroup()
        val on = Prefs.driveOn(this)
        val off = Prefs.driveOff(this)

        val onRow = newRow(thresholds)
        onRow.findViewById<TextView>(R.id.row_title).text = "Engage above"
        onRow.findViewById<TextView>(R.id.row_sub).text = "Tap to enter exact speed"
        onRow.findViewById<TextView>(R.id.row_value).text = "$on km/h"
        onRow.setOnClickListener { editDriveThresholds() }
        thresholds.addView(onRow)

        val offRow = newRow(thresholds)
        offRow.findViewById<TextView>(R.id.row_title).text = "Return below"
        offRow.findViewById<TextView>(R.id.row_sub).text = "Tap to enter exact speed"
        offRow.findViewById<TextView>(R.id.row_value).text = "$off km/h"
        offRow.setOnClickListener { editDriveThresholds() }
        thresholds.addView(offRow)

        content.addView(spacer())
        content.addView(header("Speed"))
        content.addView(thresholds)

        val slots = newGroup()
        for ((index, slot) in Prefs.driveDock(this).withIndex()) {
            val r = newRow(slots)
            val resolved = resolve(slot)
            r.findViewById<TextView>(R.id.row_title).text = "Tile ${index + 1}"
            r.findViewById<ImageView>(R.id.row_icon).setImageDrawable(resolved)
            r.findViewById<TextView>(R.id.row_sub).apply {
                when {
                    slot.component.isEmpty() -> text = "Empty"
                    resolved == null -> {
                        text = "Not installed on this unit"
                        setTextColor(getColor(R.color.accent))
                    }
                    else -> text = labelFor(slot, appLabel(slot.component))
                }
            }
            r.setOnClickListener {
                editingDriveSlot = index
                startActivityForResult(
                    Intent(this, AppPickerActivity::class.java)
                        .putExtra(AppPickerActivity.EXTRA_SLOT, index),
                    REQ_PICK_APP
                )
            }
            slots.addView(r)
        }
        content.addView(spacer())
        content.addView(header("Tiles while moving"))
        content.addView(slots)

        val note = TextView(this).apply {
            text = "All apps is always the fourth tile."
            setTextColor(getColor(R.color.muted))
            textSize = 14f
            setPadding(dp(6), dp(10), 0, 0)
        }
        content.addView(note)
    }

    /** Two editable numeric fields; persisted in km/h regardless of display units. */
    private fun editDriveThresholds() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        fun numeric(label: String, value: Int): EditText = EditText(this).apply {
            hint = label
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(value.toString())
            selectAll()
            layout.addView(this)
        }
        val engage = numeric("Engage above (km/h)", Prefs.driveOn(this))
        val resume = numeric("Return below (km/h)", Prefs.driveOff(this))
        val dialog = AlertDialog.Builder(this)
            .setTitle("Driving thresholds")
            .setMessage("Enter values in km/h. Engage must be 5–200 km/h and at least 5 km/h higher than return.")
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val on = engage.text.toString().toIntOrNull()
                val off = resume.text.toString().toIntOrNull()
                if (on == null || off == null || on !in 5..200 || off < 0 || off > on - 5) {
                    Toast.makeText(this, "Use 5–200 km/h and keep at least a 5 km/h gap", Toast.LENGTH_LONG).show()
                } else {
                    Prefs.setDriveThresholds(this, on, off)
                    dialog.dismiss()
                    render()
                }
            }
        }
        dialog.show()
    }

    private fun notificationAccessGranted(): Boolean {
        val component = ComponentName(this, DashNotificationListener::class.java)
        return (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)
            ?.isNotificationListenerAccessGranted(component) == true
    }

    private fun locationAccessGranted(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** A single setup entry point, not a bypass of Android's permission prompts. */
    private fun requestRequiredPermissions() {
        if (!locationAccessGranted()) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                REQ_LOCATION_SETUP
            )
        } else {
            finishPermissionSetup()
        }
    }

    private fun finishPermissionSetup() {
        if (!notificationAccessGranted()) {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "Open system Settings and grant notification access", Toast.LENGTH_LONG).show()
            }
        } else if (locationAccessGranted()) {
            Toast.makeText(this, "Required permissions are already granted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION_SETUP) {
            // Continue even if location was denied; media access is independent.
            finishPermissionSetup()
            if (section == Section.PERMISSIONS) render()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized && section == Section.PERMISSIONS) render()
    }

    private fun renderPermissions() {
        val group = newGroup()
        val location = newRow(group)
        location.findViewById<TextView>(R.id.row_title).text = "GPS location"
        location.findViewById<TextView>(R.id.row_sub).text = "Used for speed and trip data"
        location.findViewById<ImageView>(R.id.row_icon).setImageResource(R.drawable.ic_qcar_gps)
        location.findViewById<TextView>(R.id.row_value).text =
            if (locationAccessGranted()) "Granted" else "Not granted"
        location.setOnClickListener { requestRequiredPermissions() }
        group.addView(location)

        val media = newRow(group)
        media.findViewById<TextView>(R.id.row_title).text = "Media notification access"
        media.findViewById<TextView>(R.id.row_sub).text =
            "Android 14: first allow restricted settings from this app's Android App info if blocked"
        media.findViewById<TextView>(R.id.row_value).text =
            if (notificationAccessGranted()) "Granted" else "Not granted"
        media.setOnClickListener { finishPermissionSetup() }
        group.addView(media)

        content.addView(header("Permission status"))
        content.addView(group)

        val actionGroup = newGroup()
        val request = newRow(actionGroup)
        request.findViewById<TextView>(R.id.row_title).text = "Request all required permissions"
        request.findViewById<TextView>(R.id.row_sub).text =
            "One setup flow: GPS permission prompt, then Android notification access settings"
        request.setOnClickListener { requestRequiredPermissions() }
        actionGroup.addView(request)
        content.addView(spacer())
        content.addView(actionGroup)

        val note = TextView(this).apply {
            text = "Android requires separate confirmation for media notification access. " +
                "Direct GPS switching also requires system privileges or a separate ADB grant; " +
                "the launcher cannot grant those privileges itself."
            setTextColor(getColor(R.color.muted))
            textSize = 14f
            setPadding(dp(8), dp(16), dp(8), dp(8))
        }
        content.addView(note)
    }

    private fun renderWallpaper() {
        val file = File(getExternalFilesDir(null), "wallpaper.jpg")
        val group = newGroup()

        val pick = newRow(group)
        pick.findViewById<TextView>(R.id.row_title).text = "Choose an image"
        pick.findViewById<TextView>(R.id.row_sub).text =
            if (file.exists()) "Currently set, ${file.length() / 1024} KB" else "None set, showing the colour wash"
        pick.setOnClickListener {
            // GET_CONTENT, not OPEN_DOCUMENT. This unit's firmware ships no DocumentsUI at
            // all, so OPEN_DOCUMENT resolves to nothing and throws, which on a launcher looks
            // like the screen simply bouncing back to home. GET_CONTENT is handled here by
            // the gallery and the vendor file manager, and it costs nothing, because the
            // result is copied to our own files dir immediately rather than held as a Uri.
            val intent = Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
            try {
                startActivityForResult(intent, REQ_PICK_IMAGE)
            } catch (e: ActivityNotFoundException) {
                // A launcher must never die because another app is absent.
                Toast.makeText(this, "No gallery or file manager on this unit", Toast.LENGTH_LONG)
                    .show()
            }
        }
        group.addView(pick)

        if (file.exists()) {
            val clear = newRow(group)
            clear.findViewById<TextView>(R.id.row_title).apply {
                text = "Remove wallpaper"
                setTextColor(getColor(R.color.accent))
            }
            clear.findViewById<TextView>(R.id.row_sub).text = "Falls back to the colour wash"
            clear.setOnClickListener {
                file.delete()
                render()
            }
            group.addView(clear)
        }
        content.addView(group)
    }

    private fun renderDisplay() {
        val gpsGroup = newGroup()
        val gpsRow = newRow(gpsGroup)
        gpsRow.findViewById<TextView>(R.id.row_title).text = "Show GPS Status"
        gpsRow.findViewById<TextView>(R.id.row_sub).text =
            "Tap to toggle location; opens Android settings without privileged permission"
        gpsRow.findViewById<ImageView>(R.id.row_icon).setImageResource(R.drawable.ic_qcar_gps)
        gpsRow.findViewById<TextView>(R.id.row_value).text =
            if (GpsControl.enabled(this)) "On" else "Off"
        gpsRow.setOnClickListener { GpsControl.toggleOrOpenSettings(this) }
        gpsGroup.addView(gpsRow)
        content.addView(header("GPS"))
        content.addView(gpsGroup)
        content.addView(spacer())

        val clockGroup = newGroup()
        val systemIs24 = DateFormat.is24HourFormat(this)
        val current = Prefs.clockFormat(this)

        addChoice(clockGroup, "Follow the unit", if (systemIs24) "Currently 24 hour" else "Currently 12 hour",
            current == Prefs.CLOCK_SYSTEM) {
            Prefs.setClockFormat(this, Prefs.CLOCK_SYSTEM); render()
        }
        addChoice(clockGroup, "12 hour", "9:59", current == 12) {
            Prefs.setClockFormat(this, 12); render()
        }
        addChoice(clockGroup, "24 hour", "21:59", current == 24) {
            Prefs.setClockFormat(this, 24); render()
        }
        content.addView(header("Clock"))
        content.addView(clockGroup)

        val unitsGroup = newGroup()
        val units = Prefs.units(this)
        addChoice(unitsGroup, "km/h", "", units == Prefs.UNITS_KMH) {
            Prefs.setUnits(this, Prefs.UNITS_KMH); render()
        }
        addChoice(unitsGroup, "mph", "", units == Prefs.UNITS_MPH) {
            Prefs.setUnits(this, Prefs.UNITS_MPH); render()
        }
        content.addView(spacer())
        content.addView(header("Speed units"))
        content.addView(unitsGroup)

        // --- night dimming ---
        val nightGroup = newGroup()
        val mode = Prefs.nightMode(this)
        val start = Prefs.nightStart(this)
        val end = Prefs.nightEnd(this)
        addChoice(nightGroup, "Automatic", "Dim between ${start}:00 and ${end}:00",
            mode == Prefs.NIGHT_AUTO) { Prefs.setNightMode(this, Prefs.NIGHT_AUTO); render() }
        addChoice(nightGroup, "Always dim", "", mode == Prefs.NIGHT_ON) {
            Prefs.setNightMode(this, Prefs.NIGHT_ON); render()
        }
        addChoice(nightGroup, "Never dim", "", mode == Prefs.NIGHT_OFF) {
            Prefs.setNightMode(this, Prefs.NIGHT_OFF); render()
        }

        val levelRow = newRow(nightGroup)
        levelRow.findViewById<TextView>(R.id.row_title).text = "How dim"
        levelRow.findViewById<TextView>(R.id.row_sub).text = "Tap to change"
        levelRow.findViewById<TextView>(R.id.row_value).text = "${Prefs.nightLevel(this)}%"
        levelRow.setOnClickListener {
            val options = listOf(20, 35, 45, 60, 75)
            val current = Prefs.nightLevel(this)
            val next = options[(options.indexOf(current).takeIf { i -> i >= 0 }?.plus(1) ?: 0) % options.size]
            Prefs.setNightLevel(this, next)
            render()
        }
        nightGroup.addView(levelRow)

        content.addView(spacer())
        content.addView(header("Night dimming"))
        content.addView(nightGroup)

        // --- idle dimming ---
        val idleGroup = newGroup()
        val idleOn = Prefs.idleDimEnabled(this)
        val idleRow = newRow(idleGroup)
        idleRow.findViewById<TextView>(R.id.row_title).text = "Dim when idle"
        idleRow.findViewById<TextView>(R.id.row_sub).text =
            "Only while this screen is showing, so navigation and video are never dimmed"
        idleRow.findViewById<TextView>(R.id.row_value).apply {
            text = if (idleOn) "On" else "Off"
            setTextColor(getColor(R.color.accent))
        }
        idleRow.setOnClickListener { Prefs.setIdleDimEnabled(this, !idleOn); render() }
        idleGroup.addView(idleRow)

        if (idleOn) {
            val delayRow = newRow(idleGroup)
            delayRow.findViewById<TextView>(R.id.row_title).text = "After"
            delayRow.findViewById<TextView>(R.id.row_sub).text = "Tap to change"
            delayRow.findViewById<TextView>(R.id.row_value).text = "${Prefs.idleMinutes(this)} min"
            delayRow.setOnClickListener {
                val options = listOf(1, 2, 3, 5, 10)
                val current = Prefs.idleMinutes(this)
                val next = options[(options.indexOf(current).takeIf { i -> i >= 0 }?.plus(1) ?: 0) % options.size]
                Prefs.setIdleMinutes(this, next)
                render()
            }
            idleGroup.addView(delayRow)
        }

        content.addView(spacer())
        content.addView(header("Idle dimming"))
        content.addView(idleGroup)

        // Without this, "Hide the trip card" from the long press menu would be a one way
        // door: no way back short of clearing app data.
        val tripGroup = newGroup()
        val tripOn = Trip.cardEnabled(this)
        val tripRow = newRow(tripGroup)
        tripRow.findViewById<TextView>(R.id.row_title).text = "Trip card"
        tripRow.findViewById<TextView>(R.id.row_sub).text =
            "Distance, average, top speed and moving time. Shown when nothing is playing."
        tripRow.findViewById<TextView>(R.id.row_value).apply {
            text = if (tripOn) "On" else "Off"
            setTextColor(getColor(R.color.accent))
        }
        tripRow.setOnClickListener { Trip.setCardEnabled(this, !tripOn); render() }
        tripGroup.addView(tripRow)

        val totalRow = newRow(tripGroup)
        totalRow.findViewById<TextView>(R.id.row_title).text = "Total distance"
        totalRow.findViewById<TextView>(R.id.row_sub).text = "Since Dash was installed"
        totalRow.findViewById<TextView>(R.id.row_value).text =
            if (Prefs.units(this) == Prefs.UNITS_MPH)
                "${(Trip.totalKm() * 0.621371).toInt()} mi"
            else "${Trip.totalKm().toInt()} km"
        totalRow.isClickable = false
        tripGroup.addView(totalRow)

        content.addView(spacer())
        content.addView(header("Trip"))
        content.addView(tripGroup)
    }


    /** Proprietary head-unit values are read-only to normal third-party apps. */
    private fun factoryControl(group: LinearLayout, title: String, explanation: String, key: String) {
        val row = newRow(group)
        row.findViewById<TextView>(R.id.row_title).text = title
        row.findViewById<TextView>(R.id.row_sub).text = explanation
        row.findViewById<TextView>(R.id.row_value).text =
            HeadUnitSettingsBridge.property(key) ?: "Stock settings"
        row.setOnClickListener { openStockSettings(title) }
        group.addView(row)
    }

    private fun openStockSettings(feature: String) {
        if (!HeadUnitSettingsBridge.openStockSettings(this)) {
            AlertDialog.Builder(this)
                .setTitle("Stock settings unavailable")
                .setMessage("Open the original head-unit Settings app to change " + feature +
                    ". Its privileged controls are not accessible to QCarDash.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun renderVolume() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val androidGroup = newGroup()
        val music = newRow(androidGroup)
        music.findViewById<TextView>(R.id.row_title).text = "Music"
        music.findViewById<TextView>(R.id.row_sub).text = "Android media volume"
        val current = music.findViewById<TextView>(R.id.row_value)
        current.text = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toString()
        androidGroup.addView(music)
        val volume = SeekBar(this).apply {
            max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            setPadding(dp(18), dp(4), dp(18), dp(12))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, level: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    try {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
                        current.text = level.toString()
                    } catch (_: SecurityException) {
                        Toast.makeText(this@SettingsActivity, "Audio control restricted", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        androidGroup.addView(volume)
        content.addView(header("Android playback"))
        content.addView(androidGroup)

        val factory = newGroup()
        factoryControl(factory, "Music (factory level)",
            "Tap to change the stock music source level", "persist.sys.volume_media")
        factoryControl(factory, "Navigation",
            "Factory navigation volume / audio mixing", "persist.sys.volume_gis")
        factoryControl(factory, "BT call",
            "Factory Bluetooth call level", "persist.sys.volume_bt")
        factoryControl(factory, "Backcar mute",
            "Mute audio during reverse-camera mode", "persist.sys.backcar_mute_enable")
        content.addView(spacer())
        content.addView(header("Factory mixer"))
        content.addView(factory)

        val appGroup = newGroup()
        val musicApp = newRow(appGroup)
        musicApp.findViewById<TextView>(R.id.row_title).text = "Music app"
        musicApp.findViewById<TextView>(R.id.row_sub).text =
            "Choose which app the QCarDash OPEN shortcut launches"
        musicApp.findViewById<TextView>(R.id.row_value).text =
            Prefs.prefs(this).getString("qcar_music_app", null)?.substringBefore('/') ?: "Poweramp"
        musicApp.setOnClickListener {
            startActivityForResult(
                Intent(this, AppPickerActivity::class.java)
                    .putExtra(AppPickerActivity.EXTRA_SLOT, -1), REQ_PICK_MUSIC
            )
        }
        appGroup.addView(musicApp)
        val stockApp = newRow(appGroup)
        stockApp.findViewById<TextView>(R.id.row_title).text = "Stock music app"
        stockApp.findViewById<TextView>(R.id.row_sub).text =
            "Change the music app used by stock launcher and factory buttons"
        stockApp.setOnClickListener { openStockSettings("stock music app") }
        appGroup.addView(stockApp)
        content.addView(spacer())
        content.addView(header("App selection"))
        content.addView(appGroup)
    }

    private fun renderNavigation() {
        val group = newGroup()
        val app = newRow(group)
        app.findViewById<TextView>(R.id.row_title).text = "Choose map app"
        app.findViewById<TextView>(R.id.row_sub).text =
            "External navigation app for QCarDash; offline Mapsforge stays embedded"
        app.findViewById<TextView>(R.id.row_value).text =
            Prefs.prefs(this).getString("qcar_navigation_app", null)
                ?.substringBefore('/') ?: "Not selected"
        app.setOnClickListener {
            startActivityForResult(
                Intent(this, AppPickerActivity::class.java)
                    .putExtra(AppPickerActivity.EXTRA_SLOT, -1), REQ_PICK_NAVIGATION
            )
        }
        group.addView(app)
        val launch = newRow(group)
        launch.findViewById<TextView>(R.id.row_title).text = "Open chosen map app"
        launch.findViewById<TextView>(R.id.row_sub).text =
            "Launch the selected external navigation application"
        launch.setOnClickListener {
            val component = Prefs.prefs(this).getString("qcar_navigation_app", null)
            if (component.isNullOrBlank()) {
                Toast.makeText(this, "Choose a map app first", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    val parts = component.split('/', limit = 2)
                    val launchIntent = if (parts.size == 2) {
                        Intent(Intent.ACTION_MAIN).setClassName(parts[0], parts[1])
                    } else packageManager.getLaunchIntentForPackage(parts[0])
                    if (launchIntent == null) throw ActivityNotFoundException()
                    startActivity(launchIntent)
                } catch (_: Exception) {
                    Toast.makeText(this, "Selected app is unavailable", Toast.LENGTH_LONG).show()
                }
            }
        }
        group.addView(launch)
        factoryControl(group, "Stock navigation app",
            "Select the app used by factory navigation keys", "persist.sys.navi_package")
        content.addView(group)
    }

    private fun renderBackcar() {
        val group = newGroup()
        factoryControl(group, "Reverse mode",
            "Stock camera mode (factory / after-market camera)", "persist.sys.backcar_type")
        factoryControl(group, "Swerve auto 360",
            "Stock automatic 360-degree camera switching", "persist.sys.360_auto_show")
        content.addView(group)
        content.addView(TextView(this).apply {
            text = "Reverse camera settings are controlled by head-unit firmware. " +
                "Tap a setting to open the stock settings app. " +
                "QCarDash does not transmit unverified MCU commands."
            setTextColor(getColor(R.color.muted))
            textSize = 14f
            setPadding(dp(8), dp(16), dp(8), dp(8))
        })
    }

    private fun renderAbout() {
        val group = newGroup()
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "1.0"
        }
        addInfo(group, "QCarDash", "Version $version")
        addInfo(group, "Stock firmware version", HeadUnitSettingsBridge.firmwareVersion())
        addInfo(group, "MCU", HeadUnitSettingsBridge.mcuVersion())
        addInfo(group, "Device info", HeadUnitSettingsBridge.deviceInfo(this))
        addInfo(group, "Screen", "${resources.displayMetrics.widthPixels} x " +
                "${resources.displayMetrics.heightPixels} at ${resources.displayMetrics.densityDpi} dpi")
        addInfo(group, "Android", android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")")
        addInfo(group, "Device", android.os.Build.MODEL + " / " + android.os.Build.BOARD)
        addInfo(group, "Source", "github.com/host90-gist/dash-car-launcher-improvement")
        content.addView(group)

        val factory = newGroup()
        val settings = newRow(factory)
        settings.findViewById<TextView>(R.id.row_title).text = "Factory settings"
        settings.findViewById<TextView>(R.id.row_sub).text =
            "Open original firmware settings (does not send MCU commands)"
        settings.setOnClickListener { openStockSettings("factory settings") }
        factory.addView(settings)

        val reset = newRow(factory)
        reset.findViewById<TextView>(R.id.row_title).text = "Factory data reset"
        reset.findViewById<TextView>(R.id.row_sub).text =
            "Open Android reset options (requires confirmation in system Settings)"
        reset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Factory data reset")
                .setMessage("A factory reset erases apps and user data. QCarDash will " +
                    "only open Android Settings, never reset the device itself.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open Android settings") { _, _ ->
                    if (!HeadUnitSettingsBridge.openFactoryResetOptions(this)) {
                        Toast.makeText(this, "System reset options unavailable", Toast.LENGTH_LONG).show()
                    }
                }.show()
        }
        factory.addView(reset)
        content.addView(spacer())
        content.addView(header("Factory"))
        content.addView(factory)
    }

    // ----------------------------------------------------------- small parts

    private fun newGroup(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.group_bg)
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun newRow(parent: ViewGroup): View =
        LayoutInflater.from(this).inflate(R.layout.settings_row, parent, false)

    private fun spacer(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(18)
        )
    }

    private fun header(text: String): TextView = TextView(this).apply {
        this.text = text.uppercase()
        setTextColor(getColor(R.color.muted))
        textSize = 12f
        letterSpacing = 0.09f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setPadding(dp(6), 0, 0, dp(8))
    }

    private fun addChoice(
        group: LinearLayout, title: String, sub: String, selected: Boolean, onPick: () -> Unit
    ) {
        val row = newRow(group)
        row.findViewById<TextView>(R.id.row_title).text = title
        row.findViewById<TextView>(R.id.row_sub).apply {
            text = sub
            visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        }
        row.findViewById<TextView>(R.id.row_value).apply {
            text = if (selected) "Selected" else ""
            setTextColor(getColor(R.color.accent))
        }
        row.setOnClickListener { onPick() }
        group.addView(row)
    }

    private fun addInfo(group: LinearLayout, title: String, value: String) {
        val row = newRow(group)
        row.findViewById<TextView>(R.id.row_title).text = title
        row.findViewById<TextView>(R.id.row_sub).visibility = View.GONE
        row.findViewById<TextView>(R.id.row_value).text = value
        row.isClickable = false
        group.addView(row)
    }

    private fun labelFor(slot: Prefs.Slot, fallback: String) =
        if (slot.label.isNotBlank()) slot.label else fallback

    private fun appLabel(component: String): String {
        val pkg = component.substringBefore('/')
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    /** Null means the slot cannot be launched, which is what dims it on the home screen. */
    private fun resolve(slot: Prefs.Slot): Drawable? {
        if (slot.component.isEmpty() || slot.component == Prefs.DRAWER) return null
        return try {
            if (slot.component.contains('/')) {
                val pkg = slot.component.substringBefore('/')
                val cls = slot.component.substringAfter('/')
                val info = packageManager.getActivityInfo(android.content.ComponentName(pkg, cls), 0)
                if (!info.enabled) null else info.loadIcon(packageManager)
            } else {
                if (packageManager.getLaunchIntentForPackage(slot.component) == null) null
                else packageManager.getApplicationIcon(slot.component)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------- results

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return

        when (requestCode) {
            REQ_PICK_APP -> {
                val component = data?.getStringExtra(AppPickerActivity.EXTRA_COMPONENT) ?: return
                val label = data.getStringExtra(AppPickerActivity.EXTRA_LABEL).orEmpty()
                when {
                    editingDriveSlot >= 0 -> {
                        Prefs.setDriveSlot(this, editingDriveSlot, Prefs.Slot(component, label))
                        editingDriveSlot = -1
                    }
                    editingSlot >= 0 -> {
                        Prefs.setSlot(this, editingSlot, Prefs.Slot(component, label))
                        editingSlot = -1
                    }
                }
                render()
            }
            REQ_PICK_NAVIGATION, REQ_PICK_MUSIC -> {
                val selected = data?.getStringExtra(AppPickerActivity.EXTRA_COMPONENT) ?: return
                if (selected == Prefs.DRAWER) {
                    Toast.makeText(this, "Choose an application", Toast.LENGTH_SHORT).show()
                    return
                }
                val key = if (requestCode == REQ_PICK_NAVIGATION)
                    "qcar_navigation_app" else "qcar_music_app"
                Prefs.prefs(this).edit().putString(key, selected).apply()
                render()
            }
            REQ_PICK_PACK -> {
                val pkg = data?.getStringExtra(IconPackPickerActivity.EXTRA_PACKAGE).orEmpty()
                Prefs.setIconPack(this, pkg)
                // The old pack's decoded icons must go, or the next screen mixes two themes.
                IconPacks.clear()
                render()
            }
            REQ_PICK_IMAGE -> {
                val uri = data?.data ?: return
                // Copy rather than hold the Uri: the picked document's permission does not
                // survive a reboot, and the home screen has to work on a cold start.
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        File(getExternalFilesDir(null), "wallpaper.jpg").outputStream().use { out ->
                            input.copyTo(out)
                        }
                    }
                } catch (e: Exception) {
                    // Unreadable pick. Leave whatever was there before.
                }
                render()
            }
        }
    }
}
