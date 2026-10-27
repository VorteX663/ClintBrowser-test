package com.jhaiian.clint.browser.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object BackgroundPlaybackArtwork {

    private const val MAX_SIZE = 512
    private const val MAX_BYTES = 8L * 1024 * 1024

    private val cache = LruCache<String, Bitmap>(6)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun peek(url: String): Bitmap? = cache.get(url)

    suspend fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { decode(fetch(url)) }.getOrNull()
        } ?: return null
        cache.put(url, bitmap)
        return bitmap
    }

    private fun fetch(url: String): ByteArray? {
        if (url.startsWith("data:image/")) {
            val comma = url.indexOf(',')
            if (comma < 0 || !url.substring(0, comma).endsWith(";base64")) return null
            return Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", System.getProperty("http.agent").orEmpty().ifBlank { "Mozilla/5.0 (Linux; Android)" })
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body
            if (body.contentLength() > MAX_BYTES) return null
            return body.bytes().takeIf { it.size <= MAX_BYTES }
        }
    }

    private fun decode(bytes: ByteArray?): Bitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIZE && bounds.outHeight / (sample * 2) >= MAX_SIZE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val longest = maxOf(decoded.width, decoded.height)
        if (longest <= MAX_SIZE) return decoded
        val scale = MAX_SIZE.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }
}
