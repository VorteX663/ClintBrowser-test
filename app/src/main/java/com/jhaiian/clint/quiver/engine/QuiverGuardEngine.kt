package com.jhaiian.clint.quiver.engine

import android.content.Context
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import org.json.JSONException
import org.json.JSONObject

object QuiverGuardEngine {

    private val lock = ReentrantReadWriteLock()
    private val loadLock = Any()
    private var handle: Long = 0L

    @Volatile private var lastFailure: Pair<String, PreloadResult>? = null

    val isLoaded: Boolean
        get() = lock.read { handle != 0L }

    enum class PreloadResult {

        LOADED,

        NO_DATABASE,

        FAILED,

        VERSION_MISMATCH,

        BAD_HEADER,

        BAD_CHECKSUM,

        FLATBUFFER_PARSING_ERROR;

        val requiresRecompile: Boolean
            get() = this != LOADED && this != NO_DATABASE && this != FAILED
    }

    fun preload(context: Context): PreloadResult {
        if (isLoaded) return PreloadResult.LOADED
        val file = QuiverGuardPaths.databaseFile(context)
        if (!file.exists()) return PreloadResult.NO_DATABASE
        return synchronized(loadLock) {
            if (isLoaded) return PreloadResult.LOADED
            val signature = signatureOf(file)
            lastFailure?.let { (failedSignature, result) ->
                if (failedSignature == signature) return result
            }
            val newHandle = QuiverGuardNative.nativeLoadEngine(file.absolutePath)
            val failure = failureFor(newHandle)
            if (failure != null) {
                lastFailure = signature to failure
                return failure
            }
            lastFailure = null
            install(newHandle)
            PreloadResult.LOADED
        }
    }

    fun activate(path: String): Boolean {
        return synchronized(loadLock) {
            val newHandle = QuiverGuardNative.nativeLoadEngine(path)
            if (failureFor(newHandle) != null) return false
            lastFailure = null
            install(newHandle)
            true
        }
    }

    private fun install(newHandle: Long) {
        lock.write {
            val old = handle
            handle = newHandle
            if (old != 0L) QuiverGuardNative.nativeDestroyEngine(old)
        }
    }

    private fun failureFor(newHandle: Long): PreloadResult? = when (newHandle) {
        0L -> PreloadResult.FAILED
        -1L -> PreloadResult.VERSION_MISMATCH
        -2L -> PreloadResult.BAD_HEADER
        -3L -> PreloadResult.BAD_CHECKSUM
        -4L -> PreloadResult.FLATBUFFER_PARSING_ERROR
        else -> null
    }

    private fun signatureOf(file: File): String = "${file.length()}:${file.lastModified()}"

    data class NetworkCheck(
        val matched: Boolean,

        val redirectDataUrl: String?,
        val rewrittenUrl: String?,
        val csp: String?,
    )

    fun checkNetworkRequest(url: String, sourceUrl: String, requestType: String, method: String): NetworkCheck? =
        lock.read {
            if (handle == 0L) return@read null
            parseNetworkCheck(
                QuiverGuardNative.nativeCheckNetworkRequest(handle, url, sourceUrl, requestType, method)
            )
        }

    fun cosmeticResourcesJson(url: String): String =
        lock.read {
            if (handle == 0L) return@read ""
            QuiverGuardNative.nativeUrlCosmeticResources(handle, url)
        }

    fun scriptletScript(url: String): String? =
        lock.read {
            if (handle == 0L) return@read null
            QuiverGuardNative.nativeScriptletScript(handle, url)
        }

    fun hiddenClassIdSelectorsJson(classesJson: String, idsJson: String, exceptionsJson: String): String =
        lock.read {
            if (handle == 0L) return@read "[]"
            QuiverGuardNative.nativeHiddenClassIdSelectors(handle, classesJson, idsJson, exceptionsJson)
        }

    private fun parseNetworkCheck(json: String): NetworkCheck? {
        if (json.isEmpty()) return null
        return try {
            val obj = JSONObject(json)
            if (obj.has("error")) {
                null
            } else {
                NetworkCheck(
                    matched = obj.optBoolean("matched", false),
                    redirectDataUrl = obj.stringOrNull("redirect"),
                    rewrittenUrl = obj.stringOrNull("rewrittenUrl"),
                    csp = obj.stringOrNull("csp"),
                )
            }
        } catch (e: JSONException) {
            null
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)
}
