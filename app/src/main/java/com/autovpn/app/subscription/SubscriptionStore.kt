package com.autovpn.app.subscription

import android.content.Context
import com.autovpn.app.model.SubscriptionEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the list of subscription URLs + their enabled/disabled checkbox state
 * across app restarts (SharedPreferences, no DB needed for such a small list).
 */
object SubscriptionStore {

    private const val PREFS = "subscriptions_prefs"
    private const val KEY = "subscriptions_json"

    // Original default subscription, used only the very first time the app runs.
    private val DEFAULT_URLS = listOf(
        "https://raw.githubusercontent.com/roosterkid/openproxylist/main/V2RAY_RAW.txt"
    )

    fun load(context: Context): List<SubscriptionEntry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null)
        if (raw == null) {
            val defaults = DEFAULT_URLS.map { SubscriptionEntry(it, true) }
            save(context, defaults)
            return defaults
        }
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                SubscriptionEntry(o.getString("url"), o.optBoolean("enabled", true))
            }
        } catch (e: Exception) {
            DEFAULT_URLS.map { SubscriptionEntry(it, true) }
        }
    }

    fun save(context: Context, list: List<SubscriptionEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("url", it.url)
                put("enabled", it.enabled)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun addUrl(context: Context, url: String): List<SubscriptionEntry> {
        val current = load(context).toMutableList()
        if (current.none { it.url == url }) {
            current.add(SubscriptionEntry(url, true))
            save(context, current)
        }
        return current
    }

    fun setEnabled(context: Context, url: String, enabled: Boolean): List<SubscriptionEntry> {
        val current = load(context).map {
            if (it.url == url) it.copy(enabled = enabled) else it
        }
        save(context, current)
        return current
    }

    fun remove(context: Context, url: String): List<SubscriptionEntry> {
        val current = load(context).filterNot { it.url == url }
        save(context, current)
        return current
    }
}
