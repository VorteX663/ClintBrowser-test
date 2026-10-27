package com.jhaiian.clint.translation

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal object TranslationEngine {

    private const val HOST = "appassets.androidplatform.net"
    private const val ENGINE_URL = "https://$HOST/assets/translations/engine.html"
    private const val DATA_URL = "https://$HOST/data/"
    private const val READY_TIMEOUT_MS = 30_000L
    private const val INIT_TIMEOUT_MS = 60_000L
    private const val LOAD_TIMEOUT_MS = 180_000L
    private const val TRANSLATE_TIMEOUT_MS = 120_000L
    private const val IDLE_TIMEOUT_MS = 120_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val ids = AtomicInteger()

    private var webView: WebView? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private var initialized = false
    private var loadedKey: String? = null
    private var idleJob: Job? = null
    @Volatile private var supported: Boolean? = null

    private class Host {
        @JavascriptInterface
        fun onPageReady(isSupported: Boolean) {
            supported = isSupported
            scope.launch { ready?.complete(isSupported) }
        }

        @JavascriptInterface
        fun onMessage(json: String) {
            val message = runCatching { JSONObject(json) }.getOrNull() ?: return
            if (message.optString("type") == "crash") {
                scope.launch {
                    initialized = false
                    loadedKey = null
                    failPending()
                }
                return
            }
            pending.remove(message.optInt("id", -1))?.complete(message)
        }
    }

    suspend fun isSupported(context: Context): Boolean {
        supported?.let { return it }
        return mutex.withLock {
            val result = start(context)
            scheduleIdle()
            result
        }
    }

    suspend fun prepare(context: Context, from: String, to: String): Boolean = mutex.withLock {
        cancelIdle()
        try {
            prepareLocked(context, from, to)
        } finally {
            scheduleIdle()
        }
    }

    suspend fun translate(context: Context, from: String, to: String, texts: List<String>, html: Boolean): List<String?>? = mutex.withLock {
        cancelIdle()
        try {
            if (texts.isEmpty()) return@withLock emptyList()
            if (!prepareLocked(context, from, to)) return@withLock null
            val response = call(
                JSONObject()
                    .put("type", "translate")
                    .put("key", keyOf(from, to))
                    .put("html", html)
                    .put("texts", JSONArray(texts)),
                TRANSLATE_TIMEOUT_MS
            ) ?: return@withLock null
            if (!response.optBoolean("ok")) {
                if (response.optString("error") == "not_loaded") loadedKey = null
                return@withLock null
            }
            val results = response.optJSONArray("results") ?: return@withLock null
            List(texts.size) { index -> if (results.isNull(index)) null else results.optString(index) }
        } finally {
            scheduleIdle()
        }
    }

    suspend fun unload() {
        mutex.withLock {
            if (loadedKey == null || webView == null) return@withLock
            call(JSONObject().put("type", "unload"), INIT_TIMEOUT_MS)
            loadedKey = null
        }
    }

    suspend fun dispose() {
        mutex.withLock { destroyLocked() }
    }

    private fun keyOf(from: String, to: String) = "$from>$to"

    private suspend fun prepareLocked(context: Context, from: String, to: String): Boolean {
        val key = keyOf(from, to)
        if (loadedKey == key && initialized && webView != null) return true
        if (!start(context)) return false
        val registry = TranslationRepository.registry(context) ?: return false
        val payloads = registry.payloads(from, to) ?: return false
        val records = payloads.flatMap { it.records } + registry.wasm
        if (!TranslationRepository.ensureDownloaded(context, records)) return false
        if (!initialized) {
            val response = call(
                JSONObject().put("type", "init").put("wasm", fileEntry(registry.wasm)),
                INIT_TIMEOUT_MS
            )
            if (response?.optBoolean("ok") != true) {
                if (response?.optString("error") == "unsupported") supported = false
                return false
            }
            initialized = true
        }
        val payloadArray = JSONArray()
        payloads.forEach { payload ->
            val files = JSONObject()
            payload.records.forEach { record -> files.put(record.fileType, fileEntry(record)) }
            payloadArray.put(
                JSONObject()
                    .put("from", payload.from)
                    .put("to", payload.to)
                    .put("modelName", payload.modelName)
                    .put("files", files)
            )
        }
        loadedKey = null
        val response = call(
            JSONObject().put("type", "load").put("key", key).put("from", from).put("payloads", payloadArray),
            LOAD_TIMEOUT_MS
        )
        if (response?.optBoolean("ok") != true) return false
        loadedKey = key
        return true
    }

    private fun fileEntry(record: TranslationFileRecord): JSONObject =
        JSONObject()
            .put("url", DATA_URL + TranslationRepository.relativePath(record))
            .put("compressed", true)
            .put("size", record.decompressedSize)

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun start(context: Context): Boolean = withContext(Dispatchers.Main.immediate) {
        val existing = ready
        if (webView != null && existing != null) {
            return@withContext withTimeoutOrNull(READY_TIMEOUT_MS) { existing.await() } == true
        }
        val appContext = context.applicationContext
        val deferred = CompletableDeferred<Boolean>()
        ready = deferred
        val loader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
            .addPathHandler("/data/", WebViewAssetLoader.InternalStoragePathHandler(appContext, TranslationRepository.root(appContext)))
            .build()
        val view = runCatching { WebView(appContext) }.getOrNull()
        if (view == null) {
            supported = false
            ready = null
            return@withContext false
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkImage = true
        }
        view.addJavascriptInterface(Host(), "ClintEngineHost")
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                loader.shouldInterceptRequest(request.url)
                    ?: WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                if (webView === view) {
                    webView = null
                    ready = null
                    initialized = false
                    loadedKey = null
                    failPending()
                }
                runCatching { view.destroy() }
                return true
            }
        }
        webView = view
        initialized = false
        loadedKey = null
        view.loadUrl(ENGINE_URL)
        val result = withTimeoutOrNull(READY_TIMEOUT_MS) { deferred.await() } == true
        if (!result) destroyLocked()
        result
    }

    private suspend fun call(message: JSONObject, timeoutMs: Long): JSONObject? {
        val view = webView ?: return null
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        message.put("id", id)
        val script = "window.ClintEngine&&window.ClintEngine.call(${JSONObject.quote(message.toString())})"
        withContext(Dispatchers.Main.immediate) { view.evaluateJavascript(script, null) }
        val response = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pending.remove(id)
        if (response == null) destroyLocked()
        return response
    }

    private fun failPending() {
        val failure = JSONObject().put("ok", false).put("error", "crash")
        pending.values.forEach { it.complete(failure) }
        pending.clear()
    }

    private suspend fun destroyLocked() {
        withContext(Dispatchers.Main.immediate) {
            val view = webView
            webView = null
            ready = null
            initialized = false
            loadedKey = null
            failPending()
            view?.let {
                runCatching {
                    it.stopLoading()
                    it.destroy()
                }
            }
        }
    }

    private fun cancelIdle() {
        idleJob?.cancel()
        idleJob = null
    }

    private fun scheduleIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            mutex.withLock { destroyLocked() }
        }
    }
}
