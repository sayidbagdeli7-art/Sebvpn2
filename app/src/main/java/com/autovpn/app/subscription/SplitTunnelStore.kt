package com.autovpn.app.subscription

import android.content.Context
import org.json.JSONArray

/** Persists the set of package names selected for split-tunneling.
 *  Empty set = tunnel everything (default, current behavior). */
object SplitTunnelStore {
    private const val PREFS = "split_tunnel_prefs"
    private const val KEY = "selected_packages"

    fun load(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun save(context: Context, packages: Set<String>) {
        val arr = JSONArray()
        packages.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
