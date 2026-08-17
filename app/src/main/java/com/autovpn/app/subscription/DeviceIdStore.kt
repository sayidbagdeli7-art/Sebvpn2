package com.autovpn.app.subscription

import android.content.Context
import java.util.UUID

/** A random ID generated once per device install, used only to tell chat bubbles
 *  apart (mine vs. theirs) - never sent anywhere in plaintext, only inside the
 *  encrypted message payload. */
object DeviceIdStore {
    private const val PREFS = "device_id_prefs"
    private const val KEY = "id"

    fun getOrCreate(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY, null)
        if (existing != null) return existing
        val fresh = UUID.randomUUID().toString().take(8)
        prefs.edit().putString(KEY, fresh).apply()
        return fresh
    }
}
