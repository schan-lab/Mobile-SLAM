package com.example.mobilendt

import android.content.Context

/** User settings, stored in SharedPreferences. */
object Settings {
    /** Values match the colour modes of [GlPoints.draw]. */
    const val COLOR_HEIGHT = 0
    const val COLOR_INTENSITY = 2

    const val DEFAULT_BLIND = 1.5f
    const val MAX_BLIND = 5.0f

    private const val FILE = "settings"
    private const val KEY_BLIND = "blind_m"
    private const val KEY_COLOR = "color_mode"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Points closer than this (m) are ignored by SLAM / localisation. */
    fun blind(c: Context): Float = prefs(c).getFloat(KEY_BLIND, DEFAULT_BLIND).coerceIn(0f, MAX_BLIND)
    fun setBlind(c: Context, m: Float) = prefs(c).edit().putFloat(KEY_BLIND, m.coerceIn(0f, MAX_BLIND)).apply()

    fun colorMode(c: Context): Int =
        if (prefs(c).getInt(KEY_COLOR, COLOR_INTENSITY) == COLOR_HEIGHT) COLOR_HEIGHT else COLOR_INTENSITY
    fun setColorMode(c: Context, mode: Int) = prefs(c).edit().putInt(KEY_COLOR, mode).apply()
}
