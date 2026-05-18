package com.aicodelearning.runner

import com.aicodelearning.learning.PracticeContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DockerRunResultNormalizerTest {
    private val normalizer = DockerRunResultNormalizer(RunnerWorkspaceService())

    @Test
    fun `normalizes timeout and resource limited results`() {
        assertEquals(
            PracticeContract.RUN_STATUS_TIMEOUT,
            normalizer.normalize(rawResult(timedOut = true)).status,
        )
        assertEquals(
            PracticeContract.RUN_STATUS_RESOURCE_LIMITED,
            normalizer.normalize(rawResult(exitCode = 137)).status,
        )
        assertEquals(
            PracticeContract.RUN_STATUS_RESOURCE_LIMITED,
            normalizer.normalize(rawResult(resourceLimited = true)).status,
        )
    }

    @Test
    fun `normalizes passed and failed results`() {
        assertEquals(PracticeContract.RUN_STATUS_PASSED, normalizer.normalize(rawResult(exitCode = 0)).status)
        assertEquals(PracticeContract.RUN_STATUS_FAILED, normalizer.normalize(rawResult(exitCode = 1)).status)
        assertEquals(PracticeContract.RUN_STATUS_COMPILE_ERROR, normalizer.normalize(rawResult(exitCode = 2)).status)
        assertEquals(PracticeContract.RUN_STATUS_RUNNER_UNAVAILABLE, normalizer.normalize(rawResult(exitCode = 125)).status)
    }

    @Test
    fun `truncates output excerpts and clamps duration`() {
        val result =
            normalizer.normalize(
                rawResult(
                    stdout = "a".repeat(PracticeContract.MAX_STDIO_EXCERPT_BYTES + 1),
                    stderr = "b".repeat(PracticeContract.MAX_STDIO_EXCERPT_BYTES + 1),
                    durationMs = -5,
                ),
            )

        assertTrue(result.stdoutExcerpt.toByteArray(Charsets.UTF_8).size <= PracticeContract.MAX_STDIO_EXCERPT_BYTES)
        assertTrue(result.stderrExcerpt.toByteArray(Charsets.UTF_8).size <= PracticeContract.MAX_STDIO_EXCERPT_BYTES)
        assertEquals(0, result.durationMs)
    }

    private fun rawResult(
        exitCode: Int = 1,
        timedOut: Boolean = false,
        resourceLimited: Boolean = false,
        stdout: String = "",
        stderr: String = "",
        durationMs: Long = 10,
    ): RawDockerRunResult =
        RawDockerRunResult(
            exitCode = exitCode,
            timedOut = timedOut,
            resourceLimited = resourceLimited,
            stdout = stdout,
            stderr = stderr,
            durationMs = durationMs,
        )
}
