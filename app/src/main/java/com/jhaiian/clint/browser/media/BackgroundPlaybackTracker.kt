package com.jhaiian.clint.browser.media

import android.os.SystemClock
import org.json.JSONObject

class BackgroundPlaybackTracker {

    data class MediaInfo(
        val title: String,
        val artist: String,
        val album: String,
        val artworkUrl: String?,
        val durationMs: Long,
        val positionMs: Long,
        val rate: Float,
        val canSeek: Boolean,
        val canNext: Boolean,
        val canPrevious: Boolean,
        val receivedAt: Long
    ) {
        companion object {
            fun parse(json: String): MediaInfo? = runCatching {
                val obj = JSONObject(json)
                val duration = obj.optDouble("duration", -1.0)
                val position = obj.optDouble("position", 0.0)
                val rate = obj.optDouble("rate", 1.0)
                MediaInfo(
                    title = obj.optString("title").trim().take(MAX_TEXT),
                    artist = obj.optString("artist").trim().take(MAX_TEXT),
                    album = obj.optString("album").trim().take(MAX_TEXT),
                    artworkUrl = obj.optString("artwork").takeIf { it.isNotBlank() },
                    durationMs = if (duration.isFinite() && duration > 0) (duration * 1000).toLong() else -1L,
                    positionMs = if (position.isFinite() && position >= 0) (position * 1000).toLong() else 0L,
                    rate = if (rate.isFinite() && rate > 0) rate.toFloat() else 1f,
                    canSeek = obj.optBoolean("canSeek"),
                    canNext = obj.optBoolean("canNext"),
                    canPrevious = obj.optBoolean("canPrevious"),
                    receivedAt = SystemClock.elapsedRealtime()
                )
            }.getOrNull()

            private const val MAX_TEXT = 300
        }
    }

    private val playingFrames = mutableMapOf<String, MutableSet<String>>()
    private val lastFrames = mutableMapOf<String, String>()
    private val infos = mutableMapOf<String, MutableMap<String, MediaInfo>>()

    fun update(tabId: String, frameId: String, playing: Boolean) {
        val frames = playingFrames.getOrPut(tabId) { mutableSetOf() }
        if (playing) {
            frames.add(frameId)
            lastFrames[tabId] = frameId
        } else {
            frames.remove(frameId)
        }
    }

    fun updateInfo(tabId: String, frameId: String, info: MediaInfo) {
        infos.getOrPut(tabId) { mutableMapOf() }[frameId] = info
    }

    fun info(tabId: String): MediaInfo? {
        val frames = infos[tabId] ?: return null
        return lastFrames[tabId]?.let { frames[it] } ?: frames.values.maxByOrNull { it.receivedAt }
    }

    fun isPlaying(tabId: String): Boolean = playingFrames[tabId]?.isNotEmpty() == true

    fun lastFrame(tabId: String): String? = lastFrames[tabId]

    fun clearTab(tabId: String) {
        playingFrames.remove(tabId)
        lastFrames.remove(tabId)
        infos.remove(tabId)
    }

    fun clearAll() {
        playingFrames.clear()
        lastFrames.clear()
        infos.clear()
    }
}
