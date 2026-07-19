package com.autovpn.app.subscription

import android.content.Context

/** Persists the user's chosen CDN "front" domain (SNI) across app restarts. */
object CdnFrontStore {
    private const val PREFS = "cdn_front_prefs"
    private const val KEY = "front_domain"

    fun load(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
    }

    fun save(context: Context, domain: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, domain).apply()
    }
}
