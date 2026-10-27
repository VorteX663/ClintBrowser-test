package com.jhaiian.clint.translation

import android.webkit.JavascriptInterface
import androidx.preference.PreferenceManager
import com.jhaiian.clint.browser.webview.ClintWebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID
import kotlin.coroutines.resume

class PageTranslator(private val webView: ClintWebView) {

    private val context get() = webView.context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PageTranslationState())
    val state: StateFlow<PageTranslationState> = mutableState.asStateFlow()

    private var detectJob: Job? = null
    private var translateJob: Job? = null
    private var generation = 0
    @Volatile private var session: String? = null
    private var sessionFrom: String? = null
    private var sessionTo: String? = null
    private var pageUrl: String? = null

    private val script: String by lazy {
        context.assets.open("JavaScript/page_translator.js").bufferedReader().use { it.readText() }
    }

    val bridge = Bridge()

    inner class Bridge {
        @JavascriptInterface
        fun request(id: String, batch: Int, payload: String) {
            scope.launch { onBatch(id, batch, payload) }
        }

        @JavascriptInterface
        fun idle(id: String) {
            scope.launch {
                if (id == session && !mutableState.value.isEngineReady) {
                    mutableState.value = mutableState.value.copy(isEngineReady = true, isDownloading = false)
                }
            }
        }
    }

    fun onPageStarted(url: String?) {
        generation++
        detectJob?.cancel()
        translateJob?.cancel()
        session = null
        pageUrl = url
        mutableState.value = PageTranslationState()
    }

    fun onPageFinished(url: String?) {
        pageUrl = url
        if (!isWebUrl(url)) return
        if (session != null || mutableState.value.docLanguage != null) return
        val current = generation
        detectJob?.cancel()
        detectJob = scope.launch {
            evaluate(script)
            evaluate("window.__clintTranslate&&window.__clintTranslate.active()&&window.__clintTranslate.restore()")
            val raw = evaluate("window.__clintTranslate?window.__clintTranslate.detect():null")
            if (current != generation) return@launch
            val detection = parseString(raw)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@launch
            val docLanguage = LanguageDetector.detect(detection.optString("lang"), detection.optString("title") + " " + detection.optString("text"))
            val userLanguage = ClintTranslations.userLanguage(context)
            if (docLanguage == null || ClintTranslations.sameLanguage(docLanguage, userLanguage)) {
                mutableState.value = PageTranslationState(docLanguage = docLanguage, userLanguage = userLanguage)
                return@launch
            }
            val registry = TranslationRepository.registry(context)
            if (current != generation) return@launch
            val supported = registry?.fromLanguages?.any { it.equals(docLanguage, ignoreCase = true) } == true
            mutableState.value = PageTranslationState(
                docLanguage = docLanguage,
                userLanguage = userLanguage,
                isDocLanguageSupported = supported
            )
            if (supported) applyAutomaticBehavior(docLanguage)
        }
    }

    private fun applyAutomaticBehavior(docLanguage: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (ClintTranslations.isNeverTranslateSite(context, pageUrl)) return
        when (ClintTranslations.languageSetting(context, docLanguage)) {
            TranslationLanguageSetting.NEVER -> Unit
            TranslationLanguageSetting.ALWAYS -> {
                val target = ClintTranslations.defaultTarget(context, mutableState.value) ?: return
                startTranslation(docLanguage, target)
            }
            TranslationLanguageSetting.OFFER -> {
                if (prefs.getBoolean(PREF_TRANSLATION_OFFER, true) && mutableState.value.canTranslate) {
                    ClintTranslations.offerHandler?.invoke(webView)
                }
            }
        }
    }

    private fun startTranslation(from: String, to: String) {
        translateJob?.cancel()
        translateJob = scope.launch { translate(from, to) }
    }

    suspend fun translate(from: String, to: String): Boolean {
        if (!isWebUrl(pageUrl ?: webView.url)) return false
        val current = generation
        if (session != null) {
            evaluate("window.__clintTranslate&&window.__clintTranslate.restore()")
            session = null
        }
        val needsDownload = (TranslationRepository.missingSize(context, from, to) ?: 0L) > 0L
        mutableState.value = mutableState.value.copy(
            requestedFrom = from,
            requestedTo = to,
            isEngineReady = false,
            isDownloading = needsDownload,
            error = null
        )
        val prepared = TranslationEngine.prepare(context, from, to)
        if (current != generation) return true
        if (!prepared) {
            mutableState.value = mutableState.value.copy(isDownloading = false, error = "prepare")
            return false
        }
        mutableState.value = mutableState.value.copy(isDownloading = false)
        val id = UUID.randomUUID().toString()
        session = id
        sessionFrom = from
        sessionTo = to
        evaluate(script)
        evaluate("window.__clintTranslate&&window.__clintTranslate.start(${JSONObject.quote(id)})")
        return true
    }

    suspend fun restoreOriginal(): Boolean {
        translateJob?.cancel()
        session = null
        evaluate("window.__clintTranslate&&window.__clintTranslate.restore()")
        mutableState.value = mutableState.value.copy(
            requestedFrom = null,
            requestedTo = null,
            isEngineReady = false,
            isDownloading = false,
            error = null
        )
        return true
    }

    fun isNeverTranslateSite(): Boolean = ClintTranslations.isNeverTranslateSite(context, pageUrl ?: webView.url)

    fun setNeverTranslateSite(never: Boolean): Boolean = ClintTranslations.setNeverTranslateSite(context, pageUrl ?: webView.url, never)

    fun reset() {
        onPageStarted(webView.url)
    }

    fun destroy() {
        session = null
        scope.cancel()
    }

    private suspend fun onBatch(id: String, batch: Int, payload: String) {
        if (id != session) return
        val from = sessionFrom ?: return
        val to = sessionTo ?: return
        val items = runCatching { JSONArray(payload) }.getOrNull() ?: return
        val texts = List(items.length()) { items.optJSONObject(it)?.optString("t").orEmpty() }
        val html = List(items.length()) { items.optJSONObject(it)?.optBoolean("h") == true }
        val results = arrayOfNulls<String>(texts.size)
        var failed = false
        for (flag in listOf(true, false)) {
            val indices = texts.indices.filter { html[it] == flag }
            if (indices.isEmpty()) continue
            val translated = TranslationEngine.translate(context, from, to, indices.map { texts[it] }, flag)
            if (translated == null) {
                failed = true
                break
            }
            indices.forEachIndexed { position, index -> results[index] = translated[position] }
        }
        if (id != session) return
        val quotedId = JSONObject.quote(id)
        if (failed) {
            session = null
            evaluate("window.__clintTranslate&&window.__clintTranslate.fail($quotedId,$batch)")
            mutableState.value = mutableState.value.copy(error = "translate")
            return
        }
        val array = JSONArray()
        results.forEach { array.put(it ?: JSONObject.NULL) }
        evaluate("window.__clintTranslate&&window.__clintTranslate.receive($quotedId,$batch,$array)")
        if (!mutableState.value.isEngineReady) {
            mutableState.value = mutableState.value.copy(isEngineReady = true, isDownloading = false)
        }
    }

    private suspend fun evaluate(js: String): String? = suspendCancellableCoroutine { continuation ->
        runCatching {
            webView.evaluateJavascript(js) { value -> if (continuation.isActive) continuation.resume(value) }
        }.onFailure { if (continuation.isActive) continuation.resume(null) }
    }

    private fun parseString(raw: String?): String? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    }

    private fun isWebUrl(url: String?): Boolean =
        url != null && (url.startsWith("http://") || url.startsWith("https://"))
}
