package com.autovpn.app.subscription

import android.content.Context

object LastGoodConfigStore {
    private const val PREFS = "last_good_config_prefs"
    private const val KEY = "raw_link"

    fun save(context: Context, rawLink: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, rawLink).apply()
    }

    fun load(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    }
}
