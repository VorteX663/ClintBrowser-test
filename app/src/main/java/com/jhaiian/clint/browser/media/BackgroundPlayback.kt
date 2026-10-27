package com.jhaiian.clint.browser.media

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object BackgroundPlayback {
    const val PREF_ENABLED = "background_playback_enabled"
    const val DEFAULT_ENABLED = true

    sealed interface Command {
        data object Play : Command
        data object Pause : Command
        data object Stop : Command
        data object Next : Command
        data object Previous : Command
        data class SeekTo(val positionMs: Long) : Command
    }

    data class Session(
        val tabId: String,
        val title: String,
        val subtitle: String,
        val playing: Boolean,
        val album: String = "",
        val artworkUrl: String? = null,
        val durationMs: Long = -1L,
        val positionMs: Long = -1L,
        val speed: Float = 1f,
        val positionUpdatedAt: Long = 0L,
        val canSeek: Boolean = false,
        val canSkipNext: Boolean = false,
        val canSkipPrevious: Boolean = false
    )

    private val state = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = state.asStateFlow()

    @Volatile
    var commandHandler: ((Command) -> Unit)? = null

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_ENABLED, DEFAULT_ENABLED)

    fun publish(session: Session) {
        state.value = session
    }

    fun clear() {
        state.value = null
    }

    fun send(command: Command) {
        val handler = commandHandler
        if (handler == null) {
            clear()
            return
        }
        handler(command)
    }
}
