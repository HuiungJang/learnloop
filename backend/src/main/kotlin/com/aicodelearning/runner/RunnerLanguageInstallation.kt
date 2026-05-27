package com.aicodelearning.runner

import com.aicodelearning.auth.CurrentUser
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionOperations
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object RunnerLanguageStatuses {
    const val AVAILABLE = "available"
    const val INSTALLING = "installing"
    const val INSTALLED = "installed"
    const val MISSING = "missing"
    const val FAILED = "failed"
}

object RunnerInstallStages {
    const val CHECKING_IMAGE = "checking_image"
    const val BUILDING_LOCAL_IMAGE = "building_local_image"
    const val PULLING_IMAGE = "pulling_image"
    const val VERIFYING_IMAGE = "verifying_image"
}

object RunnerImageErrorCodes {
    const val BUNDLED_IMAGE_MISSING = "bundled_image_missing"
    const val DOCKER_DAEMON_UNREACHABLE = "docker_daemon_unreachable"
    const val DOCKER_MISSING = "docker_missing"
    const val DISK_FULL = "disk_full"
    const val IMAGE_NOT_FOUND = "image_not_found"
    const val LOCAL_BUILD_CONTEXT_MISSING = "local_build_context_missing"
    const val REGISTRY_AUTH_FAILED = "registry_auth_failed"
    const val TIMEOUT = "timeout"
}

@Entity
@Table(name = "runner_language_installations")
class RunnerLanguageInstallationEntity(
    @Id
    var language: String = "",

    @Column(name = "desired_enabled", nullable = false)
    var desiredEnabled: Boolean = false,

    @Column(name = "image_ref", nullable = false)
    var imageRef: String = "",

    @Column(nullable = false)
    var status: String = RunnerLanguageStatuses.AVAILABLE,

    @Column(name = "installed_at")
    var installedAt: Instant? = null,

    @Column(name = "last_checked_at")
    var lastCheckedAt: Instant? = null,

    @Column(name = "last_error")
    var lastError: String? = null,

    @Column(name = "install_stage")
    var installStage: String? = null,

    @Column(name = "last_error_code")
    var lastErrorCode: String? = null,
)

interface RunnerLanguageInstallationRepository : JpaRepository<RunnerLanguageInstallationEntity, String> {
    fun findAllByOrderByLanguageAsc(): List<RunnerLanguageInstallationEntity>
}

interface RunnerLanguageInstallationStore {
    fun findAll(): List<RunnerLanguageInstallationEntity>

    fun findByLanguage(language: String): RunnerLanguageInstallationEntity?

    fun save(entity: RunnerLanguageInstallationEntity): RunnerLanguageInstallationEntity
}

@Repository
class JpaRunnerLanguageInstallationStore(
    private val repository: RunnerLanguageInstallationRepository,
) : RunnerLanguageInstallationStore {
    override fun findAll(): List<RunnerLanguageInstallationEntity> = repository.findAllByOrderByLanguageAsc()

    override fun findByLanguage(language: String): RunnerLanguageInstallationEntity? = repository.findById(language).orElse(null)

    override fun save(entity: RunnerLanguageInstallationEntity): RunnerLanguageInstallationEntity = repository.save(entity)
}

data class RunnerLanguageListResponse(
    val languages: List<RunnerLanguageResponse>,
)

data class RunnerLanguageResponse(
    val language: String,
    val displayName: String,
    val imageRef: String,
    val imageSource: String,
    val status: String,
    val installed: Boolean,
    val runnable: Boolean,
    val desiredEnabled: Boolean,
    val selectedByDefault: Boolean,
    val estimatedCompressedSizeMb: Int,
    val installedAt: Instant?,
    val lastCheckedAt: Instant?,
    val lastError: String?,
    val installStage: String?,
    val lastErrorCode: String?,
)

data class RunnerImageOperationResult(
    val success: Boolean,
    val detail: String,
    val errorCode: String? = null,
)

interface RunnerImageClient {
    fun inspect(imageRef: String): RunnerImageOperationResult

    fun pull(imageRef: String): RunnerImageOperationResult

    fun build(
        imageRef: String,
        contextPath: String,
    ): RunnerImageOperationResult

    fun remove(imageRef: String): RunnerImageOperationResult
}

fun interface RunnerInstallTaskExecutor {
    fun execute(task: () -> Unit)
}

@Configuration
class RunnerInstallConfiguration {
    @Bean
    fun runnerInstallTaskExecutor(): RunnerInstallTaskExecutor {
        val executor = SimpleAsyncTaskExecutor("runner-install-")
        return RunnerInstallTaskExecutor { task -> executor.execute(task) }
    }
}

class RunnerBuildContextResolver(
    private val root: String,
) {
    fun pathFor(language: String): String = Path.of(root).resolve(language).normalize().toString()
}

@Service
class RunnerLanguageService(
    private val catalog: RunnerImageCatalog,
    private val store: RunnerLanguageInstallationStore,
    private val imageClient: RunnerImageClient,
    private val properties: RunnerProperties,
    private val transactionTemplate: TransactionOperations,
    private val installTaskExecutor: RunnerInstallTaskExecutor,
) {
    private val operationLocks = ConcurrentHashMap<String, Any>()
    private val inFlightLanguages = ConcurrentHashMap.newKeySet<String>()
    private val buildContextResolver = RunnerBuildContextResolver(properties.buildContextRoot)

    @Transactional
    fun list(): RunnerLanguageListResponse {
        ensureCatalogRows()
        return response()
    }

    @Transactional
    fun refresh(): RunnerLanguageListResponse {
        ensureCatalogRows()
        catalog.languages().forEach { descriptor ->
            val entity = store.findByLanguage(descriptor.language) ?: return@forEach
            val inspection = imageClient.inspect(descriptor.imageRef)
            val now = Instant.now()
            entity.imageRef = descriptor.imageRef
            entity.lastCheckedAt = now
            if (entity.status == RunnerLanguageStatuses.INSTALLING && descriptor.language in inFlightLanguages) {
                store.save(entity)
                return@forEach
            }
            if (inspection.success) {
                entity.status = RunnerLanguageStatuses.INSTALLED
                entity.installedAt = entity.installedAt ?: now
                entity.lastError = null
                entity.installStage = null
                entity.lastErrorCode = null
            } else {
                entity.status =
                    if (entity.desiredEnabled) {
                        RunnerLanguageStatuses.MISSING
                    } else {
                        RunnerLanguageStatuses.AVAILABLE
                    }
                entity.installedAt = null
                entity.lastError = null
                entity.installStage = null
                entity.lastErrorCode = null
            }
            store.save(entity)
        }
        return response()
    }

    fun install(language: String): RunnerLanguageResponse {
        val descriptor = catalog.requireLanguage(language)
        if (!inFlightLanguages.add(language)) {
            return currentResponse(descriptor)
        }

        val response =
            try {
                updateLanguage(descriptor) { entity ->
                    entity.desiredEnabled = true
                    entity.imageRef = descriptor.imageRef
                    entity.status = RunnerLanguageStatuses.INSTALLING
                    entity.installedAt = null
                    entity.lastCheckedAt = Instant.now()
                    entity.lastError = null
                    entity.lastErrorCode = null
                    entity.installStage = RunnerInstallStages.CHECKING_IMAGE
                }
            } catch (ex: RuntimeException) {
                inFlightLanguages.remove(language)
                throw ex
            }

        installTaskExecutor.execute {
            try {
                runInstallWorker(language)
            } finally {
                inFlightLanguages.remove(language)
            }
        }

        return response
    }

    @Transactional
    fun remove(language: String): RunnerLanguageResponse {
        val descriptor = catalog.requireLanguage(language)
        return synchronized(operationLocks.computeIfAbsent(language) { Any() }) {
            ensureCatalogRows()
            val entity = store.findByLanguage(language) ?: newEntity(descriptor)
            val inspection = imageClient.inspect(descriptor.imageRef)
            val result =
                if (inspection.success) {
                    imageClient.remove(descriptor.imageRef)
                } else {
                    RunnerImageOperationResult(success = true, detail = "Runner image is not installed")
                }
            entity.desiredEnabled = false
            entity.imageRef = descriptor.imageRef
            entity.lastCheckedAt = Instant.now()
            entity.installedAt = null
            entity.status =
                if (result.success) {
                    RunnerLanguageStatuses.AVAILABLE
                } else {
                    RunnerLanguageStatuses.FAILED
                }
            entity.lastError = if (result.success) null else result.detail
            entity.lastErrorCode = if (result.success) null else result.errorCode
            entity.installStage = null
            store.save(entity)
            entity.toResponse(descriptor)
        }
    }

    private fun ensureCatalogRows() {
        catalog.languages().forEach { descriptor ->
            val existing = store.findByLanguage(descriptor.language)
            if (existing == null) {
                store.save(newEntity(descriptor))
            } else if (existing.imageRef != descriptor.imageRef) {
                existing.imageRef = descriptor.imageRef
                store.save(existing)
            }
        }
    }

    private fun response(): RunnerLanguageListResponse {
        val rows = store.findAll().associateBy { it.language }
        return RunnerLanguageListResponse(
            languages =
                catalog
                    .languages()
                    .map { descriptor ->
                        (rows[descriptor.language] ?: newEntity(descriptor)).toResponse(descriptor)
                    },
        )
    }

    private fun newEntity(descriptor: RunnerLanguageDescriptor): RunnerLanguageInstallationEntity =
        RunnerLanguageInstallationEntity(
            language = descriptor.language,
            desiredEnabled = descriptor.selectedByDefault,
            imageRef = descriptor.imageRef,
            status = if (descriptor.selectedByDefault) RunnerLanguageStatuses.MISSING else RunnerLanguageStatuses.AVAILABLE,
        )

    private fun currentResponse(descriptor: RunnerLanguageDescriptor): RunnerLanguageResponse =
        transactionTemplate.execute {
            ensureCatalogRows()
            (store.findByLanguage(descriptor.language) ?: newEntity(descriptor)).toResponse(descriptor)
        }!!

    private fun updateLanguage(
        descriptor: RunnerLanguageDescriptor,
        update: (RunnerLanguageInstallationEntity) -> Unit,
    ): RunnerLanguageResponse =
        transactionTemplate.execute {
            ensureCatalogRows()
            val entity = store.findByLanguage(descriptor.language) ?: newEntity(descriptor)
            update(entity)
            store.save(entity)
            entity.toResponse(descriptor)
        }!!

    private fun runInstallWorker(language: String) {
        val descriptor = catalog.requireLanguage(language)
        val inspection = imageClient.inspect(descriptor.imageRef)
        if (inspection.success) {
            markInstalled(descriptor)
            return
        }

        val acquireResult =
            when (descriptor.imageSource) {
                RunnerImageSources.LOCAL -> {
                    updateStage(descriptor, RunnerInstallStages.BUILDING_LOCAL_IMAGE)
                    imageClient.build(descriptor.imageRef, buildContextResolver.pathFor(descriptor.language))
                }
                RunnerImageSources.REGISTRY -> {
                    updateStage(descriptor, RunnerInstallStages.PULLING_IMAGE)
                    imageClient.pull(descriptor.imageRef)
                }
                RunnerImageSources.BUNDLED -> {
                    markFailed(
                        descriptor = descriptor,
                        errorCode = RunnerImageErrorCodes.BUNDLED_IMAGE_MISSING,
                        message = "${descriptor.displayName} runner image is missing from this offline bundle. Reinstall LearnLoop or import the bundled runner image and refresh.",
                    )
                    return
                }
                else -> RunnerImageOperationResult(false, "Runner image source is not supported", "unsupported_image_source")
            }

        if (!acquireResult.success) {
            markFailed(descriptor, acquireResult.errorCode, userFacingError(descriptor, acquireResult))
            return
        }

        updateStage(descriptor, RunnerInstallStages.VERIFYING_IMAGE)
        val verification = imageClient.inspect(descriptor.imageRef)
        if (verification.success) {
            markInstalled(descriptor)
        } else {
            markFailed(descriptor, verification.errorCode, userFacingError(descriptor, verification))
        }
    }

    private fun updateStage(
        descriptor: RunnerLanguageDescriptor,
        stage: String,
    ) {
        updateLanguage(descriptor) { entity ->
            entity.status = RunnerLanguageStatuses.INSTALLING
            entity.installStage = stage
            entity.lastCheckedAt = Instant.now()
        }
    }

    private fun markInstalled(descriptor: RunnerLanguageDescriptor) {
        updateLanguage(descriptor) { entity ->
            val now = Instant.now()
            entity.desiredEnabled = true
            entity.imageRef = descriptor.imageRef
            entity.status = RunnerLanguageStatuses.INSTALLED
            entity.installedAt = now
            entity.lastCheckedAt = now
            entity.lastError = null
            entity.lastErrorCode = null
            entity.installStage = null
        }
    }

    private fun markFailed(
        descriptor: RunnerLanguageDescriptor,
        errorCode: String?,
        message: String,
    ) {
        updateLanguage(descriptor) { entity ->
            entity.desiredEnabled = true
            entity.imageRef = descriptor.imageRef
            entity.status = RunnerLanguageStatuses.FAILED
            entity.installedAt = null
            entity.lastCheckedAt = Instant.now()
            entity.lastError = message
            entity.lastErrorCode = errorCode
            entity.installStage = null
        }
    }

    private fun userFacingError(
        descriptor: RunnerLanguageDescriptor,
        result: RunnerImageOperationResult,
    ): String =
        when (result.errorCode) {
            RunnerImageErrorCodes.REGISTRY_AUTH_FAILED ->
                "${descriptor.displayName} runner image is not publicly accessible. Check the runner image package visibility or Docker registry access and retry."
            RunnerImageErrorCodes.LOCAL_BUILD_CONTEXT_MISSING ->
                "${descriptor.displayName} runner build files are missing. Rebuild or reinstall LearnLoop and retry."
            RunnerImageErrorCodes.DOCKER_DAEMON_UNREACHABLE ->
                "Docker is not running or the Docker socket is unavailable. Start Docker and retry."
            RunnerImageErrorCodes.DOCKER_MISSING ->
                "Docker command is not installed. Install Docker and retry."
            RunnerImageErrorCodes.DISK_FULL ->
                "Docker does not have enough disk space to install this runner. Free disk space and retry."
            RunnerImageErrorCodes.TIMEOUT ->
                "${descriptor.displayName} runner install timed out. Check Docker status and retry."
            else -> result.detail.ifBlank { "${descriptor.displayName} runner install failed." }
        }
}

@RestController
class RunnerLanguageController(
    private val runnerLanguageService: RunnerLanguageService,
) {
    @GetMapping("/api/runner/languages")
    fun list(
        @AuthenticationPrincipal currentUser: CurrentUser,
    ): RunnerLanguageListResponse = runnerLanguageService.list()

    @PostMapping("/api/runner/languages/{language}/install")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun install(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable language: String,
    ): RunnerLanguageResponse = runnerLanguageService.install(language)

    @PostMapping("/api/runner/languages/{language}/remove")
    fun remove(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable language: String,
    ): RunnerLanguageResponse = runnerLanguageService.remove(language)

    @PostMapping("/api/runner/languages/refresh")
    fun refresh(
        @AuthenticationPrincipal currentUser: CurrentUser,
    ): RunnerLanguageListResponse = runnerLanguageService.refresh()
}

@Service
class ProcessRunnerImageClient(
    private val properties: RunnerProperties,
) : RunnerImageClient {
    override fun inspect(imageRef: String): RunnerImageOperationResult = runDocker(properties.commandTimeout, "image", "inspect", imageRef)

    override fun pull(imageRef: String): RunnerImageOperationResult = runDocker(properties.installCommandTimeout, "pull", imageRef)

    override fun build(
        imageRef: String,
        contextPath: String,
    ): RunnerImageOperationResult {
        val context = Path.of(contextPath)
        if (!Files.isDirectory(context) || !Files.isRegularFile(context.resolve("Dockerfile"))) {
            return RunnerImageOperationResult(
                success = false,
                detail = "Runner build context is missing",
                errorCode = RunnerImageErrorCodes.LOCAL_BUILD_CONTEXT_MISSING,
            )
        }
        return runDocker(properties.installCommandTimeout, "build", "-t", imageRef, context.toString())
    }

    override fun remove(imageRef: String): RunnerImageOperationResult = runDocker(properties.commandTimeout, "rmi", imageRef)

    private fun runDocker(
        timeout: java.time.Duration,
        vararg args: String,
    ): RunnerImageOperationResult {
        val process =
            try {
                ProcessBuilder(listOf(properties.dockerCommand) + args)
                    .redirectErrorStream(true)
                    .start()
            } catch (_: IOException) {
                return RunnerImageOperationResult(success = false, detail = "Docker command is not installed", errorCode = "docker_missing")
            }
        val outputFuture = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }

        val completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!completed) {
            process.destroyForcibly()
            outputFuture.cancel(true)
            return RunnerImageOperationResult(success = false, detail = "Docker command timed out", errorCode = "timeout")
        }

        val output = sanitizeOutput(readOutput(outputFuture))
        if (process.exitValue() == 0) {
            return RunnerImageOperationResult(success = true, detail = output.ifBlank { "Docker command completed" })
        }
        return RunnerImageOperationResult(success = false, detail = output.ifBlank { "Docker command failed" }, errorCode = classifyFailure(output))
    }

    private fun readOutput(outputFuture: CompletableFuture<String>): String =
        runCatching { outputFuture.get(1, TimeUnit.SECONDS) }.getOrDefault("")

    private fun classifyFailure(output: String): String =
        when {
            output.contains("Cannot connect to the Docker daemon", ignoreCase = true) -> RunnerImageErrorCodes.DOCKER_DAEMON_UNREACHABLE
            output.contains("no space left", ignoreCase = true) -> RunnerImageErrorCodes.DISK_FULL
            output.contains("pull access denied", ignoreCase = true) ||
                output.contains("unauthorized", ignoreCase = true) ||
                output.contains("authentication", ignoreCase = true) -> RunnerImageErrorCodes.REGISTRY_AUTH_FAILED
            output.contains("not found", ignoreCase = true) -> RunnerImageErrorCodes.IMAGE_NOT_FOUND
            else -> "docker_command_failed"
        }

    private fun sanitizeOutput(output: String): String =
        output
            .replace(secretLikeRegex, "$1[redacted]")
            .take(MAX_OUTPUT_CHARS)
            .trim()

    private companion object {
        const val MAX_OUTPUT_CHARS = 4_000
        val secretLikeRegex = Regex("""(?i)\b(authorization\s*[:=]\s*|token\s*[:=]\s*|password\s*[:=]\s*|secret\s*[:=]\s*)\S+""")
    }
}

private fun RunnerLanguageInstallationEntity.toResponse(descriptor: RunnerLanguageDescriptor): RunnerLanguageResponse {
    val installed = status == RunnerLanguageStatuses.INSTALLED
    return RunnerLanguageResponse(
        language = descriptor.language,
        displayName = descriptor.displayName,
        imageRef = descriptor.imageRef,
        imageSource = descriptor.imageSource,
        status = status,
        installed = installed,
        runnable = installed,
        desiredEnabled = desiredEnabled,
        selectedByDefault = descriptor.selectedByDefault,
        estimatedCompressedSizeMb = descriptor.estimatedCompressedSizeMb,
        installedAt = installedAt,
        lastCheckedAt = lastCheckedAt,
        lastError = lastError,
        installStage = installStage,
        lastErrorCode = lastErrorCode,
    )
}
