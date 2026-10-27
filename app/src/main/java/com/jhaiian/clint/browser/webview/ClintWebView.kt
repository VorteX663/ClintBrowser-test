package com.jhaiian.clint.browser.webview

import android.annotation.SuppressLint
import android.content.Context
import android.util.SparseArray
import android.view.MotionEvent
import android.view.ViewStructure
import android.view.autofill.AutofillValue
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.jhaiian.clint.browser.media.TabAudioState

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

    private var touchDownTime = -1L
    private var touchStartedAtTop = false
    private var touchOverscrolledTop = false

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        scrollListener?.invoke(t, oldt)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchDownTime = event.downTime
            touchStartedAtTop = !canScrollVertically(-1)
            touchOverscrolledTop = false
        }
        return super.onTouchEvent(event)
    }

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        if (clampedY && scrollY <= 0 && touchStartedAtTop) touchOverscrolledTop = true
    }

    fun pullToRefreshVerdict(downTime: Long): Boolean? = when {
        downTime != touchDownTime -> null
        !touchStartedAtTop -> false
        touchOverscrolledTop -> true
        else -> null
    }

    var destroyListener: (() -> Unit)? = null

    val translator = com.jhaiian.clint.translation.PageTranslator(this)

    val audioState = TabAudioState()

    private val nativeMuteSupported: Boolean by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO) }

    fun setAudioMuted(muted: Boolean) {
        audioState.muted = muted
        if (nativeMuteSupported) {
            WebViewCompat.setAudioMuted(this, muted)
        } else {
            evaluateJavascript(TabMediaControl.muteScript(muted), null)
        }
    }

    fun reapplyAudioMute() {
        if (audioState.muted && !nativeMuteSupported) evaluateJavascript(TabMediaControl.muteScript(true), null)
    }

    var keepVisibleInBackground: () -> Boolean = { false }

    var parked: Boolean = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (parked) return false
        return super.dispatchTouchEvent(event)
    }

    private var actualWindowVisibility = VISIBLE
    private var appliedWindowVisibility = VISIBLE

    override fun onWindowVisibilityChanged(visibility: Int) {
        actualWindowVisibility = visibility
        appliedWindowVisibility = effectiveWindowVisibility()
        super.onWindowVisibilityChanged(appliedWindowVisibility)
    }

    fun refreshWindowVisibility() {
        val effective = effectiveWindowVisibility()
        if (effective == appliedWindowVisibility) return
        appliedWindowVisibility = effective
        super.onWindowVisibilityChanged(effective)
    }

    private fun effectiveWindowVisibility(): Int =
        if (actualWindowVisibility != VISIBLE && keepVisibleInBackground()) VISIBLE else actualWindowVisibility

    override fun destroy() {
        scrollListener = null
        translator.destroy()
        destroyListener?.let { listener ->
            destroyListener = null
            listener()
        }
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
