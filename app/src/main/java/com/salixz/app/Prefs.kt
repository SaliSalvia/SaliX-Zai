package com.salixz.app

import android.content.Context

object Prefs {
    private const val FILE = "salix_prefs"
    const val KEY_DESKTOP = "desktop_mode"
    const val KEY_IMMERSIVE = "immersive"

    fun read(ctx: Context, key: String, def: Boolean = false): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(key, def)

    fun write(ctx: Context, key: String, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(key, value).apply()
    }
}
