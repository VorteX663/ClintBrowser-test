package com.jhaiian.clint.quiver.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.lang.ref.WeakReference

internal class QuiverGuardJsBridge(
    context: Context,
    webView: WebView,
    private val tabIdProvider: () -> String?,
) {

    private val appContext = context.applicationContext
    private val webViewRef = WeakReference(webView)
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun cosmetic(url: String, topUrl: String): String {
        if (!QuiverGuardWebIntegration.isActiveFor(appContext, topUrl.ifEmpty { url })) return ""
        if (!QuiverGuardEngine.isLoaded) QuiverGuardEngine.preload(appContext)
        return QuiverGuardEngine.cosmeticResourcesJson(url)
    }

    @JavascriptInterface
    fun hiddenClassIdSelectors(classesJson: String, idsJson: String, exceptionsJson: String): String =
        QuiverGuardEngine.hiddenClassIdSelectorsJson(classesJson, idsJson, exceptionsJson)

    @JavascriptInterface
    fun blockWebSocket(url: String, pageUrl: String): Boolean {
        val check = QuiverGuardEngine.checkNetworkRequest(url, pageUrl, "websocket", "GET") ?: return false
        if (!check.matched) return false
        tabIdProvider()?.let { BlockedRequestCounter.increment(it) }
        return true
    }

    @JavascriptInterface
    fun scriptletFallback(url: String) {
        if (!QuiverGuardWebIntegration.isActiveFor(appContext, url)) return
        val script = QuiverGuardWebIntegration.scriptletScriptFor(url) ?: return
        mainHandler.post {
            val webView = webViewRef.get() ?: return@post
            runCatching { webView.evaluateJavascript(script, null) }
        }
    }
}
