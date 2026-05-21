package com.aicodelearning.runner

import com.aicodelearning.learning.PracticeContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunnerImageCatalogTest {
    @Test
    fun `lists default and optional runner languages`() {
        val languages = RunnerImageCatalog(emptyMap()).languages()

        assertEquals(
            listOf("typescript", "kotlin", "java", "swift", "rust"),
            languages.map { it.language },
        )
        assertEquals(
            listOf("typescript", "kotlin", "java"),
            languages.filter { it.selectedByDefault }.map { it.language },
        )
        assertFalse(languages.single { it.language == PracticeContract.LANGUAGE_SWIFT }.selectedByDefault)
        assertFalse(languages.single { it.language == PracticeContract.LANGUAGE_RUST }.selectedByDefault)
    }

    @Test
    fun `applies registry version and per-language image overrides`() {
        val catalog =
            RunnerImageCatalog(
                mapOf(
                    "APP_RUNNER_IMAGE_REGISTRY" to "ghcr.io/huiungjang",
                    "APP_RUNNER_IMAGE_VERSION" to "1.2.3",
                    "APP_RUNNER_RUST_IMAGE" to "localhost/learnloop-runner-rust:test",
                ),
            )

        assertEquals(
            "ghcr.io/huiungjang/learnloop-runner-swift:1.2.3",
            catalog.requireLanguage("swift").imageRef,
        )
        assertEquals(
            "localhost/learnloop-runner-rust:test",
            catalog.requireLanguage("rust").imageRef,
        )
    }

    @Test
    fun `rejects unknown runner language`() {
        assertThrows(com.aicodelearning.platform.BadRequestException::class.java) {
            RunnerImageCatalog(emptyMap()).requireLanguage("ruby")
        }
    }
}
