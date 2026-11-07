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

    var user: String
        get() = str("user", "")
        set(v) = put("user", v)

    var password: String
        get() = prefs.getString("password", "") ?: ""
        set(v) = prefs.edit().putString("password", v).apply()

    var domain: String
        get() = str("domain", "")
        set(v) = put("domain", v)

    /** Play the intro clip when the app starts. */
    var showIntro: Boolean
        get() = prefs.getBoolean("intro", true)
        set(v) = prefs.edit().putBoolean("intro", v).apply()

    val isConfigured: Boolean get() = prefs.contains("host")
}
