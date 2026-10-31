package com.jhaiian.clint.mediacapture

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MediaCaptureDiagnostic(
    val host: String,
    val method: String,
    val destination: String,
    val extension: String,
    val acceptHint: String
)

object MediaCaptureDiagnostics {
    private const val MAX_ITEMS = 30
    private val mutableItems = MutableStateFlow<List<MediaCaptureDiagnostic>>(emptyList())
    val items = mutableItems.asStateFlow()

    fun record(host: String, method: String, destination: String, extension: String, accept: String) {
        val safeAccept = when {
            accept.contains("video/", true) -> "video"
            accept.contains("audio/", true) -> "audio"
            accept.contains("text/", true) -> "text"
            else -> "other"
        }
        val item = MediaCaptureDiagnostic(
            host = host.take(100),
            method = method.take(10),
            destination = destination.take(20).ifBlank { "unknown" },
            extension = extension.take(10),
            acceptHint = safeAccept
        )
        mutableItems.value = (mutableItems.value.filterNot { it == item } + item).takeLast(MAX_ITEMS)
    }

    fun clear() { mutableItems.value = emptyList() }
}
