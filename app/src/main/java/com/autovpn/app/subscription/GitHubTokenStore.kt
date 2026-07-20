package com.autovpn.app.subscription

import android.content.Context

/** Persists the user's own GitHub token, used only to add channel names to their repo. */
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
