package com.randyflix

import android.content.Context
import com.randyflix.smb.Smb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records crashes. Fire TV shows no message when a sideloaded app crashes (the screen just
 * closes), so the details are saved on the Firestick and, the next time RandyFlix starts, added
 * to crash-log.txt in the RandyFlix folder on the share, where they can be read from a computer.
 * The latest one is also shown in Settings → About.
 */
object CrashLog {
    private lateinit var pending: File   // not yet copied to the share
    private lateinit var latest: File    // the most recent crash, for Settings → About
    private var version = "?"

    /** The screen in front, recorded with a crash. */
    @Volatile var screen = ""

    fun install(context: Context) {
        pending = File(context.filesDir, "crash.txt")
        latest = File(context.filesDir, "crash-latest.txt")
        @Suppress("DEPRECATION")
        version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val entry = "=== $time  RandyFlix $version  screen: $screen  thread: ${thread.name}\n$trace\n"
                pending.appendText(entry)
                latest.writeText(entry)
            }
            previous?.uncaughtException(thread, e)
        }
        upload()
    }

    /** "Nov 22 14:03 · IllegalStateException: …" for Settings → About, or null. */
    fun lastSummary(): String? = runCatching {
        if (!latest.exists()) return null
        val lines = latest.readLines()
        val head = lines.firstOrNull()?.removePrefix("=== ")?.substringBefore("  RandyFlix") ?: ""
        val error = lines.getOrNull(1)?.trim()?.take(160) ?: ""
        "$head · $error"
    }.getOrNull()

    /** Adds any recorded crashes to <RandyFlix folder>/crash-log.txt on the share. */
    private fun upload() {
        if (!pending.exists() || Settings.appFolder.isBlank()) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val path = "${Settings.appFolder}/crash-log.txt"
                val before = runCatching { Smb.readBytes(path).toString(Charsets.UTF_8) }.getOrDefault("")
                Smb.writeBytes(path, (before + pending.readText()).takeLast(200_000).toByteArray())
                pending.delete()
            }
        }
    }
}
