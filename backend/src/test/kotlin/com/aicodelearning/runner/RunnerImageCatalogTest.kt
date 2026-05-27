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
        assertEquals(
            listOf(RunnerImageSources.LOCAL, RunnerImageSources.LOCAL, RunnerImageSources.LOCAL, RunnerImageSources.LOCAL, RunnerImageSources.LOCAL),
            languages.map { it.imageSource },
        )
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
        assertEquals(RunnerImageSources.REGISTRY, catalog.requireLanguage("swift").imageSource)
        assertEquals(
            "localhost/learnloop-runner-rust:test",
            catalog.requireLanguage("rust").imageRef,
        )
        assertEquals(RunnerImageSources.REGISTRY, catalog.requireLanguage("rust").imageSource)
    }

    @Test
    fun `explicit image source overrides registry derivation`() {
        val catalog =
            RunnerImageCatalog(
                mapOf(
                    "APP_RUNNER_IMAGE_SOURCE" to RunnerImageSources.BUNDLED,
                    "APP_RUNNER_IMAGE_REGISTRY" to "ghcr.io/huiungjang/learnloop",
                ),
            )

        assertEquals(RunnerImageSources.BUNDLED, catalog.requireLanguage("rust").imageSource)
    }

    @Test
    fun `bare per-language override remains local`() {
        val catalog =
            RunnerImageCatalog(
                mapOf(
                    "APP_RUNNER_IMAGE_REGISTRY" to "ghcr.io/huiungjang/learnloop",
                    "APP_RUNNER_RUST_IMAGE" to "learnloop-runner-rust:dev",
                ),
            )

        assertEquals("learnloop-runner-rust:dev", catalog.requireLanguage("rust").imageRef)
        assertEquals(RunnerImageSources.LOCAL, catalog.requireLanguage("rust").imageSource)
    }

    @Test
    fun `rejects unsupported image source`() {
        assertThrows(com.aicodelearning.platform.BadRequestException::class.java) {
            RunnerImageCatalog(mapOf("APP_RUNNER_IMAGE_SOURCE" to "dockerhub")).languages()
        }
    }

    @Test
    fun `rejects unknown runner language`() {
        assertThrows(com.aicodelearning.platform.BadRequestException::class.java) {
            RunnerImageCatalog(emptyMap()).requireLanguage("ruby")
        }
    }

    @Test
    fun `resolves runner build context path by language`() {
        assertEquals("/app/runner/rust", RunnerBuildContextResolver("/app/runner").pathFor("rust"))
    }
}
