package com.randyflix.data

import android.content.Context
import android.content.SharedPreferences
import com.randyflix.Settings
import com.randyflix.smb.Smb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * Menu background images from <app folder>/backgrounds on the share. One image per day,
 * stepping through the folder in name order. The file list is remembered on the Firestick
 * so the background shows immediately on start, before the share has been read.
 */
object Backgrounds {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: SharedPreferences
    private val IMAGE = setOf("jpg", "jpeg", "png", "webp")

    @Volatile
    private var images: List<String> = emptyList()
    private var day = -1L

    private val _today = MutableStateFlow<String?>(null)
    /** Today's background (a path on the share), or null for the plain background. */
    val today: StateFlow<String?> = _today

    fun load(context: Context) {
        prefs = context.getSharedPreferences("backgrounds", Context.MODE_PRIVATE)
        images = prefs.getString("list", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        pick()
    }

    /** Re-reads the folder on the share. Cheap, so it runs on every start and library refresh. */
    fun refresh() {
        scope.launch {
            val folder = Settings.backgroundsFolder
            val list = if (folder.isBlank()) emptyList()
            else try {
                Smb.list(folder)
                    .filter { !it.isDir && !it.name.startsWith(".") && it.name.substringAfterLast('.').lowercase() in IMAGE }
                    .map { "$folder/${it.name}" }
                    .sortedBy { it.lowercase() }
            } catch (e: Exception) {
                return@launch   // share unreachable: keep yesterday's list
            }
            images = list
            prefs.edit().putString("list", list.joinToString("\n")).apply()
            day = -1L
            pick()
        }
    }

    /** Called when a screen comes up, so an app left open past midnight still changes picture. */
    fun check() {
        if (today() != day) pick()
    }

    private fun today(): Long {
        val now = System.currentTimeMillis()
        return (now + TimeZone.getDefault().getOffset(now)) / 86_400_000L
    }

    private fun pick() {
        day = today()
        val list = images
        _today.value = if (list.isEmpty()) null else list[(day % list.size).toInt()]
    }
}
