package com.jhaiian.clint.browser.webview

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HttpTrafficEntry(
    val id: Long,
    val url: String,
    val method: String,
    val destination: String,
    val requestHeaders: List<String>,
    val statusCode: Int? = null,
    val responseType: String? = null,
    val error: String? = null,
    val timeMillis: Long = System.currentTimeMillis()
)

/**
 * On-device, bounded traffic log. Query strings and sensitive headers are deliberately omitted.
 * Android WebView only exposes successful response metadata when the app itself supplies the
 * response; normal network responses are not available from shouldInterceptRequest.
 */
object HttpTrafficInspector {
    private const val MAX_ENTRIES = 150
    private var nextId = 0L
    private val mutableEntries = MutableStateFlow<List<HttpTrafficEntry>>(emptyList())
    val entries = mutableEntries.asStateFlow()

    private val safeRequestHeaders = setOf("accept", "content-type", "origin", "sec-fetch-dest", "sec-fetch-mode")

    @Synchronized
    fun recordRequest(request: WebResourceRequest) {
        val uri = request.url ?: return
        if (uri.scheme != "http" && uri.scheme != "https") return
        val safeUrl = Uri.Builder().scheme(uri.scheme).authority(uri.encodedAuthority)
            .encodedPath(uri.encodedPath).build().toString()
        val headers = request.requestHeaders.entries
            .filter { it.key.lowercase() in safeRequestHeaders }
            .map { "${it.key}: ${it.value.take(180)}" }
        add(HttpTrafficEntry(
            id = ++nextId,
            url = safeUrl.take(1200),
            method = request.method.take(12),
            destination = request.requestHeaders.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value?.take(30) ?: "unknown",
            requestHeaders = headers
        ))
    }

    @Synchronized
    fun recordHttpError(request: WebResourceRequest, response: WebResourceResponse) {
        val uri = request.url ?: return
        val safeUrl = Uri.Builder().scheme(uri.scheme).authority(uri.encodedAuthority)
            .path(uri.encodedPath).build().toString()
        add(HttpTrafficEntry(
            id = ++nextId,
            url = safeUrl.take(1200),
            method = request.method.take(12),
            destination = request.requestHeaders.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value?.take(30) ?: "unknown",
            requestHeaders = emptyList(),
            statusCode = response.statusCode,
            responseType = response.responseHeaders?.entries?.firstOrNull { it.key.equals("Content-Type", true) }?.value?.take(120),
            error = response.reasonPhrase?.take(120)
        ))
    }

    @Synchronized
    private fun add(entry: HttpTrafficEntry) {
        mutableEntries.value = (mutableEntries.value + entry).takeLast(MAX_ENTRIES)
    }

    fun clear() { mutableEntries.value = emptyList() }
}
