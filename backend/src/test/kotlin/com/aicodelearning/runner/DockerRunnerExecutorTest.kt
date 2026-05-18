package com.aicodelearning.runner

import com.aicodelearning.learning.PracticeContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class DockerRunnerExecutorTest {
    private val workspaceService = RunnerWorkspaceService()
    private val properties = RunnerProperties()
    private val executor =
        DockerRunnerExecutor(
            properties = properties,
            workspaceService = workspaceService,
            commandBuilder = DockerRunCommandBuilder(properties),
            resultNormalizer = DockerRunResultNormalizer(workspaceService),
        )
    private val validator = RunnerRequestValidator(RunnerRegistry())

    @Test
    fun `executes tiny TypeScript exercise when runner image is available`() {
        assumeTrue(runnerImageAvailable(), "learnloop-runner-typescript:latest is not built")

        val request =
            validator.validate(
                RunnerRunRequest(
                    language = PracticeContract.LANGUAGE_TYPESCRIPT,
                    testHarnessId = "typescript-node-test",
                    timeoutMs = 5_000,
                    files =
                        listOf(
                            RunnerRunFile(
                                path = "src/main.ts",
                                content = "export const value = 1",
                            ),
                            RunnerRunFile(
                                path = "src/main.test.ts",
                                content =
                                    """
                                    import { strict as assert } from "node:assert"
                                    import { test } from "node:test"
                                    import { value } from "./main"

                                    test("reads value", () => {
                                      assert.equal(value, 1)
                                    })
                                    """.trimIndent(),
                            ),
                        ),
                ),
            )

        val result = executor.run(request)

        assertEquals(PracticeContract.RUN_STATUS_PASSED, result.status)
    }

    private fun runnerImageAvailable(): Boolean {
        val process =
            try {
                ProcessBuilder(properties.dockerCommand, "image", "inspect", "learnloop-runner-typescript:latest")
                    .redirectErrorStream(true)
                    .start()
            } catch (_: Exception) {
                return false
            }

        val completed = process.waitFor(2, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return false
        }
        return process.exitValue() == 0
    }
}
