package com.aicodelearning.runner

import com.aicodelearning.learning.PracticeContract
import org.springframework.stereotype.Component

data class RawDockerRunResult(
    val exitCode: Int,
    val timedOut: Boolean,
    val resourceLimited: Boolean,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
)

data class NormalizedRunnerResult(
    val status: String,
    val stdoutExcerpt: String,
    val stderrExcerpt: String,
    val durationMs: Long,
)

@Component
class DockerRunResultNormalizer(
    private val workspaceService: RunnerWorkspaceService,
) {
    fun normalize(result: RawDockerRunResult): NormalizedRunnerResult {
        val status =
            when {
                result.timedOut -> PracticeContract.RUN_STATUS_TIMEOUT
                result.resourceLimited || result.exitCode == RESOURCE_LIMIT_EXIT_CODE -> PracticeContract.RUN_STATUS_RESOURCE_LIMITED
                result.exitCode == DOCKER_RUNNER_UNAVAILABLE_EXIT_CODE -> PracticeContract.RUN_STATUS_RUNNER_UNAVAILABLE
                result.exitCode == TYPESCRIPT_COMPILE_ERROR_EXIT_CODE -> PracticeContract.RUN_STATUS_COMPILE_ERROR
                result.exitCode == 0 -> PracticeContract.RUN_STATUS_PASSED
                else -> PracticeContract.RUN_STATUS_FAILED
            }
        return NormalizedRunnerResult(
            status = status,
            stdoutExcerpt = workspaceService.truncateOutput(result.stdout),
            stderrExcerpt = workspaceService.truncateOutput(result.stderr),
            durationMs = result.durationMs.coerceAtLeast(0),
        )
    }

    private companion object {
        const val DOCKER_RUNNER_UNAVAILABLE_EXIT_CODE = 125
        const val RESOURCE_LIMIT_EXIT_CODE = 137
        const val TYPESCRIPT_COMPILE_ERROR_EXIT_CODE = 2
    }
}
