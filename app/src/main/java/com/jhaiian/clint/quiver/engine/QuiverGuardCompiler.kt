package com.jhaiian.clint.quiver.engine

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class FilterListCompileInput(val id: Long, val name: String, val rulesFile: File, val trusted: Boolean = false)

data class CompileStatistics(val ruleLines: Long, val commentLines: Long, val emptyLines: Long)

enum class CompileStage { PREPARING, READING, PARSING, FINALIZING }

data class CompileProgress(
    val completedLists: Int,
    val totalLists: Int,
    val stage: CompileStage,
    val currentFilterListName: String?,
    val rulesProcessed: Long,
)

sealed class CompileResult {
    data class Success(
        val statistics: CompileStatistics,
        val outputFileSizeBytes: Long,
        val durationMs: Long,
    ) : CompileResult()

    data class Failure(
        val message: String,
        val failedFilterListName: String?,
        val cause: Throwable? = null,
    ) : CompileResult()
}

sealed class CompileEvent {
    data class Progress(val progress: CompileProgress) : CompileEvent()
    data class Completed(val result: CompileResult) : CompileEvent()
}

object QuiverGuardCompiler {

    private val compileMutex = Mutex()

    fun compile(
        inputs: List<FilterListCompileInput>,
        outputFile: File,
        tempFile: File,
    ): Flow<CompileEvent> = flow {
        compileMutex.withLock { compileLocked(inputs, outputFile, tempFile) }
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<CompileEvent>.compileLocked(
        inputs: List<FilterListCompileInput>,
        outputFile: File,
        tempFile: File,
    ) {
        val startedAt = System.currentTimeMillis()
        emit(CompileEvent.Progress(CompileProgress(0, inputs.size, CompileStage.PREPARING, null, 0L)))

        val builder = QuiverGuardNative.nativeNewFilterSetBuilder()
        if (builder == 0L) {
            emit(CompileEvent.Completed(CompileResult.Failure("Could not start the native filter compiler.", null)))
            return
        }

        var totalRuleLines = 0L
        var totalCommentLines = 0L
        var totalEmptyLines = 0L
        var builderOwned = true

        try {
            for ((index, input) in inputs.withIndex()) {
                emit(CompileEvent.Progress(CompileProgress(index, inputs.size, CompileStage.READING, input.name, totalRuleLines)))
                val rules = try {
                    input.rulesFile.readText()
                } catch (e: IOException) {
                    emit(CompileEvent.Completed(CompileResult.Failure(e.message ?: "Failed to read filter list.", input.name, e)))
                    return
                }

                emit(CompileEvent.Progress(CompileProgress(index, inputs.size, CompileStage.PARSING, input.name, totalRuleLines)))
                val stats = JSONObject(QuiverGuardNative.nativeAddFilterListRules(builder, rules, input.trusted))
                totalRuleLines += stats.optLong("ruleLines", 0L)
                totalCommentLines += stats.optLong("commentLines", 0L)
                totalEmptyLines += stats.optLong("emptyLines", 0L)

                emit(CompileEvent.Progress(CompileProgress(index + 1, inputs.size, CompileStage.PARSING, input.name, totalRuleLines)))
            }

            emit(CompileEvent.Progress(CompileProgress(inputs.size, inputs.size, CompileStage.FINALIZING, null, totalRuleLines)))

            if (tempFile.exists()) tempFile.delete()
            builderOwned = false
            val finalizeResult = JSONObject(QuiverGuardNative.nativeFinalizeEngine(builder, tempFile.absolutePath))
            val durationMs = System.currentTimeMillis() - startedAt

            if (!finalizeResult.optBoolean("success", false)) {
                tempFile.delete()
                emit(
                    CompileEvent.Completed(
                        CompileResult.Failure(finalizeResult.optString("error", "Unknown native compiler error."), null)
                    )
                )
                return
            }

            outputFile.delete()
            if (!tempFile.renameTo(outputFile)) {
                emit(CompileEvent.Completed(CompileResult.Failure("Could not finalize the compiled filter database.", null)))
                return
            }

            emit(
                CompileEvent.Completed(
                    CompileResult.Success(
                        statistics = CompileStatistics(totalRuleLines, totalCommentLines, totalEmptyLines),
                        outputFileSizeBytes = finalizeResult.optLong("sizeBytes", outputFile.length()),
                        durationMs = durationMs,
                    )
                )
            )
        } finally {
            if (builderOwned) QuiverGuardNative.nativeDestroyFilterSetBuilder(builder)
        }
    }
}
