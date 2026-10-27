package com.jhaiian.clint.quiver.engine

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.FilterInputStream
import java.io.InputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

object PassthroughFetcher {

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val SKIPPED_REQUEST_HEADERS = setOf("host", "accept-encoding", "content-length", "cookie", "connection")

    data class Modification(

        val newUrl: String? = null,
        val addResponseHeaders: Map<String, String> = emptyMap(),
    ) {
        val isNoOp: Boolean
            get() = newUrl == null && addResponseHeaders.isEmpty()
    }

    fun fetch(request: WebResourceRequest, modification: Modification, cookieManager: CookieManager?): WebResourceResponse? {
        if (modification.isNoOp) return null
        val method = request.method?.uppercase() ?: "GET"
        if (method != "GET" && method != "HEAD") return null

        var response: Response? = null
        return try {
            val urlToFetch = modification.newUrl ?: request.url.toString()
            val reqBuilder = Request.Builder().url(urlToFetch)
            if (method == "HEAD") reqBuilder.head() else reqBuilder.get()
            request.requestHeaders?.forEach { (name, value) ->
                if (name.lowercase() !in SKIPPED_REQUEST_HEADERS) {
                    reqBuilder.header(name, value)
                }
            }
            val cookie = cookieManager?.let { runCatching { it.getCookie(urlToFetch) }.getOrNull() }
            if (!cookie.isNullOrEmpty()) reqBuilder.header("Cookie", cookie)

            val resp = client.newCall(reqBuilder.build()).execute()
            response = resp
            if (resp.code !in 200..299 && resp.code !in 400..599) {
                resp.close()
                return null
            }

            if (cookieManager != null) {
                for (setCookie in resp.headers.values("Set-Cookie")) {
                    runCatching { cookieManager.setCookie(urlToFetch, setCookie) }
                }
            }

            val headers = LinkedHashMap<String, String>()
            for (name in resp.headers.names()) {
                if (name.equals("Set-Cookie", ignoreCase = true)) continue
                headers[name] = resp.headers.values(name).joinToString(", ")
            }
            for ((name, value) in modification.addResponseHeaders) {
                val existingKey = headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }
                if (existingKey == null) {
                    headers[name] = value
                } else {
                    headers[existingKey] = headers.getValue(existingKey) + ", " + value
                }
            }

            val mediaType = resp.body.contentType()
            val mimeType = mediaType?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream"
            val encoding = mediaType?.charset()?.name()

            WebResourceResponse(
                mimeType,
                encoding,
                resp.code,
                resp.message.ifEmpty { "OK" },
                headers,
                ResponseStream(resp, resp.body.byteStream())
            )
        } catch (_: Exception) {
            response?.close()
            null
        }
    }

    private class ResponseStream(private val response: Response, input: InputStream) : FilterInputStream(input) {
        override fun close() {
            try {
                super.close()
            } finally {
                response.close()
            }
        }
    }
}
