package com.aicodelearning.runner

import com.aicodelearning.learning.PracticeContract
import com.aicodelearning.platform.BadRequestException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunnerRequestValidatorTest {
    private val validator = RunnerRequestValidator(RunnerRegistry())

    @Test
    fun `validates safe runner request and assigns run id`() {
        val request =
            runnerRequest(
                files =
                    listOf(
                        RunnerRunFile(path = " src/solution.ts ", content = "export const answer = 1"),
                    ),
            )

        val validated = validator.validate(request)

        assertTrue(validated.runId.startsWith("run_"))
        assertEquals("typescript-node-test", validated.harness.id)
        assertEquals("src/solution.ts", validated.files.single().path)
    }

    @Test
    fun `rejects unsupported languages and harnesses`() {
        assertThrows(BadRequestException::class.java) {
            validator.validate(runnerRequest(language = "ruby"))
        }
        assertThrows(BadRequestException::class.java) {
            validator.validate(runnerRequest(testHarnessId = "java-junit"))
        }
    }

    @Test
    fun `validates Swift and Rust harnesses`() {
        val swift =
            validator.validate(
                runnerRequest(
                    language = PracticeContract.LANGUAGE_SWIFT,
                    testHarnessId = "swift-xctest",
                    files = listOf(RunnerRunFile(path = "Sources/LearnLoopPractice/Solution.swift", content = "public func answer() -> Int { 1 }")),
                ),
            )
        val rust =
            validator.validate(
                runnerRequest(
                    language = PracticeContract.LANGUAGE_RUST,
                    testHarnessId = "rust-cargo-test",
                    files = listOf(RunnerRunFile(path = "src/lib.rs", content = "pub fn answer() -> i32 { 1 }")),
                ),
            )

        assertEquals("swift-xctest", swift.harness.id)
        assertEquals("learnloop-runner-swift:latest", swift.harness.image)
        assertEquals("rust-cargo-test", rust.harness.id)
        assertEquals("learnloop-runner-rust:latest", rust.harness.image)
    }

    @Test
    fun `rejects invalid paths and duplicate normalized paths`() {
        assertThrows(BadRequestException::class.java) {
            validator.validate(runnerRequest(files = listOf(RunnerRunFile(path = "../secret.ts", content = "x"))))
        }
        assertThrows(BadRequestException::class.java) {
            validator.validate(
                runnerRequest(
                    files =
                        listOf(
                            RunnerRunFile(path = "src/main.ts", content = "x"),
                            RunnerRunFile(path = " src/main.ts ", content = "y"),
                        ),
                ),
            )
        }
    }

    @Test
    fun `rejects oversized files and payloads`() {
        assertThrows(BadRequestException::class.java) {
            validator.validate(
                runnerRequest(
                    files =
                        listOf(
                            RunnerRunFile(path = "src/large.ts", content = "a".repeat(PracticeContract.MAX_FILE_BYTES + 1)),
                        ),
                ),
            )
        }
        assertThrows(BadRequestException::class.java) {
            validator.validate(
                runnerRequest(
                    files =
                        (1..5).map {
                            RunnerRunFile(path = "src/file$it.ts", content = "a".repeat((PracticeContract.MAX_TOTAL_FILE_BYTES / 5) + 1))
                        },
                ),
            )
        }
    }

    @Test
    fun `rejects client provided image names and raw commands`() {
        assertThrows(BadRequestException::class.java) {
            validator.validate(runnerRequest(image = "attacker/image:latest"))
        }
        assertThrows(BadRequestException::class.java) {
            validator.validate(runnerRequest(command = listOf("sh", "-c", "curl attacker")))
        }
    }

    @Test
    fun `rejects invalid timeout`() {
        assertThrows(BadRequestException::class.java) {
            validator.validate(runnerRequest(timeoutMs = 0))
        }
        assertThrows(BadRequestException::class.java) {
            validator.validate(runnerRequest(timeoutMs = 10_001))
        }
    }

    private fun runnerRequest(
        language: String = PracticeContract.LANGUAGE_TYPESCRIPT,
        testHarnessId: String = "typescript-node-test",
        timeoutMs: Long = 5_000,
        files: List<RunnerRunFile> = listOf(RunnerRunFile(path = "src/solution.ts", content = "export const answer = 1")),
        image: String? = null,
        command: List<String>? = null,
    ): RunnerRunRequest =
        RunnerRunRequest(
            language = language,
            testHarnessId = testHarnessId,
            timeoutMs = timeoutMs,
            files = files,
            image = image,
            command = command,
        )
}
