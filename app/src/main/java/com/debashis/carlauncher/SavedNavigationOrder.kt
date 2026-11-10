package com.debashis.carlauncher

import android.content.Context
import org.json.JSONArray

/**
 * Saved Routes ordering and per-waypoint visibility. Original imported GPX/CSV/VCF
 * files are kept unmodified so moving/removing a waypoint cannot corrupt a
 * source file containing thousands of locations.
 */
object SavedNavigationOrder {
    private const val ROUTES = "qcar_saved_route_order_v2"
    private const val POIS = "qcar_saved_poi_order_v2"
    private const val HIDDEN_POIS = "qcar_saved_hidden_pois_v2"

    fun routeKey(fileName: String): String = fileName
    fun pointKey(fileName: String, originalIndex: Int): String =
        "$fileName#$originalIndex"

    private fun read(context: Context, key: String): List<String> {
        val raw = Prefs.prefs(context).getString(key, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                .distinct()
        } catch (_: Exception) { emptyList() }
    }

    private fun save(context: Context, key: String, values: List<String>) {
        val array = JSONArray()
        values.distinct().forEach { array.put(it) }
        Prefs.prefs(context).edit().putString(key, array.toString()).apply()
    }

    private fun category(routes: Boolean) = if (routes) ROUTES else POIS

    fun ordered(context: Context, routes: Boolean, available: List<String>): List<String> {
        val persisted = read(context, category(routes))
        val availableSet = available.toHashSet()
        val persistedSet = persisted.toHashSet()
        return (persisted.filter { it in availableSet } +
            available.filterNot { it in persistedSet }).distinct()
    }

    fun move(context: Context, routes: Boolean, available: List<String>,
             selectedKey: String, direction: Int): Boolean {
        val list = ordered(context, routes, available).toMutableList()
        val index = list.indexOf(selectedKey)
        val other = index + direction
        if (index < 0 || other !in list.indices) return false
        val temp = list[index]
        list[index] = list[other]
        list[other] = temp
        save(context, category(routes), list)
        return true
    }

    fun dropRoute(context: Context, routeKey: String, available: List<String>) {
        save(context, ROUTES, ordered(context, true, available).filterNot { it == routeKey })
    }

    fun hiddenPoiKeys(context: Context): Set<String> =
        Prefs.prefs(context).getStringSet(HIDDEN_POIS, emptySet()).orEmpty().toSet()

    fun isPoiHidden(context: Context, key: String): Boolean =
        key in Prefs.prefs(context).getStringSet(HIDDEN_POIS, emptySet()).orEmpty()

    fun hidePoi(context: Context, key: String) {
        val hidden = Prefs.prefs(context).getStringSet(HIDDEN_POIS, emptySet())
            .orEmpty().toMutableSet()
        hidden.add(key)
        Prefs.prefs(context).edit().putStringSet(HIDDEN_POIS, hidden).apply()
        save(context, POIS, read(context, POIS).filterNot { it == key })
    }
}
