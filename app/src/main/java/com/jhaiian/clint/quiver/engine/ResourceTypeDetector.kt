package com.jhaiian.clint.quiver.engine

import android.webkit.WebResourceRequest

object ResourceTypeDetector {

    private val SCRIPT_EXTENSIONS = setOf("js", "mjs", "jsx")
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif")
    private val STYLESHEET_EXTENSIONS = setOf("css", "less", "scss")
    private val FONT_EXTENSIONS = setOf("woff", "woff2", "ttf", "otf", "eot")
    private val MEDIA_EXTENSIONS = setOf("mp4", "webm", "mp3", "ogg", "flac", "wav", "avi", "mov", "m4v", "m3u8", "mkv", "opus", "m4s", "ts", "aac", "mpd")
    private val XHR_EXTENSIONS = setOf("json", "xml")

    fun detect(request: WebResourceRequest): String {
        if (request.isForMainFrame) return "document"

        val headers = request.requestHeaders.orEmpty()
        val destination = headers.headerValue("Sec-Fetch-Dest").lowercase()
        val mode = headers.headerValue("Sec-Fetch-Mode").lowercase()
        val accept = headers.headerValue("Accept").lowercase()
        val method = request.method?.uppercase() ?: "GET"

        when (destination) {
            "script", "worker", "sharedworker", "serviceworker", "audioworklet", "paintworklet" -> return "script"
            "style" -> return "stylesheet"
            "image" -> return "image"
            "font" -> return "font"
            "audio", "video", "track" -> return "media"
            "iframe", "frame", "document" -> return "sub_frame"
            "object", "embed" -> return "object"
            "report" -> return "csp_report"
            "manifest" -> return "other"
            "empty" -> return "xhr"
        }

        if (mode == "navigate" || mode == "nested-navigate") return "sub_frame"
        if (method != "GET" && method != "HEAD") return "xhr"

        when {
            accept.startsWith("text/html") || accept.startsWith("application/xhtml+xml") -> return "sub_frame"
            accept.startsWith("text/css") -> return "stylesheet"
            accept.startsWith("image/") -> return "image"
            accept.startsWith("audio/") || accept.startsWith("video/") -> return "media"
            accept.startsWith("font/") || accept.startsWith("application/font") -> return "font"
            accept.contains("javascript") || accept.contains("ecmascript") -> return "script"
            accept.contains("application/json") || accept.startsWith("text/plain") ||
                accept.startsWith("application/xml") || accept.startsWith("text/event-stream") -> return "xhr"
        }

        val path = request.url.path?.lowercase().orEmpty()
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        when (ext) {
            in SCRIPT_EXTENSIONS -> return "script"
            in IMAGE_EXTENSIONS -> return "image"
            in STYLESHEET_EXTENSIONS -> return "stylesheet"
            in FONT_EXTENSIONS -> return "font"
            in MEDIA_EXTENSIONS -> return "media"
            in XHR_EXTENSIONS -> return "xhr"
        }

        if (headers.hasHeader("Range")) return "media"
        if (headers.hasHeader("Origin") || headers.hasHeader("Content-Type") ||
            headers.headerValue("X-Requested-With").equals("XMLHttpRequest", ignoreCase = true)
        ) {
            return "xhr"
        }

        return "script"
    }

    private fun Map<String, String>.headerValue(name: String): String {
        get(name)?.let { return it }
        for ((key, value) in this) {
            if (key.equals(name, ignoreCase = true)) return value
        }
        return ""
    }

    private fun Map<String, String>.hasHeader(name: String): Boolean =
        containsKey(name) || keys.any { it.equals(name, ignoreCase = true) }
}
