package com.autovpn.app.subscription

import android.content.Context

/** Stores the shared chat password on THIS device only. Never uploaded anywhere. */
object ChatPasswordStore {
    private const val PREFS = "chat_password_prefs"
    private const val KEY = "password"

    fun load(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
    }

    fun save(context: Context, password: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, password).apply()
    }
}
