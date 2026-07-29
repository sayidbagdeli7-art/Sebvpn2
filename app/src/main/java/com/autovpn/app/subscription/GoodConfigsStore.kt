package com.autovpn.app.subscription

import android.content.Context
import org.json.JSONArray

/**
 * Persists EVERY config (by its raw share-link) that has ever actually passed real
 * data - not just the single most-recent one. Newest goes to the front. Capped so
 * this can't grow forever if you try tons of different servers over time.
 */
object GoodConfigsStore {
    private const val PREFS = "good_configs_prefs"
    private const val KEY = "raw_links"
    private const val MAX_ENTRIES = 30

    fun load(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Marks a config as known-good, moving it to the front if already present. */
    fun markGood(context: Context, rawLink: String) {
        val current = load(context).toMutableList()
        current.remove(rawLink)
        current.add(0, rawLink)
        while (current.size > MAX_ENTRIES) {
            current.removeAt(current.size - 1)
        }
        save(context, current)
    }

    private fun save(context: Context, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
