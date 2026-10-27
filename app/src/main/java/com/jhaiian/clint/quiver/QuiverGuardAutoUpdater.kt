package com.jhaiian.clint.quiver

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jhaiian.clint.R
import com.jhaiian.clint.quiver.engine.CompileEvent
import com.jhaiian.clint.quiver.engine.CompileResult
import com.jhaiian.clint.quiver.engine.CompiledManifest
import com.jhaiian.clint.quiver.engine.CompiledManifestData
import com.jhaiian.clint.quiver.engine.CompiledManifestEntry
import com.jhaiian.clint.quiver.engine.FilterListCompileInput
import com.jhaiian.clint.quiver.engine.QuiverGuardCompiler
import com.jhaiian.clint.quiver.engine.QuiverGuardPaths
import com.jhaiian.clint.quiver.engine.QuiverGuardWebIntegration
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

internal object QuiverGuardAutoUpdater {

    const val PREF_AUTO_UPDATE = "quiver_guard_auto_update"
    const val DEFAULT_AUTO_UPDATE = true

    private const val UNIQUE_WORK_NAME = "quiver_guard_auto_update"
    private const val PREF_LAST_CHECK_PREFIX = "quiver_guard_last_check_"
    private const val WORK_INTERVAL_HOURS = 6L
    private const val HEADER_SCAN_LINES = 64

    private val MIN_INTERVAL_MS = TimeUnit.HOURS.toMillis(WORK_INTERVAL_HOURS)
    private val MAX_INTERVAL_MS = TimeUnit.DAYS.toMillis(14)
    private val DEFAULT_INTERVAL_MS = TimeUnit.DAYS.toMillis(4)
    private val EXPIRES_REGEX = Regex("""(\d+)\s*(hour|day)""", RegexOption.IGNORE_CASE)

    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_AUTO_UPDATE, DEFAULT_AUTO_UPDATE)

    fun setEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(PREF_AUTO_UPDATE, enabled).apply()
        schedule(context)
    }

    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        if (!isEnabled(context)) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<QuiverGuardUpdateWorker>(WORK_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    suspend fun runUpdate(context: Context): Boolean {
        if (!isEnabled(context) || !QuiverGuardWebIntegration.isEnabled(context)) return false
        val appContext = context.applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        val database = FilterListDatabase(appContext)
        try {
            val now = System.currentTimeMillis()
            val due = database.getAllFilterLists().filter { list ->
                list.isEnabled && list.isDownloaded && !list.isLocal && !list.isNeverCompiled &&
                    now - maxOf(list.downloadedAt, prefs.getLong(PREF_LAST_CHECK_PREFIX + list.id, 0L)) >= intervalFor(appContext, list)
            }
            if (due.isEmpty()) return false

            var anyUpdated = false
            FilterListUpdateChecker.checkAndUpdateAll(appContext, due).collect { event ->
                if (event !is FilterListUpdateEvent.ItemComplete) return@collect
                when (val result = event.result) {
                    is FilterListUpdateItemResult.Updated -> {
                        database.updateDownloadResult(
                            result.filterList.id,
                            FilterListDownloader.localFileFor(appContext, result.filterList.id).absolutePath,
                            result.newFileSizeBytes, System.currentTimeMillis(), result.newRuleCount,
                            result.newEtag, result.newLastModified
                        )
                        prefs.edit().putLong(PREF_LAST_CHECK_PREFIX + result.filterList.id, System.currentTimeMillis()).apply()
                        anyUpdated = true
                    }
                    is FilterListUpdateItemResult.UpToDate ->
                        prefs.edit().putLong(PREF_LAST_CHECK_PREFIX + result.filterList.id, System.currentTimeMillis()).apply()
                    else -> Unit
                }
            }
            if (!anyUpdated) return false
            return recompile(appContext, database)
        } finally {
            database.close()
        }
    }

    private suspend fun recompile(context: Context, database: FilterListDatabase): Boolean {
        val allLists = database.getAllFilterLists()
        val enabledLists = allLists.filter { it.isEnabled && it.isDownloaded }
        val manualDatabase = ManualFilterDatabase(context)
        val manualRules = try { manualDatabase.getAllRules() } finally { manualDatabase.close() }
        val manualContributes = ManualFilterState.isEnabled(context) && manualRules.isNotEmpty()
        if (enabledLists.isEmpty() && !manualContributes) return false

        val manualName = context.getString(R.string.quiver_guard_manual_filter_title)
        val inputs = enabledLists.map { list ->
            FilterListCompileInput(
                id = list.id,
                name = list.name,
                rulesFile = FilterListDownloader.localFileFor(context, list.id),
                trusted = FilterListDatabase.isTrusted(list)
            )
        } + if (manualContributes) {
            ManualFilterDatabase.writeRulesFile(context, manualRules)
            listOf(FilterListCompileInput(ManualFilterState.COMPILE_ID, manualName, ManualFilterDatabase.rulesFile(context)))
        } else {
            emptyList()
        }

        var success: CompileResult.Success? = null
        QuiverGuardCompiler.compile(
            inputs,
            QuiverGuardPaths.databaseFile(context),
            QuiverGuardPaths.tempDatabaseFile(context)
        ).collect { event ->
            if (event is CompileEvent.Completed) success = event.result as? CompileResult.Success
        }
        val result = success ?: return false

        val compiledAtMillis = System.currentTimeMillis()
        database.commitCompiledState(allLists.associate { it.id to it.isEnabled }, compiledAtMillis)

        val entries = allLists.map { list ->
            CompiledManifestEntry(
                id = list.id,
                name = list.name,
                downloadUrl = list.downloadUrl,
                isCustom = list.isCustom,
                isEnabled = list.isEnabled,
                contentFingerprint = "${list.id}:${list.downloadedAt}:${list.ruleCount}"
            )
        } + if (manualContributes) {
            listOf(
                CompiledManifestEntry(
                    id = ManualFilterState.COMPILE_ID,
                    name = manualName,
                    downloadUrl = "",
                    isCustom = true,
                    isEnabled = true,
                    contentFingerprint = ManualFilterState.contentFingerprint(manualRules)
                )
            )
        } else {
            emptyList()
        }

        CompiledManifest.write(
            QuiverGuardPaths.manifestFile(context),
            CompiledManifestData(
                entries = entries,
                totalRuleLines = result.statistics.ruleLines,
                outputFileSizeBytes = result.outputFileSizeBytes,
                durationMs = result.durationMs,
                compiledAtMillis = compiledAtMillis
            )
        )
        return QuiverGuardWebIntegration.onCompileComplete(context)
    }

    private fun intervalFor(context: Context, list: FilterList): Long {
        val file = FilterListDownloader.localFileFor(context, list.id)
        val expires = readExpires(file) ?: return DEFAULT_INTERVAL_MS
        val match = EXPIRES_REGEX.find(expires) ?: return DEFAULT_INTERVAL_MS
        val amount = match.groupValues[1].toLongOrNull() ?: return DEFAULT_INTERVAL_MS
        val millis = if (match.groupValues[2].equals("hour", ignoreCase = true)) {
            TimeUnit.HOURS.toMillis(amount)
        } else {
            TimeUnit.DAYS.toMillis(amount)
        }
        return millis.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }

    private fun readExpires(file: File): String? = try {
        file.bufferedReader().useLines { lines ->
            lines.take(HEADER_SCAN_LINES)
                .map { it.trim() }
                .filter { it.startsWith("!") }
                .map { it.removePrefix("!").trim() }
                .firstOrNull { it.startsWith("Expires:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
        }
    } catch (_: Exception) {
        null
    }
}

class QuiverGuardUpdateWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        try {
            QuiverGuardAutoUpdater.runUpdate(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        return Result.success()
    }
}
