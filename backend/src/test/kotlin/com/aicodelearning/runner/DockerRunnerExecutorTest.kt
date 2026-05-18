package com.aicodelearning.runner

import com.aicodelearning.learning.PracticeContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
        assumeTrue(runnerImageAvailable("learnloop-runner-typescript:latest"), "learnloop-runner-typescript:latest is not built")

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

    @Test
    fun `executes tiny Java exercise when runner image is available`() {
        assumeTrue(runnerImageAvailable("learnloop-runner-java:latest"), "learnloop-runner-java:latest is not built")

        val request =
            validator.validate(
                RunnerRunRequest(
                    language = PracticeContract.LANGUAGE_JAVA,
                    testHarnessId = "java-junit",
                    timeoutMs = 5_000,
                    files =
                        listOf(
                            RunnerRunFile(
                                path = "src/main/java/learnloop/Solution.java",
                                content =
                                    """
                                    package learnloop;

                                    public final class Solution {
                                        private Solution() {
                                        }

                                        public static int add(int left, int right) {
                                            return left + right;
                                        }
                                    }
                                    """.trimIndent(),
                            ),
                            RunnerRunFile(
                                path = "src/test/java/learnloop/SolutionTest.java",
                                content =
                                    """
                                    package learnloop;

                                    import org.junit.jupiter.api.Test;

                                    import static org.junit.jupiter.api.Assertions.assertEquals;

                                    class SolutionTest {
                                        @Test
                                        void addsNumbers() {
                                            assertEquals(3, Solution.add(1, 2));
                                        }
                                    }
                                    """.trimIndent(),
                            ),
                        ),
                ),
            )

        val result = executor.run(request)

        assertEquals(PracticeContract.RUN_STATUS_PASSED, result.status)
    }

    @Test
    fun `normalizes Java compile errors when runner image is available`() {
        assumeTrue(runnerImageAvailable("learnloop-runner-java:latest"), "learnloop-runner-java:latest is not built")

        val request =
            validator.validate(
                RunnerRunRequest(
                    language = PracticeContract.LANGUAGE_JAVA,
                    testHarnessId = "java-junit",
                    timeoutMs = 5_000,
                    files =
                        listOf(
                            RunnerRunFile(
                                path = "src/main/java/learnloop/Solution.java",
                                content = "package learnloop; public class Solution {",
                            ),
                            RunnerRunFile(
                                path = "src/test/java/learnloop/SolutionTest.java",
                                content = "package learnloop; class SolutionTest {}",
                            ),
                        ),
                ),
            )

        val result = executor.run(request)

        assertEquals(PracticeContract.RUN_STATUS_COMPILE_ERROR, result.status)
        assertTrue(result.stderrExcerpt.toByteArray(Charsets.UTF_8).size <= PracticeContract.MAX_STDIO_EXCERPT_BYTES)
    }

    private fun runnerImageAvailable(image: String): Boolean {
        val process =
            try {
                ProcessBuilder(properties.dockerCommand, "image", "inspect", image)
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
