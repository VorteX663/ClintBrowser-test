package com.jhaiian.clint.quiver.engine

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.preference.PreferenceManager
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.jhaiian.clint.settings.sitepermissions.SitePermissionDatabase
import com.jhaiian.clint.settings.sitepermissions.SitePermissionManager
import com.jhaiian.clint.util.registeredDomain
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object QuiverGuardWebIntegration {

    const val PREF_ENABLED = "quiver_guard_enabled"

    private const val JS_BRIDGE_NAME = "__qgBridge"

    private val scriptKey = "__qg" + UUID.randomUUID().toString().replace("-", "")

    @Volatile private var bootstrapScript: String? = null

    private val documentBypassHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val supportsDocumentStart: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    private fun bootstrapScript(context: Context): String =
        bootstrapScript ?: synchronized(this) {
            bootstrapScript ?: run {
                val body = context.assets.open("JavaScript/quiver_guard_cosmetic.js")
                    .bufferedReader().use { it.readText() }

                "(function(__qgKey){\n$body\n})('$scriptKey');".also { bootstrapScript = it }
            }
        }

    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_ENABLED, false)

    fun isExcepted(context: Context, host: String?): Boolean =
        !host.isNullOrEmpty() && SitePermissionManager.getState(
            context, host, SitePermissionDatabase.TYPE_QUIVER_GUARD_EXCEPTION
        ) != null

    fun isActiveFor(context: Context, pageUrl: String?): Boolean {
        if (!isEnabled(context)) return false
        return !isExcepted(context, hostOf(pageUrl))
    }

    fun initialize(context: Context): QuiverGuardEngine.PreloadResult {
        return QuiverGuardEngine.preload(context)
    }

    fun onCompileComplete(context: Context): Boolean =
        QuiverGuardEngine.activate(QuiverGuardPaths.databaseFile(context).absolutePath)

    fun attach(context: Context, webView: WebView, tabIdProvider: () -> String?) {
        webView.addJavascriptInterface(QuiverGuardJsBridge(context, webView, tabIdProvider), JS_BRIDGE_NAME)
        if (supportsDocumentStart) {
            WebViewCompat.addDocumentStartJavaScript(webView, bootstrapScript(context), setOf("*"))
        }
    }

    fun fallbackBootstrapScript(context: Context): String? =
        if (supportsDocumentStart) null else bootstrapScript(context)

    fun fallbackScriptletScript(pageUrl: String): String? =
        if (supportsDocumentStart) null else scriptletScriptFor(pageUrl)

    internal fun scriptletScriptFor(pageUrl: String): String? {
        val script = QuiverGuardEngine.scriptletScript(pageUrl)
        if (script.isNullOrBlank()) return null
        return "(function(){if(window['${scriptKey}a']!==true)return;\n$script\n})();"
    }

    fun allowBlockedDocument(url: String) {
        hostOf(url)?.lowercase()?.let { documentBypassHosts.add(it) }
    }

    fun mainFrameRewrite(context: Context, url: String): String? {
        if (!isActiveFor(context, url)) return null
        if (!QuiverGuardEngine.isLoaded) return null
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val check = QuiverGuardEngine.checkNetworkRequest(url, url, "document", "GET") ?: return null
        if (check.matched) return null
        return check.rewrittenUrl?.takeIf { it != url }
    }

    fun shouldInterceptRequest(
        context: Context,
        request: WebResourceRequest,
        pageUrl: String?,
        tabId: String,
        cookieManager: CookieManager?,
        onMainFrameRewrite: (String) -> Unit,
        onDocumentBlocked: (String) -> Unit,
    ): WebResourceResponse? {
        if (!ensureLoaded(context)) return null

        val requestUrl = request.url ?: return null
        val scheme = requestUrl.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null

        val urlStr = requestUrl.toString()
        val resourceType = ResourceTypeDetector.detect(request)
        val sourceUrl = if (request.isForMainFrame) urlStr else pageUrl ?: urlStr
        val method = request.method ?: "GET"

        val check = QuiverGuardEngine.checkNetworkRequest(urlStr, sourceUrl, resourceType, method) ?: return null

        if (check.matched) {
            if (!request.isForMainFrame) {
                BlockedRequestCounter.increment(tabId)
                check.redirectDataUrl?.let { decodeDataUrlResponse(it) }?.let { return it }
                return BlockedResponse.forResourceType(resourceType)
            }
            val host = requestUrl.host?.lowercase()
            if (host == null || host !in documentBypassHosts) {
                BlockedRequestCounter.increment(tabId)
                onDocumentBlocked(urlStr)
                return emptyDocument()
            }
        }

        if (request.isForMainFrame && method.equals("GET", ignoreCase = true)) {
            val rewritten = check.rewrittenUrl
            if (rewritten != null && rewritten != urlStr) {
                onMainFrameRewrite(rewritten)
                return emptyDocument()
            }
        }

        val addHeaders = LinkedHashMap<String, String>()
        check.csp?.let { addHeaders["Content-Security-Policy"] = it }

        if (check.rewrittenUrl != null || addHeaders.isNotEmpty()) {
            val sameSite = isSameSite(urlStr, sourceUrl)
            val modification = PassthroughFetcher.Modification(
                newUrl = check.rewrittenUrl,
                addResponseHeaders = addHeaders,
            )
            PassthroughFetcher.fetch(request, modification, if (sameSite) cookieManager else null)?.let { return it }
        }

        return null
    }

    fun shouldInterceptServiceWorkerRequest(
        context: Context,
        request: WebResourceRequest,
        fallbackPageUrl: String?,
        tabId: String?,
    ): WebResourceResponse? {
        if (!isEnabled(context)) return null
        val referer = request.requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
            ?.takeIf { it.isNotEmpty() }
        val pageUrl = referer ?: fallbackPageUrl
        if (isExcepted(context, hostOf(pageUrl))) return null
        if (!ensureLoaded(context)) return null

        val requestUrl = request.url ?: return null
        val scheme = requestUrl.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null

        val urlStr = requestUrl.toString()
        val resourceType = ResourceTypeDetector.detect(request)
        val check = QuiverGuardEngine.checkNetworkRequest(
            urlStr, pageUrl ?: urlStr, resourceType, request.method ?: "GET"
        ) ?: return null
        if (!check.matched) return null

        tabId?.let { BlockedRequestCounter.increment(it) }
        check.redirectDataUrl?.let { decodeDataUrlResponse(it) }?.let { return it }
        return BlockedResponse.forResourceType(resourceType)
    }

    private fun ensureLoaded(context: Context): Boolean {
        if (QuiverGuardEngine.isLoaded) return true
        QuiverGuardEngine.preload(context)
        return QuiverGuardEngine.isLoaded
    }

    private fun hostOf(url: String?): String? =
        url?.let { runCatching { Uri.parse(it).host }.getOrNull() }?.takeIf { it.isNotEmpty() }

    private fun isSameSite(url: String, sourceUrl: String): Boolean {
        val host = hostOf(url) ?: return false
        val sourceHost = hostOf(sourceUrl) ?: return false
        return registeredDomain(host).equals(registeredDomain(sourceHost), ignoreCase = true)
    }

    private fun emptyDocument(): WebResourceResponse =
        WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun decodeDataUrlResponse(dataUrl: String): WebResourceResponse? {
        if (!dataUrl.startsWith("data:")) return null
        val commaIndex = dataUrl.indexOf(',')
        if (commaIndex == -1) return null
        val meta = dataUrl.substring(5, commaIndex)
        if (!meta.endsWith(";base64")) return null
        val mime = meta.removeSuffix(";base64").ifBlank { "application/octet-stream" }
        val bytes = try {
            Base64.getDecoder().decode(dataUrl.substring(commaIndex + 1))
        } catch (_: IllegalArgumentException) {
            return null
        }
        return WebResourceResponse(
            mime, "binary", 200, "OK",
            BlockedResponse.BASE_HEADERS + mapOf("Content-Type" to mime),
            bytes.inputStream()
        )
    }
}
