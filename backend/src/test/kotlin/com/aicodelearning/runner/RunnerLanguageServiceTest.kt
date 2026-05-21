package com.aicodelearning.runner

import com.aicodelearning.platform.BadRequestException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunnerLanguageServiceTest {
    @Test
    fun `lists default desired and optional runner languages`() {
        val service = service()

        val response = service.list()

        assertEquals(listOf("typescript", "kotlin", "java", "swift", "rust"), response.languages.map { it.language })
        assertEquals(listOf("typescript", "kotlin", "java"), response.languages.filter { it.desiredEnabled }.map { it.language })
        assertEquals(RunnerLanguageStatuses.MISSING, response.languages.single { it.language == "typescript" }.status)
        assertEquals(RunnerLanguageStatuses.AVAILABLE, response.languages.single { it.language == "rust" }.status)
    }

    @Test
    fun `install uses catalog image ref and stores installed state`() {
        val imageClient = FakeRunnerImageClient()
        val service = service(imageClient = imageClient)

        val installed = service.install("rust")

        assertEquals(listOf("learnloop-runner-rust:latest"), imageClient.pulled)
        assertEquals(RunnerLanguageStatuses.INSTALLED, installed.status)
        assertTrue(installed.desiredEnabled)
        assertTrue(installed.installed)
        assertNull(installed.lastError)
        assertNotNull(installed.installedAt)
    }

    @Test
    fun `install stores failed state with sanitized error detail`() {
        val imageClient = FakeRunnerImageClient(pullResult = RunnerImageOperationResult(false, "pull failed token=[redacted]", "registry_auth_failed"))
        val service = service(imageClient = imageClient)

        val failed = service.install("swift")

        assertEquals(RunnerLanguageStatuses.FAILED, failed.status)
        assertTrue(failed.desiredEnabled)
        assertFalse(failed.installed)
        assertEquals("pull failed token=[redacted]", failed.lastError)
    }

    @Test
    fun `refresh maps docker inspect result to installed and missing states`() {
        val imageClient = FakeRunnerImageClient(installedImages = setOf("learnloop-runner-typescript:latest"))
        val service = service(imageClient = imageClient)

        val response = service.refresh()

        assertEquals(RunnerLanguageStatuses.INSTALLED, response.languages.single { it.language == "typescript" }.status)
        assertEquals(RunnerLanguageStatuses.MISSING, response.languages.single { it.language == "kotlin" }.status)
        assertEquals(RunnerLanguageStatuses.AVAILABLE, response.languages.single { it.language == "swift" }.status)
    }

    @Test
    fun `remove clears desired state and calls static catalog image ref`() {
        val imageClient = FakeRunnerImageClient(installedImages = setOf("learnloop-runner-rust:latest"))
        val service = service(imageClient = imageClient)

        val removed = service.remove("rust")

        assertEquals(listOf("learnloop-runner-rust:latest"), imageClient.removed)
        assertEquals(RunnerLanguageStatuses.AVAILABLE, removed.status)
        assertFalse(removed.desiredEnabled)
    }

    @Test
    fun `remove treats already missing image as available`() {
        val imageClient = FakeRunnerImageClient()
        val service = service(imageClient = imageClient)

        val removed = service.remove("swift")

        assertEquals(emptyList<String>(), imageClient.removed)
        assertEquals(RunnerLanguageStatuses.AVAILABLE, removed.status)
        assertFalse(removed.desiredEnabled)
        assertNull(removed.lastError)
    }

    @Test
    fun `rejects unsupported language ids`() {
        assertThrows(BadRequestException::class.java) {
            service().install("ruby")
        }
    }

    private fun service(
        store: InMemoryRunnerLanguageInstallationStore = InMemoryRunnerLanguageInstallationStore(),
        imageClient: FakeRunnerImageClient = FakeRunnerImageClient(),
    ): RunnerLanguageService =
        RunnerLanguageService(
            catalog = RunnerImageCatalog(emptyMap()),
            store = store,
            imageClient = imageClient,
        )
}

private class InMemoryRunnerLanguageInstallationStore : RunnerLanguageInstallationStore {
    private val rows = linkedMapOf<String, RunnerLanguageInstallationEntity>()

    override fun findAll(): List<RunnerLanguageInstallationEntity> = rows.values.sortedBy { it.language }

    override fun findByLanguage(language: String): RunnerLanguageInstallationEntity? = rows[language]

    override fun save(entity: RunnerLanguageInstallationEntity): RunnerLanguageInstallationEntity {
        rows[entity.language] = entity
        return entity
    }
}

private class FakeRunnerImageClient(
    private val installedImages: Set<String> = emptySet(),
    private val pullResult: RunnerImageOperationResult = RunnerImageOperationResult(true, "ok"),
    private val removeResult: RunnerImageOperationResult = RunnerImageOperationResult(true, "ok"),
) : RunnerImageClient {
    val pulled = mutableListOf<String>()
    val removed = mutableListOf<String>()

    override fun inspect(imageRef: String): RunnerImageOperationResult =
        if (imageRef in installedImages) {
            RunnerImageOperationResult(true, "image exists")
        } else {
            RunnerImageOperationResult(false, "image missing", "image_not_found")
        }

    override fun pull(imageRef: String): RunnerImageOperationResult {
        pulled += imageRef
        return pullResult
    }

    override fun remove(imageRef: String): RunnerImageOperationResult {
        removed += imageRef
        return removeResult
    }
}
