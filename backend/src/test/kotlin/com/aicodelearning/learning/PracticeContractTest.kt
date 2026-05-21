package com.aicodelearning.learning

import com.aicodelearning.platform.BadRequestException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class PracticeContractTest {
    @Test
    fun `accepts supported practice contract values`() {
        assertDoesNotThrow {
            PracticeContract.requireSupportedLanguage("typescript")
            PracticeContract.requireSupportedLanguage("kotlin")
            PracticeContract.requireSupportedLanguage("java")
            PracticeContract.requireSupportedLanguage("swift")
            PracticeContract.requireSupportedLanguage("rust")
            PracticeContract.requireFileRole("starter")
            PracticeContract.requireFileRole("hidden_test")
            PracticeContract.requireHintRevealPolicy("manual")
            PracticeContract.requireAttemptStatus("draft")
            PracticeContract.requireRunStatus("passed")
            PracticeContract.requireResultStatus("submitted")
        }
    }

    @Test
    fun `rejects unsupported practice contract values`() {
        assertThrows(BadRequestException::class.java) { PracticeContract.requireSupportedLanguage("ruby") }
        assertThrows(BadRequestException::class.java) { PracticeContract.requireFileRole("answer") }
        assertThrows(BadRequestException::class.java) { PracticeContract.requireHintRevealPolicy("always") }
        assertThrows(BadRequestException::class.java) { PracticeContract.requireAttemptStatus("done") }
        assertThrows(BadRequestException::class.java) { PracticeContract.requireRunStatus("ok") }
        assertThrows(BadRequestException::class.java) { PracticeContract.requireResultStatus("reviewed") }
    }

    @Test
    fun `validates attempt sync payload limits`() {
        assertDoesNotThrow {
            PracticeContract.validateAttemptSyncRequest(
                attemptSyncRequest(
                    files = listOf(PracticeAttemptFileRequest(path = "src/main.ts", content = "export const x = 1")),
                ),
            )
        }

        assertThrows(BadRequestException::class.java) {
            PracticeContract.validateAttemptSyncRequest(attemptSyncRequest(files = emptyList()))
        }

        assertThrows(BadRequestException::class.java) {
            PracticeContract.validateAttemptSyncRequest(
                attemptSyncRequest(
                    files =
                        (1..PracticeContract.MAX_FILE_COUNT + 1).map {
                            PracticeAttemptFileRequest(path = "src/file$it.ts", content = "export const x$it = $it")
                        },
                ),
            )
        }

        assertThrows(BadRequestException::class.java) {
            PracticeContract.validateAttemptSyncRequest(
                attemptSyncRequest(
                    files = listOf(PracticeAttemptFileRequest(path = "src/large.ts", content = "a".repeat(PracticeContract.MAX_FILE_BYTES + 1))),
                ),
            )
        }
    }

    @Test
    fun `normalizes safe exercise file paths`() {
        assertEquals("src/main.ts", PracticeContract.normalizeFilePath(" src/main.ts "))
        assertEquals("src/main/App.kt", PracticeContract.normalizeFilePath("src/main/App.kt"))
        assertEquals("Main.java", PracticeContract.normalizeFilePath("Main.java"))
    }

    @Test
    fun `rejects paths that escape the exercise root`() {
        listOf(
            "",
            "/src/main.ts",
            "~/src/main.ts",
            "C:/src/main.ts",
            "src/../secret.ts",
            "src/./main.ts",
            "src//main.ts",
            "src\\main.ts",
            "src/main.ts\u0000",
        ).forEach { path ->
            assertThrows(BadRequestException::class.java) { PracticeContract.normalizeFilePath(path) }
        }
    }

    @Test
    fun `rejects duplicate attempt file paths after normalization`() {
        assertThrows(BadRequestException::class.java) {
            PracticeContract.validateAttemptSyncRequest(
                attemptSyncRequest(
                    files =
                        listOf(
                            PracticeAttemptFileRequest(path = "src/main.ts", content = "one"),
                            PracticeAttemptFileRequest(path = " src/main.ts ", content = "two"),
                        ),
                ),
            )
        }
    }

    private fun attemptSyncRequest(files: List<PracticeAttemptFileRequest>): PracticeAttemptSyncRequest =
        PracticeAttemptSyncRequest(
            clientAttemptId = "attempt-1",
            assetRevision = "rev-1",
            language = "typescript",
            intent = "draft",
            files = files,
            localUpdatedAt = Instant.parse("2026-05-18T00:00:00Z"),
        )
}
