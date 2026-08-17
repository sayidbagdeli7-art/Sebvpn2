package com.autovpn.app.subscription

import android.content.Context

object ChatSeenStore {
    private const val PREFS = "chat_seen_prefs"
    private const val KEY = "seen_count"

    fun load(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0)
    }

    fun save(context: Context, count: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY, count).apply()
    }
}
