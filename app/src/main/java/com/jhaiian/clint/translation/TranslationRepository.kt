package com.jhaiian.clint.translation

import android.content.Context
import com.jhaiian.clint.downloads.ClintDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal data class TranslationFileRecord(
    val id: String,
    val name: String,
    val fileType: String,
    val from: String,
    val to: String,
    val version: String,
    val location: String,
    val hash: String,
    val size: Long,
    val decompressedSize: Long
)

internal data class TranslationModelPayload(
    val from: String,
    val to: String,
    val records: List<TranslationFileRecord>
) {
    val modelName: String get() = records.firstOrNull { it.fileType == "model" }?.name.orEmpty()
}

internal class TranslationRegistry(
    val wasm: TranslationFileRecord,
    private val pairs: Map<Pair<String, String>, List<TranslationFileRecord>>
) {
    val fromLanguages: List<String> = buildList {
        pairs.keys.filter { it.second == PIVOT }.forEach { add(it.first) }
        if (pairs.keys.any { it.first == PIVOT }) add(PIVOT)
    }.distinct()

    val toLanguages: List<String> = buildList {
        pairs.keys.filter { it.first == PIVOT }.forEach { add(it.second) }
        if (pairs.keys.any { it.second == PIVOT }) add(PIVOT)
    }.distinct()

    val modelLanguages: List<String> = (fromLanguages + toLanguages).distinct().filter { it != PIVOT }

    val allRecords: List<TranslationFileRecord> get() = pairs.values.flatten() + wasm

    fun payloads(from: String, to: String): List<TranslationModelPayload>? {
        if (from == to) return null
        pairs[from to to]?.let { return listOf(TranslationModelPayload(from, to, it)) }
        val first = pairs[from to PIVOT] ?: return null
        val second = pairs[PIVOT to to] ?: return null
        return listOf(TranslationModelPayload(from, PIVOT, first), TranslationModelPayload(PIVOT, to, second))
    }

    fun recordsForLanguage(code: String): List<TranslationFileRecord> =
        pairs.filterKeys { (from, to) -> (from == code && to == PIVOT) || (from == PIVOT && to == code) }.values.flatten()

    companion object {
        const val PIVOT = "en"
    }
}

internal object TranslationRepository {

    private const val SETTINGS_BASE = "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/"
    private const val ATTACHMENTS_BASE = "https://firefox-settings-attachments.cdn.mozilla.net/"
    private const val MODELS_COLLECTION = "translations-models-v2"
    private const val WASM_COLLECTION = "translations-wasm-v2"
    private const val MODEL_MAJOR_VERSION = 3
    private const val WASM_MAJOR_VERSION = 4
    private const val REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private const val RETRY_INTERVAL_MS = 10L * 60L * 1000L
    private val ACCEPTED_FILTERS = setOf("", "env.appinfo.OS == 'Android'")

    private val registryMutex = Mutex()
    private val downloadMutex = Mutex()
    @Volatile private var cached: TranslationRegistry? = null
    @Volatile private var cachedAt = 0L

    private val client by lazy {
        ClintDownloadManager.httpClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun root(context: Context): File = File(context.applicationContext.filesDir, "translations")

    private fun registryDir(context: Context) = File(root(context), "registry")

    fun filesDir(context: Context): File = File(root(context), "files")

    fun fileFor(context: Context, record: TranslationFileRecord): File = File(filesDir(context), "${record.id}.zst")

    fun isDownloaded(context: Context, record: TranslationFileRecord): Boolean = fileFor(context, record).isFile

    fun relativePath(record: TranslationFileRecord): String = "files/${record.id}.zst"

    suspend fun registry(context: Context): TranslationRegistry? = withContext(Dispatchers.IO) {
        registryMutex.withLock {
            val now = System.currentTimeMillis()
            cached?.takeIf { now - cachedAt < REFRESH_INTERVAL_MS }?.let { return@withLock it }
            val dir = registryDir(context).apply { mkdirs() }
            val modelsFile = File(dir, "models.json")
            val wasmFile = File(dir, "wasm.json")
            val fresh = modelsFile.isFile && wasmFile.isFile &&
                now - minOf(modelsFile.lastModified(), wasmFile.lastModified()) < REFRESH_INTERVAL_MS
            var fetched = false
            if (!fresh) {
                val models = fetchCollection(MODELS_COLLECTION)
                val wasm = fetchCollection(WASM_COLLECTION)
                if (models != null && wasm != null && parse(models, wasm) != null) {
                    writeAtomically(modelsFile, models)
                    writeAtomically(wasmFile, wasm)
                    fetched = true
                }
            }
            val models = modelsFile.takeIf { it.isFile }?.readText()
            val wasm = wasmFile.takeIf { it.isFile }?.readText()
            val registry = if (models != null && wasm != null) parse(models, wasm) else null
            if (registry != null) {
                cached = registry
                cachedAt = when {
                    fetched -> now
                    fresh -> modelsFile.lastModified()
                    else -> now - REFRESH_INTERVAL_MS + RETRY_INTERVAL_MS
                }
            }
            registry ?: cached
        }
    }

    private fun fetchCollection(collection: String): String? = runCatching {
        val request = Request.Builder().url("$SETTINGS_BASE$collection/records").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body.string()
        }
    }.getOrNull()

    private fun writeAtomically(target: File, content: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(content)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    private fun parse(modelsJson: String, wasmJson: String): TranslationRegistry? = runCatching {
        val wasm = records(wasmJson)
            .filter { it.name == "bergamot-translator" && majorOf(it.version) == WASM_MAJOR_VERSION }
            .maxWithOrNull { a, b -> compareVersions(a.version, b.version) } ?: return null
        val pairs = records(modelsJson)
            .filter { majorOf(it.version) == MODEL_MAJOR_VERSION && it.from.isNotEmpty() && it.to.isNotEmpty() }
            .groupBy { it.from to it.to }
            .mapNotNull { (pair, list) ->
                val best = list.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }?.version ?: return@mapNotNull null
                val selected = list.filter { it.version == best }.distinctBy { it.fileType }
                val types = selected.map { it.fileType }.toSet()
                val hasVocab = "vocab" in types || ("srcvocab" in types && "trgvocab" in types)
                if ("model" in types && hasVocab) pair to selected else null
            }
            .toMap()
        if (pairs.isEmpty()) null else TranslationRegistry(wasm, pairs)
    }.getOrNull()

    private fun records(json: String): List<TranslationFileRecord> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            if (item.optString("filter_expression").trim() !in ACCEPTED_FILTERS) return@mapNotNull null
            val attachment = item.optJSONObject("attachment") ?: return@mapNotNull null
            val version = item.optString("version")
            if (!version.matches(Regex("\\d+(\\.\\d+)*"))) return@mapNotNull null
            TranslationFileRecord(
                id = item.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9-]+")) } ?: return@mapNotNull null,
                name = item.optString("name"),
                fileType = item.optString("fileType"),
                from = item.optString("sourceLanguage"),
                to = item.optString("targetLanguage"),
                version = version,
                location = attachment.optString("location").takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                hash = attachment.optString("hash"),
                size = attachment.optLong("size"),
                decompressedSize = item.optLong("decompressedSize")
            )
        }
    }

    private fun majorOf(version: String): Int? = version.substringBefore('.').toIntOrNull()

    private fun compareVersions(first: String, second: String): Int {
        val a = first.split('.').map { it.toIntOrNull() ?: 0 }
        val b = second.split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    suspend fun missingSize(context: Context, from: String, to: String): Long? {
        val registry = registry(context) ?: return null
        val payloads = registry.payloads(from, to) ?: return null
        val records = payloads.flatMap { it.records } + registry.wasm
        return records.filterNot { isDownloaded(context, it) }.sumOf { it.size }
    }

    suspend fun ensureDownloaded(context: Context, records: List<TranslationFileRecord>): Boolean = withContext(Dispatchers.IO) {
        downloadMutex.withLock { downloadLocked(context, records) }
    }

    private suspend fun downloadLocked(context: Context, records: List<TranslationFileRecord>): Boolean {
        filesDir(context).listFiles()?.forEach { if (it.name.endsWith(".part")) it.delete() }
        return records.distinctBy { it.id }.all { record -> isDownloaded(context, record) || download(context, record) }
    }

    private suspend fun download(context: Context, record: TranslationFileRecord): Boolean {
        val target = fileFor(context, record)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${record.id}.part")
        val success = runCatching {
            val request = Request.Builder().url(ATTACHMENTS_BASE + record.location).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val digest = MessageDigest.getInstance("SHA-256")
                response.body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                hash.equals(record.hash, ignoreCase = true) && temp.renameTo(target)
            }
        }.getOrDefault(false)
        if (!success) temp.delete()
        return success
    }

    suspend fun downloadLanguage(context: Context, code: String): Boolean {
        val registry = registry(context) ?: return false
        val records = registry.recordsForLanguage(code)
        if (records.isEmpty()) return false
        return withContext(Dispatchers.IO) {
            downloadMutex.withLock {
                val success = downloadLocked(context, records + registry.wasm)
                if (success) pruneOrphans(context, registry)
                success
            }
        }
    }

    suspend fun deleteLanguage(context: Context, code: String): Boolean = withContext(Dispatchers.IO) {
        val registry = registry(context) ?: return@withContext false
        downloadMutex.withLock {
            registry.recordsForLanguage(code).forEach { fileFor(context, it).delete() }
            pruneOrphans(context, registry)
            true
        }
    }

    suspend fun deleteAll(context: Context): Boolean = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            val dir = filesDir(context)
            !dir.exists() || dir.deleteRecursively()
        }
    }

    private fun pruneOrphans(context: Context, registry: TranslationRegistry) {
        val known = registry.allRecords.map { "${it.id}.zst" }.toSet()
        filesDir(context).listFiles()?.forEach { file ->
            if (file.name.endsWith(".zst") && file.name !in known) file.delete()
        }
    }
}
