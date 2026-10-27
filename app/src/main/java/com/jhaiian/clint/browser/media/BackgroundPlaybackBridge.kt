package com.jhaiian.clint.browser.media

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

class BackgroundPlaybackBridge(
    private val onState: (frameId: String, state: String, isTop: Boolean) -> Unit,
    private val onInfo: (frameId: String, info: BackgroundPlaybackTracker.MediaInfo) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onMediaState(frameId: String?, state: String?, isTop: Boolean) {
        val id = frameId?.takeIf { FRAME_ID.matches(it) } ?: return
        val value = state?.takeIf { it in STATES } ?: return
        mainHandler.post { onState(id, value, isTop) }
    }

    @JavascriptInterface
    fun onMediaInfo(frameId: String?, json: String?) {
        val id = frameId?.takeIf { FRAME_ID.matches(it) } ?: return
        val payload = json?.takeIf { it.length <= MAX_INFO_LENGTH } ?: return
        val info = BackgroundPlaybackTracker.MediaInfo.parse(payload) ?: return
        mainHandler.post { onInfo(id, info) }
    }

    companion object {
        const val NAME = "ClintBackgroundPlaybackBridge"
        const val STATE_PLAYING = "playing"
        const val STATE_PAUSED = "paused"
        const val STATE_GONE = "gone"
        private const val MAX_INFO_LENGTH = 1_200_000
        private val STATES = setOf(STATE_PLAYING, STATE_PAUSED, STATE_GONE)
        private val FRAME_ID = Regex("[a-z0-9]{1,48}")
    }
}
