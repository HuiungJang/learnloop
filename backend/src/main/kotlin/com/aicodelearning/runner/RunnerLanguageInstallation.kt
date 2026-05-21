package com.aicodelearning.runner

import com.aicodelearning.auth.CurrentUser
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.io.IOException
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
    val status: String,
    val installed: Boolean,
    val runnable: Boolean,
    val desiredEnabled: Boolean,
    val selectedByDefault: Boolean,
    val estimatedCompressedSizeMb: Int,
    val installedAt: Instant?,
    val lastCheckedAt: Instant?,
    val lastError: String?,
)

data class RunnerImageOperationResult(
    val success: Boolean,
    val detail: String,
    val errorCode: String? = null,
)

interface RunnerImageClient {
    fun inspect(imageRef: String): RunnerImageOperationResult

    fun pull(imageRef: String): RunnerImageOperationResult

    fun remove(imageRef: String): RunnerImageOperationResult
}

@Service
class RunnerLanguageService(
    private val catalog: RunnerImageCatalog,
    private val store: RunnerLanguageInstallationStore,
    private val imageClient: RunnerImageClient,
) {
    private val operationLocks = ConcurrentHashMap<String, Any>()

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
            if (inspection.success) {
                entity.status = RunnerLanguageStatuses.INSTALLED
                entity.installedAt = entity.installedAt ?: now
                entity.lastError = null
            } else {
                entity.status =
                    if (entity.desiredEnabled) {
                        RunnerLanguageStatuses.MISSING
                    } else {
                        RunnerLanguageStatuses.AVAILABLE
                    }
                entity.installedAt = null
                entity.lastError = null
            }
            store.save(entity)
        }
        return response()
    }

    @Transactional
    fun install(language: String): RunnerLanguageResponse {
        val descriptor = catalog.requireLanguage(language)
        return synchronized(operationLocks.computeIfAbsent(language) { Any() }) {
            ensureCatalogRows()
            val entity = store.findByLanguage(language) ?: newEntity(descriptor)
            val now = Instant.now()
            entity.desiredEnabled = true
            entity.imageRef = descriptor.imageRef
            entity.status = RunnerLanguageStatuses.INSTALLING
            entity.lastCheckedAt = now
            entity.lastError = null
            store.save(entity)

            val result = imageClient.pull(descriptor.imageRef)
            entity.lastCheckedAt = Instant.now()
            if (result.success) {
                entity.status = RunnerLanguageStatuses.INSTALLED
                entity.installedAt = entity.lastCheckedAt
                entity.lastError = null
            } else {
                entity.status = RunnerLanguageStatuses.FAILED
                entity.installedAt = null
                entity.lastError = result.detail
            }
            store.save(entity)
            entity.toResponse(descriptor)
        }
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
            output.contains("Cannot connect to the Docker daemon", ignoreCase = true) -> "docker_daemon_unreachable"
            output.contains("no space left", ignoreCase = true) -> "disk_full"
            output.contains("pull access denied", ignoreCase = true) ||
                output.contains("unauthorized", ignoreCase = true) ||
                output.contains("authentication", ignoreCase = true) -> "registry_auth_failed"
            output.contains("not found", ignoreCase = true) -> "image_not_found"
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
        status = status,
        installed = installed,
        runnable = installed,
        desiredEnabled = desiredEnabled,
        selectedByDefault = descriptor.selectedByDefault,
        estimatedCompressedSizeMb = descriptor.estimatedCompressedSizeMb,
        installedAt = installedAt,
        lastCheckedAt = lastCheckedAt,
        lastError = lastError,
    )
}
