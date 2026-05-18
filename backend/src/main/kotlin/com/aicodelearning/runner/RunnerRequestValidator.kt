package com.aicodelearning.runner

import com.aicodelearning.learning.PracticeContract
import com.aicodelearning.platform.BadRequestException
import com.aicodelearning.platform.prefixedId
import org.springframework.stereotype.Service

data class RunnerRunRequest(
    val language: String,
    val testHarnessId: String,
    val timeoutMs: Long,
    val files: List<RunnerRunFile>,
    val image: String? = null,
    val command: List<String>? = null,
)

data class RunnerRunFile(
    val path: String,
    val content: String,
)

data class ValidatedRunnerRunRequest(
    val runId: String,
    val language: String,
    val harness: RunnerHarness,
    val timeoutMs: Long,
    val files: List<RunnerRunFile>,
)

@Service
class RunnerRequestValidator(
    private val runnerRegistry: RunnerRegistry,
) {
    fun validate(request: RunnerRunRequest): ValidatedRunnerRunRequest {
        rejectClientExecutionControls(request)
        PracticeContract.requireSupportedLanguage(request.language)
        val harness =
            runnerRegistry.find(request.language, request.testHarnessId)
                ?: throw BadRequestException("test harness is not supported for language")

        if (request.timeoutMs <= 0 || request.timeoutMs > harness.maxTimeoutMs) {
            throw BadRequestException("timeout must be between 1 and ${harness.maxTimeoutMs} milliseconds")
        }

        val files = validateFiles(request.files)
        return ValidatedRunnerRunRequest(
            runId = prefixedId("run"),
            language = request.language,
            harness = harness,
            timeoutMs = request.timeoutMs,
            files = files,
        )
    }

    private fun rejectClientExecutionControls(request: RunnerRunRequest) {
        if (!request.image.isNullOrBlank()) {
            throw BadRequestException("runner image is selected by server registry")
        }
        if (!request.command.isNullOrEmpty()) {
            throw BadRequestException("runner command is selected by server registry")
        }
    }

    private fun validateFiles(files: List<RunnerRunFile>): List<RunnerRunFile> {
        if (files.isEmpty()) {
            throw BadRequestException("runner files are required")
        }
        if (files.size > PracticeContract.MAX_FILE_COUNT) {
            throw BadRequestException("runner may include at most ${PracticeContract.MAX_FILE_COUNT} files")
        }

        var totalBytes = 0
        val normalizedPaths = mutableSetOf<String>()
        return files.map { file ->
            val normalizedPath = PracticeContract.normalizeFilePath(file.path)
            if (!normalizedPaths.add(normalizedPath)) {
                throw BadRequestException("runner file paths must be unique")
            }

            val fileBytes = file.content.toByteArray(Charsets.UTF_8).size
            if (fileBytes > PracticeContract.MAX_FILE_BYTES) {
                throw BadRequestException("runner file exceeds maximum size")
            }
            totalBytes += fileBytes
            if (totalBytes > PracticeContract.MAX_TOTAL_FILE_BYTES) {
                throw BadRequestException("runner payload exceeds maximum size")
            }

            RunnerRunFile(path = normalizedPath, content = file.content)
        }
    }
}
