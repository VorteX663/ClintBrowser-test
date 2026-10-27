package com.jhaiian.clint.browser.delegates

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import com.jhaiian.clint.R
import com.jhaiian.clint.browser.MainActivity
import com.jhaiian.clint.quiver.QuiverGuardActivity
import com.jhaiian.clint.quiver.QuiverGuardBlockedRequest
import com.jhaiian.clint.quiver.engine.BlockedRequestCounter
import com.jhaiian.clint.quiver.engine.QuiverGuardEngine
import com.jhaiian.clint.quiver.engine.QuiverGuardWebIntegration
import com.jhaiian.clint.tabs.BrowserTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.withContext

internal fun MainActivity.observeQuiverGuardCounter() {
    BlockedRequestCounter.activeTabCount
        .launchIn(lifecycleScope)
}

internal fun MainActivity.initializeQuiverGuardEngine() {
    lifecycleScope.launch(Dispatchers.IO) {
        val result = QuiverGuardWebIntegration.initialize(this@initializeQuiverGuardEngine)
        if (result.requiresRecompile) {
            withContext(Dispatchers.Main) {
                showQuiverGuardRecompileRequiredDialog(result)
            }
        }
    }
}

internal fun MainActivity.showQuiverGuardRecompileRequiredDialog(reason: QuiverGuardEngine.PreloadResult) {
    val messageRes = when (reason) {
        QuiverGuardEngine.PreloadResult.VERSION_MISMATCH -> R.string.quiver_guard_recompile_reason_version_mismatch
        QuiverGuardEngine.PreloadResult.BAD_HEADER -> R.string.quiver_guard_recompile_reason_bad_header
        QuiverGuardEngine.PreloadResult.BAD_CHECKSUM -> R.string.quiver_guard_recompile_reason_bad_checksum
        QuiverGuardEngine.PreloadResult.FLATBUFFER_PARSING_ERROR -> R.string.quiver_guard_recompile_reason_flatbuffer_error
        else -> R.string.quiver_guard_recompile_reason_unknown
    }
    uiState.confirmDialogConfig = com.jhaiian.clint.ui.listscreen.ConfirmDialogConfig(
        title = getString(R.string.quiver_guard_recompile_required_title),
        message = getString(messageRes),
        cancelable = false,
        positiveLabel = getString(R.string.quiver_guard_recompile_required_button),
        onPositive = {
            startActivity(
                Intent(this, QuiverGuardActivity::class.java)
                    .putExtra(QuiverGuardActivity.EXTRA_AUTO_RECOMPILE, true)
            )
        }
    )
}

internal fun MainActivity.onQuiverGuardPageStarted(tab: BrowserTab, url: String) {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return
    BlockedRequestCounter.resetTab(tab.id)
    applyQuiverGuardFallbackScripts(tab, url)
}

internal fun MainActivity.onQuiverGuardPageFinished(tab: BrowserTab, url: String) {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return
    applyQuiverGuardFallbackScripts(tab, url)
}

private fun MainActivity.applyQuiverGuardFallbackScripts(tab: BrowserTab, url: String) {
    if (QuiverGuardWebIntegration.supportsDocumentStart) return
    if (!prefs.getBoolean(QuiverGuardWebIntegration.PREF_ENABLED, false)) return

    quiverGuardJobs.remove(tab.id)?.cancel()
    quiverGuardJobs[tab.id] = lifecycleScope.launch {
        val scripts = withContext(Dispatchers.IO) {
            if (!QuiverGuardWebIntegration.isActiveFor(this@applyQuiverGuardFallbackScripts, url)) {
                emptyList()
            } else {
                listOfNotNull(
                    QuiverGuardWebIntegration.fallbackBootstrapScript(this@applyQuiverGuardFallbackScripts),
                    QuiverGuardWebIntegration.fallbackScriptletScript(url)
                )
            }
        }
        scripts.forEach { tab.webView.evaluateJavascript(it, null) }
    }
}

internal fun MainActivity.onQuiverGuardTabClosed(tab: BrowserTab) {
    quiverGuardJobs.remove(tab.id)?.cancel()
    BlockedRequestCounter.removeTab(tab.id)
}

internal fun MainActivity.onQuiverGuardEnabled(enabled: Boolean) {
    if (!enabled) {
        reloadActiveTabIfWeb()
        return
    }
    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) {
            QuiverGuardWebIntegration.initialize(this@onQuiverGuardEnabled)
        }
        reloadActiveTabIfWeb()
        if (result.requiresRecompile) {
            showQuiverGuardRecompileRequiredDialog(result)
        }
    }
}

internal fun MainActivity.onQuiverGuardDocumentBlocked(blockedUrl: String, tabId: String) {
    if (tabManager.activeTab?.id != tabId) return
    uiState.quiverGuardBlockedRequest = QuiverGuardBlockedRequest(blockedUrl = blockedUrl, tabId = tabId)
}

internal fun MainActivity.dismissQuiverGuardBlockedOverlay() {
    val request = uiState.quiverGuardBlockedRequest ?: return
    uiState.quiverGuardBlockedRequest = null
    val tab = tabManager.tabs.find { it.id == request.tabId } ?: return
    if (tab.webView.canGoBack()) {
        tab.webView.goBack()
    } else {
        tab.webView.loadUrl(getHomepageUrl())
    }
}

internal fun MainActivity.proceedQuiverGuardBlockedDocument() {
    val request = uiState.quiverGuardBlockedRequest ?: return
    uiState.quiverGuardBlockedRequest = null
    val tab = tabManager.tabs.find { it.id == request.tabId } ?: return
    QuiverGuardWebIntegration.allowBlockedDocument(request.blockedUrl)
    val headers = buildRequestHeaders(request.blockedUrl)
    if (headers != null) tab.webView.loadUrl(request.blockedUrl, headers) else tab.webView.loadUrl(request.blockedUrl)
}

private fun MainActivity.reloadActiveTabIfWeb() {
    tabManager.activeTab?.let { tab ->
        val url = tab.webView.url ?: return@let
        if (url.startsWith("http://") || url.startsWith("https://")) {
            tab.webView.reload()
        }
    }
}
