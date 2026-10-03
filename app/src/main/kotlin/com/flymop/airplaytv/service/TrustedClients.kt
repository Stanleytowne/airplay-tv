package com.flymop.airplaytv.service

import android.content.Context
import android.util.Base64

/** Public pairing keys only. IP addresses and PIN codes are never persisted. */
class TrustedClients(context: Context) {
    private val preferences = context.getSharedPreferences("trusted_clients", Context.MODE_PRIVATE)

    private fun validKey(key: String): Boolean = try {
        key.length == 44 && Base64.decode(key, Base64.NO_WRAP).size == 32
    } catch (_: IllegalArgumentException) {
        false
    }

    @Synchronized fun contains(key: String): Boolean =
        validKey(key) && preferences.getStringSet("keys", emptySet())!!.contains(key)

    @Synchronized fun remember(key: String) {
        if (!validKey(key)) return
        val keys = preferences.getStringSet("keys", emptySet())!!.toMutableSet()
        if (key in keys || keys.size >= 64) return
        keys.add(key)
        // Complete the small write before native pairing proceeds to key verification.
        preferences.edit().putStringSet("keys", keys).commit()
    }

    @Synchronized fun clear() {
        preferences.edit().clear().commit()
    }
}
