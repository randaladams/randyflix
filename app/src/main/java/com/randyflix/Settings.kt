package com.randyflix

import android.content.Context
import android.content.SharedPreferences

/** Connection settings for the Pi's Samba shares, plus a few display preferences. */
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

    /**
     * The app's own folder on the share, holding channels.m3u (Live TV) and backgrounds/
     * (menu background images). Blank turns both off.
     */
    var appFolder: String
        get() = str("appfolder", "movies/_randyflix")
        set(v) = put("appfolder", v.trim('/', '\\', ' ').replace('\\', '/'))

    val livePlaylist: String get() = appFolder.takeIf { it.isNotBlank() }?.let { "$it/channels.m3u" } ?: ""
    val backgroundsFolder: String get() = appFolder.takeIf { it.isNotBlank() }?.let { "$it/backgrounds" } ?: ""

    /** Clock on the menus: 24-hour instead of 12-hour. */
    var clock24: Boolean
        get() = prefs.getBoolean("clock24", false)
        set(v) = prefs.edit().putBoolean("clock24", v).apply()

    /** Minutes without a button press before the screen saver starts; 0 = never. */
    var screenSaverMinutes: Int
        get() = prefs.getInt("saver_min", 10)
        set(v) = prefs.edit().putInt("saver_min", v).apply()

    /** All channels list order: most watched first instead of by name. */
    var channelsByUse: Boolean
        get() = prefs.getBoolean("chsort_use", false)
        set(v) = prefs.edit().putBoolean("chsort_use", v).apply()

    /** Start the next episode automatically when one ends. */
    var autoPlayNext: Boolean
        get() = prefs.getBoolean("autoplay", true)
        set(v) = prefs.edit().putBoolean("autoplay", v).apply()

    val isConfigured: Boolean get() = prefs.contains("host")
}
