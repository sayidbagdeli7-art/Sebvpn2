package com.autovpn.app.subscription

import android.content.Context

object ChatRelayStore {
    private const val PREFS = "chat_relay_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_URL = "worker_url"
    private const val KEY_ACCESS_KEY = "access_key"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun loadWorkerUrl(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, "") ?: ""
    }

    fun saveWorkerUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_URL, url).apply()
    }

    fun loadAccessKey(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACCESS_KEY, "") ?: ""
    }

    fun saveAccessKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ACCESS_KEY, key).apply()
    }
}
