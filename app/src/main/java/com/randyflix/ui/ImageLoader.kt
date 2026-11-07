package com.randyflix.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import com.randyflix.R
import com.randyflix.smb.Smb
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Loads artwork from the Samba share (or the web, for scraper URLs), scaled down for the TV,
 * with a memory cache and a disk cache so browsing stays fast after the first visit.
 */
object ImageLoader {
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    // Pictures from the share and from the web (channel logos) load on separate threads, so a
    // slow logo site can't hold up posters and backgrounds.
    private val shareDispatcher = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
    private val webDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    private val scope = MainScope()

    fun load(view: ImageView, source: String?, width: Int, placeholder: Int = R.drawable.placeholder) {
        (view.getTag(R.id.image_job) as? Job)?.cancel()
        if (source.isNullOrEmpty()) {
            if (placeholder != 0) view.setImageResource(placeholder) else view.setImageDrawable(null)
            return
        }
        val key = "$width|$source"
        memory.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        if (placeholder != 0) view.setImageResource(placeholder) else view.setImageDrawable(null)
        val appContext = view.context.applicationContext
        val job = scope.launch {
            val dispatcher = if (isWeb(source)) webDispatcher else shareDispatcher
            val bmp = withContext(dispatcher) { runCatching { fetch(appContext, source, width, key) }.getOrNull() }
            if (bmp != null) {
                memory.put(key, bmp)
                view.setImageBitmap(bmp)
            }
        }
        view.setTag(R.id.image_job, job)
    }

    fun cancel(view: ImageView) {
        (view.getTag(R.id.image_job) as? Job)?.cancel()
    }

    fun clearCache(context: Context) {
        memory.evictAll()
        File(context.cacheDir, "img").deleteRecursively()
    }

    private fun fetch(context: Context, source: String, width: Int, key: String): Bitmap? {
        val dir = File(context.cacheDir, "img").apply { mkdirs() }
        val cached = File(dir, md5(key) + ".jpg")
        if (cached.exists()) BitmapFactory.decodeFile(cached.path)?.let { return it }

        val bytes = if (isWeb(source)) download(source) else Smb.readBytes(source)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        if (bmp.width > width) {
            val h = maxOf(1, (bmp.height.toLong() * width / bmp.width).toInt())
            val scaled = Bitmap.createScaledBitmap(bmp, width, h, true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        runCatching { FileOutputStream(cached).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        return bmp
    }

    private fun isWeb(source: String) = source.startsWith("http://") || source.startsWith("https://")

    private fun download(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 10_000
        conn.instanceFollowRedirects = true
        try {
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
