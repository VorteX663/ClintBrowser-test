package com.jhaiian.clint.browser.delegates

import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.jhaiian.clint.browser.MainActivity
import com.jhaiian.clint.browser.webview.loadJsAsset
import com.jhaiian.clint.privacy.PrivacyPrefs
import com.jhaiian.clint.tabs.BrowserTab

private val privacyScriptHandlers = HashMap<String, List<ScriptHandler>>()

internal fun MainActivity.isGlobalPrivacyControlActive(): Boolean =
    prefs.getBoolean(PrivacyPrefs.GLOBAL_PRIVACY_CONTROL, PrivacyPrefs.DEFAULT_GLOBAL_PRIVACY_CONTROL)

internal fun MainActivity.addPrivacyScripts(tab: BrowserTab) {
    removePrivacyScripts(tab)
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
    val scripts = ArrayList<String>()
    if (isGlobalPrivacyControlActive()) scripts.add("global_privacy_control.js")
    if (prefs.getBoolean(PrivacyPrefs.WEBRTC_PROTECTION, PrivacyPrefs.DEFAULT_WEBRTC_PROTECTION)) scripts.add("webrtc_protection.js")
    if (scripts.isEmpty()) return
    privacyScriptHandlers[tab.id] = scripts.map {
        WebViewCompat.addDocumentStartJavaScript(tab.webView, loadJsAsset(it), setOf("*"))
    }
}

internal fun MainActivity.removePrivacyScripts(tab: BrowserTab) {
    privacyScriptHandlers.remove(tab.id)?.forEach { it.remove() }
}
