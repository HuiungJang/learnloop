package com.aicodelearning.runner

import com.aicodelearning.platform.BadRequestException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations

class RunnerLanguageServiceTest {
    @Test
    fun `lists default desired and optional runner languages`() {
        val service = service()

        val response = service.list()

        assertEquals(listOf("typescript", "kotlin", "java", "swift", "rust"), response.languages.map { it.language })
        assertEquals(listOf("typescript", "kotlin", "java"), response.languages.filter { it.desiredEnabled }.map { it.language })
        assertEquals(RunnerLanguageStatuses.MISSING, response.languages.single { it.language == "typescript" }.status)
        assertEquals(RunnerLanguageStatuses.AVAILABLE, response.languages.single { it.language == "rust" }.status)
        assertEquals(RunnerImageSources.LOCAL, response.languages.single { it.language == "rust" }.imageSource)
    }

    @Test
    fun `install uses existing local image without pulling from Docker Hub`() {
        val imageClient = FakeRunnerImageClient(installedImages = setOf("learnloop-runner-rust:latest"))
        val service = service(imageClient = imageClient)

        val installing = service.install("rust")
        val installed = service.list().languages.single { it.language == "rust" }

        assertEquals(RunnerLanguageStatuses.INSTALLING, installing.status)
        assertEquals(emptyList<String>(), imageClient.pulled)
        assertEquals(emptyList<String>(), imageClient.built)
        assertEquals(RunnerLanguageStatuses.INSTALLED, installed.status)
        assertTrue(installed.desiredEnabled)
        assertTrue(installed.installed)
        assertNull(installed.lastError)
        assertNotNull(installed.installedAt)
    }

    @Test
    fun `install builds missing local image and verifies it`() {
        val imageClient = FakeRunnerImageClient(installAfterBuild = true)
        val service = service(imageClient = imageClient)

        service.install("rust")
        val installed = service.list().languages.single { it.language == "rust" }

        assertEquals(listOf("learnloop-runner-rust:latest" to "/app/runner/rust"), imageClient.built)
        assertEquals(emptyList<String>(), imageClient.pulled)
        assertEquals(RunnerLanguageStatuses.INSTALLED, installed.status)
    }

    @Test
    fun `registry install pulls missing image and verifies it`() {
        val imageClient = FakeRunnerImageClient(installAfterPull = true)
        val service =
            service(
                catalog = RunnerImageCatalog(mapOf("APP_RUNNER_IMAGE_REGISTRY" to "ghcr.io/huiungjang/learnloop")),
                imageClient = imageClient,
            )

        service.install("rust")
        val installed = service.list().languages.single { it.language == "rust" }

        assertEquals(listOf("ghcr.io/huiungjang/learnloop/learnloop-runner-rust:latest"), imageClient.pulled)
        assertEquals(emptyList<Pair<String, String>>(), imageClient.built)
        assertEquals(RunnerLanguageStatuses.INSTALLED, installed.status)
    }

    @Test
    fun `bundled install does not pull or build missing image`() {
        val imageClient = FakeRunnerImageClient()
        val service =
            service(
                catalog = RunnerImageCatalog(mapOf("APP_RUNNER_IMAGE_SOURCE" to RunnerImageSources.BUNDLED)),
                imageClient = imageClient,
            )

        service.install("rust")
        val failed = service.list().languages.single { it.language == "rust" }

        assertEquals(emptyList<String>(), imageClient.pulled)
        assertEquals(emptyList<Pair<String, String>>(), imageClient.built)
        assertEquals(RunnerLanguageStatuses.FAILED, failed.status)
        assertEquals(RunnerImageErrorCodes.BUNDLED_IMAGE_MISSING, failed.lastErrorCode)
    }

    @Test
    fun `registry auth failure stores product message without raw docker denial`() {
        val imageClient =
            FakeRunnerImageClient(
                pullResult =
                    RunnerImageOperationResult(
                        false,
                        "Error response from daemon: pull access denied for learnloop-runner-rust",
                        RunnerImageErrorCodes.REGISTRY_AUTH_FAILED,
                    ),
            )
        val service =
            service(
                catalog = RunnerImageCatalog(mapOf("APP_RUNNER_IMAGE_REGISTRY" to "ghcr.io/huiungjang/learnloop")),
                imageClient = imageClient,
            )

        service.install("rust")
        val failed = service.list().languages.single { it.language == "rust" }

        assertEquals(RunnerLanguageStatuses.FAILED, failed.status)
        assertEquals(RunnerImageErrorCodes.REGISTRY_AUTH_FAILED, failed.lastErrorCode)
        assertFalse(failed.lastError.orEmpty().contains("pull access denied"))
    }

    @Test
    fun `install persists stage transitions for local build`() {
        val store = InMemoryRunnerLanguageInstallationStore()
        val service = service(store = store, imageClient = FakeRunnerImageClient(installAfterBuild = true))

        service.install("rust")

        assertTrue(store.savedStages.contains(RunnerInstallStages.CHECKING_IMAGE))
        assertTrue(store.savedStages.contains(RunnerInstallStages.BUILDING_LOCAL_IMAGE))
        assertTrue(store.savedStages.contains(RunnerInstallStages.VERIFYING_IMAGE))
    }

    @Test
    fun `duplicate install returns current installing state without scheduling another worker`() {
        val executor = HoldingRunnerInstallTaskExecutor()
        val service = service(installTaskExecutor = executor)

        val first = service.install("rust")
        val second = service.install("rust")

        assertEquals(RunnerLanguageStatuses.INSTALLING, first.status)
        assertEquals(RunnerLanguageStatuses.INSTALLING, second.status)
        assertEquals(1, executor.tasks.size)
    }

    @Test
    fun `different languages can install independently`() {
        val executor = HoldingRunnerInstallTaskExecutor()
        val service = service(installTaskExecutor = executor)

        service.install("rust")
        service.install("swift")

        assertEquals(2, executor.tasks.size)
    }

    @Test
    fun `refresh recovers stale installing row with existing image`() {
        val store = InMemoryRunnerLanguageInstallationStore()
        store.save(
            RunnerLanguageInstallationEntity(
                language = "rust",
                desiredEnabled = true,
                imageRef = "learnloop-runner-rust:latest",
                status = RunnerLanguageStatuses.INSTALLING,
                installStage = RunnerInstallStages.BUILDING_LOCAL_IMAGE,
            ),
        )
        val service = service(store = store, imageClient = FakeRunnerImageClient(installedImages = setOf("learnloop-runner-rust:latest")))

        val refreshed = service.refresh().languages.single { it.language == "rust" }

        assertEquals(RunnerLanguageStatuses.INSTALLED, refreshed.status)
        assertNull(refreshed.installStage)
    }

    @Test
    fun `refresh recovers stale installing row with missing image`() {
        val store = InMemoryRunnerLanguageInstallationStore()
        store.save(
            RunnerLanguageInstallationEntity(
                language = "rust",
                desiredEnabled = true,
                imageRef = "learnloop-runner-rust:latest",
                status = RunnerLanguageStatuses.INSTALLING,
                installStage = RunnerInstallStages.BUILDING_LOCAL_IMAGE,
            ),
        )
        val service = service(store = store)

        val refreshed = service.refresh().languages.single { it.language == "rust" }

        assertEquals(RunnerLanguageStatuses.MISSING, refreshed.status)
        assertNull(refreshed.installStage)
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
        catalog: RunnerImageCatalog = RunnerImageCatalog(emptyMap()),
        store: InMemoryRunnerLanguageInstallationStore = InMemoryRunnerLanguageInstallationStore(),
        imageClient: FakeRunnerImageClient = FakeRunnerImageClient(),
        installTaskExecutor: RunnerInstallTaskExecutor = RunnerInstallTaskExecutor { task -> task() },
    ): RunnerLanguageService =
        RunnerLanguageService(
            catalog = catalog,
            store = store,
            imageClient = imageClient,
            properties = RunnerProperties(),
            transactionTemplate = ImmediateTransactionOperations,
            installTaskExecutor = installTaskExecutor,
        )
}

private object ImmediateTransactionOperations : TransactionOperations {
    override fun <T : Any?> execute(action: TransactionCallback<T>): T? = action.doInTransaction(SimpleTransactionStatus())
}

private class InMemoryRunnerLanguageInstallationStore : RunnerLanguageInstallationStore {
    private val rows = linkedMapOf<String, RunnerLanguageInstallationEntity>()
    val savedStages = mutableListOf<String?>()

    override fun findAll(): List<RunnerLanguageInstallationEntity> = rows.values.sortedBy { it.language }

    override fun findByLanguage(language: String): RunnerLanguageInstallationEntity? = rows[language]

    override fun save(entity: RunnerLanguageInstallationEntity): RunnerLanguageInstallationEntity {
        rows[entity.language] = entity
        savedStages += entity.installStage
        return entity
    }
}

private class HoldingRunnerInstallTaskExecutor : RunnerInstallTaskExecutor {
    val tasks = mutableListOf<() -> Unit>()

    override fun execute(task: () -> Unit) {
        tasks += task
    }
}

private class FakeRunnerImageClient(
    private val installedImages: Set<String> = emptySet(),
    private val pullResult: RunnerImageOperationResult = RunnerImageOperationResult(true, "ok"),
    private val buildResult: RunnerImageOperationResult = RunnerImageOperationResult(true, "ok"),
    private val removeResult: RunnerImageOperationResult = RunnerImageOperationResult(true, "ok"),
    private val installAfterPull: Boolean = false,
    private val installAfterBuild: Boolean = false,
) : RunnerImageClient {
    val pulled = mutableListOf<String>()
    val built = mutableListOf<Pair<String, String>>()
    val removed = mutableListOf<String>()
    private val dynamicInstalledImages = installedImages.toMutableSet()

    override fun inspect(imageRef: String): RunnerImageOperationResult =
        if (imageRef in dynamicInstalledImages) {
            RunnerImageOperationResult(true, "image exists")
        } else {
            RunnerImageOperationResult(false, "image missing", RunnerImageErrorCodes.IMAGE_NOT_FOUND)
        }

    override fun pull(imageRef: String): RunnerImageOperationResult {
        pulled += imageRef
        if (pullResult.success && installAfterPull) {
            dynamicInstalledImages += imageRef
        }
        return pullResult
    }

    override fun build(
        imageRef: String,
        contextPath: String,
    ): RunnerImageOperationResult {
        built += imageRef to contextPath
        if (buildResult.success && installAfterBuild) {
            dynamicInstalledImages += imageRef
        }
        return buildResult
    }

    override fun remove(imageRef: String): RunnerImageOperationResult {
        removed += imageRef
        return removeResult
    }
}
