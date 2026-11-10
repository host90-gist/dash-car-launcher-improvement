package com.debashis.carlauncher

import android.util.Xml
import org.mapsforge.core.model.LatLong
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale

/**
 * Offline GPX/CSV import. No network services or third-party navigation apps.
 *
 * Routes are recorded tracks or explicitly supplied route points; these are
 * NOT recalculated on roads and do not provide spoken navigation.
 */
object OfflineNavigationData {
    data class Poi(val point: LatLong, val title: String)
    data class Gpx(val segments: List<List<LatLong>>, val waypoints: List<Poi>) {
        val pointCount: Int get() = segments.sumOf { it.size }
    }

    private fun coordinates(lat: String?, lon: String?): LatLong? {
        val latitude = lat?.trim()?.toDoubleOrNull() ?: return null
        val longitude = lon?.trim()?.toDoubleOrNull() ?: return null
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -85.0..85.0 || longitude !in -180.0..180.0) return null
        return LatLong(latitude, longitude)
    }

    /**
     * Android's platform XML parser is streaming and suitable for large
     * exported tracks. Segment boundaries are kept, so discontinuous recorded
     * tracks do not get joined by straight lines across the countryside.
     */
    fun parseGpx(stream: InputStream): Gpx {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(stream, "UTF-8")
        }
        val segments = ArrayList<List<LatLong>>()
        val waypoints = ArrayList<Poi>()
        var trackSegment: MutableList<LatLong>? = null
        var routeSegment: MutableList<LatLong>? = null
        var pendingWaypoint: LatLong? = null
        var pendingName = ""
        var inName = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.substringAfter(':').lowercase(Locale.US)) {
                        "trkseg" -> trackSegment = ArrayList()
                        "rte" -> routeSegment = ArrayList()
                        "trkpt" -> {
                            val point = coordinates(
                                parser.getAttributeValue(null, "lat"),
                                parser.getAttributeValue(null, "lon"))
                            if (point != null) {
                                val segment = trackSegment ?: ArrayList<LatLong>().also {
                                    trackSegment = it
                                }
                                if (segment.size < 60000) segment.add(point)
                            }
                        }
                        "rtept" -> {
                            val point = coordinates(
                                parser.getAttributeValue(null, "lat"),
                                parser.getAttributeValue(null, "lon"))
                            if (point != null && routeSegment != null &&
                                routeSegment!!.size < 60000) routeSegment!!.add(point)
                        }
                        "wpt" -> {
                            pendingWaypoint = coordinates(
                                parser.getAttributeValue(null, "lat"),
                                parser.getAttributeValue(null, "lon"))
                            pendingName = "Waypoint"
                        }
                        "name" -> inName = pendingWaypoint != null
                    }
                }
                XmlPullParser.TEXT -> if (inName && parser.text.isNotBlank())
                    pendingName = parser.text.trim().take(90)
                XmlPullParser.END_TAG -> {
                    when (parser.name.substringAfter(':').lowercase(Locale.US)) {
                        "trkseg" -> {
                            trackSegment?.takeIf { it.size > 1 }?.let { segments.add(it) }
                            trackSegment = null
                        }
                        "rte" -> {
                            routeSegment?.takeIf { it.size > 1 }?.let { segments.add(it) }
                            routeSegment = null
                        }
                        "wpt" -> {
                            pendingWaypoint?.let {
                                if (waypoints.size < 5000) waypoints.add(Poi(it, pendingName))
                            }
                            pendingWaypoint = null
                        }
                        "name" -> inName = false
                    }
                }
            }
            event = parser.next()
        }
        trackSegment?.takeIf { it.size > 1 }?.let { segments.add(it) }
        if (segments.isEmpty() && waypoints.isEmpty()) {
            throw IllegalArgumentException("No GPX track, route points, or waypoints found")
        }
        return Gpx(segments, waypoints)
    }

    /** CSV: latitude,longitude,name OR longitude,latitude,name if header says so.
     *  Semicolons supported for common European exports. */
    fun parsePoiCsv(file: File): List<Poi> {
        val result = ArrayList<Poi>()
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            var order = 0 // lat / lon / name
            var latIndex = 0
            var lonIndex = 1
            var nameIndex = 2
            var delimiter = ','
            reader.forEachLine { raw ->
                if (result.size >= 5000) return@forEachLine
                val line = raw.trim().removePrefix("\uFEFF")
                if (line.isBlank() || line.startsWith("#")) return@forEachLine
                if (line.contains(';') && line.count { it == ';' } >
                    line.count { it == ',' }) delimiter = ';'
                val cols = line.split(delimiter).map { it.trim().trim('"') }
                if (cols.size < 2) return@forEachLine
                if (order == 0 && cols.any {
                        it.lowercase(Locale.US) in setOf(
                            "latitude", "longitude", "lat", "lon", "lng", "name", "title")
                    }) {
                    latIndex = cols.indexOfFirst { it.equals("lat", true) ||
                        it.equals("latitude", true) }
                    lonIndex = cols.indexOfFirst { it.equals("lon", true) ||
                        it.equals("lng", true) || it.equals("longitude", true) }
                    nameIndex = cols.indexOfFirst { it.equals("name", true) ||
                        it.equals("title", true) }
                    if (latIndex < 0 || lonIndex < 0) {
                        throw IllegalArgumentException("CSV needs latitude/longitude columns")
                    }
                    order = 1
                    return@forEachLine
                }
                order = 1
                val point = coordinates(cols.getOrNull(latIndex), cols.getOrNull(lonIndex))
                if (point != null) {
                    result.add(Poi(point,
                        cols.getOrNull(nameIndex)?.takeIf { it.isNotBlank() }?.take(90)
                            ?: "Camera / POI"))
                }
            }
        }
        if (result.isEmpty()) throw IllegalArgumentException(
            "No usable POIs. Use latitude,longitude,name CSV or GPX waypoints")
        return result
    }
}
