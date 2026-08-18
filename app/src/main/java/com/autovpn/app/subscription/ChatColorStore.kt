package com.autovpn.app.subscription

import android.content.Context

object ChatColorStore {
    private const val PREFS = "chat_color_prefs"
    private const val KEY = "color"
    const val DEFAULT_COLOR = 0xFF6750A4L // Material default purple

    fun load(context: Context): Long {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY, -1L)
        return if (stored == -1L) DEFAULT_COLOR else stored
    }

    fun save(context: Context, color: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY, color).apply()
    }
}
