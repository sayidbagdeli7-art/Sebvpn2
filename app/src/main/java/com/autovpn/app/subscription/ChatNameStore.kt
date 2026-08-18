package com.autovpn.app.subscription

import android.content.Context

object ChatNameStore {
    private const val PREFS = "chat_name_prefs"
    private const val KEY = "name"

    fun load(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
    }

    fun save(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, name).apply()
    }
}
