package com.jhaiian.clint.mediacapture

import android.content.SharedPreferences
import android.webkit.JavascriptInterface
import org.json.JSONObject

class MediaScanBridge(
    private val prefs: SharedPreferences,
    private val tabIdProvider: () -> String?
) {

    @JavascriptInterface
    fun isEnabled(): Boolean = prefs.getBoolean(MEDIA_CAPTURE_ENABLED_PREF, true)

    @JavascriptInterface
    fun report(payload: String?) {
        if (payload.isNullOrEmpty() || payload.length > MAX_PAYLOAD_CHARS || !isEnabled()) return
        val tabId = tabIdProvider() ?: return
        val message = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val items = message.optJSONArray("items") ?: return
        val candidates = (0 until minOf(items.length(), MAX_CANDIDATES)).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("u").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PageMediaCandidate(
                url = url,
                label = item.optString("l").takeIf { it.isNotBlank() },
                language = item.optString("g").takeIf { it.isNotBlank() }
            )
        }
        if (candidates.isEmpty()) return
        MediaCaptureDetector.onPageMediaFound(
            tabId,
            MediaCaptureStore.pageUrlFor(tabId),
            message.optString("f").takeIf { it.isNotBlank() },
            message.optString("ua").takeIf { it.isNotBlank() },
            candidates
        )
    }

    companion object {
        const val NAME = "ClintMediaBridge"
        const val ASSET = "media_scanner.js"
        const val RESCAN_SCRIPT = "window.__clintMediaScan&&window.__clintMediaScan({full:true});"
        private const val MAX_PAYLOAD_CHARS = 1_000_000
        private const val MAX_CANDIDATES = 200
    }
}
