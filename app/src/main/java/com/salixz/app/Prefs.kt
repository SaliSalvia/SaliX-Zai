package com.salixz.app

import android.content.Context

object Prefs {
    private const val FILE = "salix_prefs"
    const val KEY_DESKTOP = "desktop_mode"
    const val KEY_IMMERSIVE = "immersive"
    const val KEY_PANEL_EXPANDED = "panel_expanded"

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(ctx: Context, key: String, def: Boolean = false): Boolean =
        sp(ctx).getBoolean(key, def)

    fun write(ctx: Context, key: String, value: Boolean) {
        sp(ctx).edit().putBoolean(key, value).apply()
    }
}
