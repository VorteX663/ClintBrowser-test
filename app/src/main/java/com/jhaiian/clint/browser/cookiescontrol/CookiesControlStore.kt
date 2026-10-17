package com.jhaiian.clint.browser.cookiescontrol

import android.net.Uri
import android.webkit.CookieManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

data class CookiesControlImportResult(val imported: Int, val skipped: Int)

data class CookiesControlDeleteResult(val deleted: Int, val remaining: Int)

object CookiesControlStore {

    private const val HEADER = "# Netscape HTTP Cookie File"
    private const val HTTP_ONLY_PREFIX = "#HttpOnly_"
    private const val EPOCH_EXPIRES = "Thu, 01 Jan 1970 00:00:00 GMT"

    fun cookiePairs(manager: CookieManager, url: String): List<Pair<String, String>> {
        val raw = runCatching { manager.getCookie(url) }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val index = trimmed.indexOf('=')
            if (index < 0) trimmed to "" else trimmed.substring(0, index) to trimmed.substring(index + 1)
        }
    }

    fun count(manager: CookieManager, url: String): Int = cookiePairs(manager, url).size

    suspend fun deleteAll(manager: CookieManager, url: String): CookiesControlDeleteResult {
        val before = cookiePairs(manager, url)
        if (before.isEmpty()) return CookiesControlDeleteResult(0, 0)
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty()
        val secure = uri.scheme.equals("https", ignoreCase = true)
        val domains = domainCandidates(host)
        val paths = pathCandidates(uri.path)
        val names = before.map { it.first }.distinct()

        repeat(2) { pass ->
            val remainingNames = if (pass == 0) names else cookiePairs(manager, url).map { it.first }.distinct()
            remainingNames.forEach { name ->
                domains.forEach { domain ->
                    paths.forEach { path ->
                        val builder = StringBuilder("$name=; Max-Age=0; Expires=$EPOCH_EXPIRES; Path=$path")
                        if (domain != null) builder.append("; Domain=").append(domain)
                        if (secure) builder.append("; Secure")
                        setCookieAwait(manager, url, builder.toString())
                    }
                }
            }
            manager.flush()
        }

        val remaining = cookiePairs(manager, url).size
        return CookiesControlDeleteResult((before.size - remaining).coerceAtLeast(0), remaining)
    }

    fun buildExport(manager: CookieManager, url: String): String? {
        val pairs = cookiePairs(manager, url)
        if (pairs.isEmpty()) return null
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty()
        val secure = uri.scheme.equals("https", ignoreCase = true)
        val builder = StringBuilder()
        builder.append(HEADER).append('\n')
        builder.append("# https://curl.se/docs/http-cookies.html").append('\n')
        builder.append('\n')
        pairs.forEach { (name, value) ->
            builder.append(host).append('\t')
                .append("FALSE").append('\t')
                .append('/').append('\t')
                .append(if (secure) "TRUE" else "FALSE").append('\t')
                .append(0).append('\t')
                .append(name).append('\t')
                .append(value).append('\n')
        }
        return builder.toString()
    }

    suspend fun import(manager: CookieManager, url: String, text: String): CookiesControlImportResult {
        val hasNetscape = text.lineSequence().any { it.trim().removePrefix(HTTP_ONLY_PREFIX).split('\t').size >= 7 }
        return if (hasNetscape) importNetscape(manager, url, text) else importPairs(manager, url, text)
    }

    private suspend fun importPairs(manager: CookieManager, url: String, text: String): CookiesControlImportResult {
        val uri = Uri.parse(url)
        val secure = uri.scheme.equals("https", ignoreCase = true)
        val cleaned = text.trim().removePrefix("Cookie:").removePrefix("cookie:")
        var imported = 0
        cleaned.split(';', '\n', '\r').forEach { part ->
            val trimmed = part.trim()
            val index = trimmed.indexOf('=')
            if (index <= 0) return@forEach
            val name = trimmed.substring(0, index).trim()
            val value = trimmed.substring(index + 1).trim()
            if (name.isEmpty()) return@forEach
            val attributes = "$name=$value; Path=/" + if (secure) "; Secure" else ""
            if (setCookieAwait(manager, url, attributes)) imported++
        }
        manager.flush()
        return CookiesControlImportResult(imported, 0)
    }

    private suspend fun importNetscape(manager: CookieManager, url: String, text: String): CookiesControlImportResult {
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        val siteScheme = uri.scheme?.lowercase(Locale.ROOT) ?: "https"
        var imported = 0
        var skipped = 0
        val now = System.currentTimeMillis()

        text.lineSequence().forEach { rawLine ->
            var line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            var httpOnly = false
            if (line.startsWith(HTTP_ONLY_PREFIX, ignoreCase = true)) {
                httpOnly = true
                line = line.substring(HTTP_ONLY_PREFIX.length)
            } else if (line.startsWith("#")) {
                return@forEach
            }
            val fields = line.split('\t')
            if (fields.size < 7) return@forEach

            val rawDomain = fields[0].trim().lowercase(Locale.ROOT)
            val domain = rawDomain.removePrefix(".")
            val includeSubdomains = fields[1].trim().equals("TRUE", ignoreCase = true) || rawDomain.startsWith(".")
            val path = fields[2].trim().ifEmpty { "/" }
            val secure = fields[3].trim().equals("TRUE", ignoreCase = true)
            val expirySeconds = fields[4].trim().toLongOrNull() ?: 0L
            val name = fields[5].trim()
            val value = fields.drop(6).joinToString("\t").trim()

            if (name.isEmpty() || domain.isEmpty()) return@forEach
            if (!(host == domain || host.endsWith(".$domain"))) {
                skipped++
                return@forEach
            }
            if (expirySeconds > 0L && expirySeconds * 1000L <= now) return@forEach

            val scheme = if (secure) "https" else siteScheme
            val target = "$scheme://$domain/"
            val attributes = StringBuilder("$name=$value; Path=$path")
            if (includeSubdomains) attributes.append("; Domain=").append(domain)
            if (expirySeconds > 0L) attributes.append("; Expires=").append(formatExpires(expirySeconds * 1000L))
            if (secure) attributes.append("; Secure")
            if (httpOnly) attributes.append("; HttpOnly")

            if (setCookieAwait(manager, target, attributes.toString())) imported++ else skipped++
        }
        manager.flush()
        return CookiesControlImportResult(imported, skipped)
    }

    private suspend fun setCookieAwait(manager: CookieManager, url: String, value: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            runCatching {
                manager.setCookie(url, value) { accepted ->
                    if (continuation.isActive) continuation.resume(accepted)
                }
            }.onFailure {
                if (continuation.isActive) continuation.resume(false)
            }
        }

    private fun formatExpires(millis: Long): String {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT")
        return format.format(Date(millis))
    }

    private fun domainCandidates(host: String): List<String?> {
        val result = mutableListOf<String?>(null, host)
        if (host.isEmpty() || host.all { it.isDigit() || it == '.' } || host.contains(':')) return result
        val labels = host.split('.')
        for (index in 1..(labels.size - 2)) {
            result.add(labels.drop(index).joinToString("."))
        }
        return result
    }

    private fun pathCandidates(path: String?): List<String> {
        val result = linkedSetOf("/")
        val segments = path.orEmpty().split('/').filter { it.isNotEmpty() }
        var current = ""
        segments.forEach { segment ->
            current += "/$segment"
            result.add(current)
            result.add("$current/")
        }
        return result.toList()
    }
}
