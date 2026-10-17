package com.jhaiian.clint.browser.webview

import android.content.Context
import android.util.SparseArray
import android.view.ViewStructure
import android.view.autofill.AutofillValue
import android.webkit.WebView

class ClintWebView(context: Context) : WebView(context) {

    @Volatile
    var autofillAllowed: Boolean = true

    var incognitoTab: Boolean = false

    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        if (incognitoTab && com.jhaiian.clint.privacy.IncognitoKeyboard.isEnabled(context)) {
            com.jhaiian.clint.privacy.IncognitoKeyboard.apply(outAttrs)
        }
        return connection
    }

    var scrollListener: ((Int, Int) -> Unit)? = null

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        scrollListener?.invoke(t, oldt)
    }

    override fun destroy() {
        scrollListener = null
        super.destroy()
    }

    override fun loadUrl(url: String) {
        super.loadUrl(upgradeForHttpsOnly(url))
    }

    override fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>) {
        super.loadUrl(upgradeForHttpsOnly(url), additionalHttpHeaders)
    }

    private fun upgradeForHttpsOnly(url: String): String =
        (webViewClient as? ClintWebViewClient)?.upgradeForHttpsOnly(url) ?: url

    override fun onProvideAutofillVirtualStructure(structure: ViewStructure?, flags: Int) {
        if (!autofillAllowed) return
        super.onProvideAutofillVirtualStructure(structure, flags)
    }

    override fun autofill(values: SparseArray<AutofillValue>) {
        if (!autofillAllowed) return
        super.autofill(values)
    }
}
