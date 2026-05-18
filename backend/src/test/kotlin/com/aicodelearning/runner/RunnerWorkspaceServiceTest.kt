package com.aicodelearning.runner

import com.aicodelearning.learning.PracticeContract
import com.aicodelearning.platform.BadRequestException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeoutException

class RunnerWorkspaceServiceTest {
    private val validator = RunnerRequestValidator(RunnerRegistry())
    private val workspaceService = RunnerWorkspaceService()

    @Test
    fun `creates workspace with validated files and selected harness files then cleans up on success`() {
        var workspaceRoot: Path? = null
        val result =
            workspaceService.withWorkspace(validatedRequest()) { workspace ->
                workspaceRoot = workspace.root
                assertTrue(Files.exists(workspace.root.resolve("src/solution.ts")))
                assertTrue(Files.exists(workspace.root.resolve(".learnloop/harness.json")))
                "ok"
            }

        assertEquals("ok", result)
        assertFalse(Files.exists(workspaceRoot!!))
    }

    @Test
    fun `cleans workspace after failure and timeout`() {
        var failureRoot: Path? = null
        assertThrows(IllegalStateException::class.java) {
            workspaceService.withWorkspace(validatedRequest()) { workspace ->
                failureRoot = workspace.root
                throw IllegalStateException("run failed")
            }
        }
        assertFalse(Files.exists(failureRoot!!))

        var timeoutRoot: Path? = null
        assertThrows(TimeoutException::class.java) {
            workspaceService.withWorkspace(validatedRequest()) { workspace ->
                timeoutRoot = workspace.root
                throw TimeoutException("run timed out")
            }
        }
        assertFalse(Files.exists(timeoutRoot!!))
    }

    @Test
    fun `rejects client files that target reserved harness paths`() {
        assertThrows(BadRequestException::class.java) {
            validatedRequest(
                files =
                    listOf(
                        RunnerRunFile(path = ".learnloop/harness.json", content = "{}"),
                    ),
            )
        }
    }

    @Test
    fun `truncates oversized output without splitting utf8 code points`() {
        val output = "한🙂".repeat(PracticeContract.MAX_STDIO_EXCERPT_BYTES)
        val truncated = workspaceService.truncateOutput(output)

        assertTrue(truncated.toByteArray(Charsets.UTF_8).size <= PracticeContract.MAX_STDIO_EXCERPT_BYTES)
        assertTrue(truncated.isNotEmpty())
        assertFalse(truncated.endsWith('\uD83D'))
    }

    private fun validatedRequest(
        files: List<RunnerRunFile> = listOf(RunnerRunFile(path = "src/solution.ts", content = "export const answer = 1")),
    ): ValidatedRunnerRunRequest =
        validator.validate(
            RunnerRunRequest(
                language = PracticeContract.LANGUAGE_TYPESCRIPT,
                testHarnessId = "typescript-vitest",
                timeoutMs = 5_000,
                files = files,
            ),
        )
}
