package com.randyflix

import android.content.Context
import android.content.SharedPreferences

/** Connection settings for the Pi's Samba shares. */
object Settings {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    private fun str(key: String, def: String) = prefs.getString(key, def) ?: def
    private fun put(key: String, value: String) = prefs.edit().putString(key, value.trim()).apply()

    var host: String
        get() = str("host", "adamsnas")
        set(v) = put("host", v)

    /** Movies location: share name plus optional subfolder, e.g. "movies" or "media/movies". */
    var moviesPath: String
        get() = str("movies", "movies")
        set(v) = put("movies", v.trim('/', '\\', ' '))

    var tvPath: String
        get() = str("tv", "tv")
        set(v) = put("tv", v.trim('/', '\\', ' '))

    /** Play the intro clip when the app starts. */
    var showIntro: Boolean
        get() = prefs.getBoolean("intro", true)
        set(v) = prefs.edit().putBoolean("intro", v).apply()

    /** Live TV playlist (.m3u) on the share; blank turns Live TV off. */
    var livePath: String
        get() = str("live", "movies/_live/channels.m3u")
        set(v) = put("live", v.trim('/', '\\', ' '))

    /** Start the next episode automatically when one ends. */
    var autoPlayNext: Boolean
        get() = prefs.getBoolean("autoplay", true)
        set(v) = prefs.edit().putBoolean("autoplay", v).apply()

    val isConfigured: Boolean get() = prefs.contains("host")
}
