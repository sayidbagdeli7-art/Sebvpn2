package com.autovpn.app.subscription

import android.content.Context

/** Persists the user's own GitHub token, stored only on this device. */
object GitHubTokenStore {
    private const val PREFS = "github_token_prefs"
    private const val KEY = "token"

    fun load(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
    }

    fun save(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, token).apply()
    }
}
