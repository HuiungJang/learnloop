package com.aicodelearning.evidence

import com.aicodelearning.auth.sha256Hex
import com.aicodelearning.platform.BadRequestException
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

@Service
class LocalSessionArtifactPreflight(
    private val secretScanner: SecretScanner,
) {
    fun validate(
        request: LocalAiSessionIngestRequest,
        approvedRepoRoot: Path,
    ): LocalSessionPreflightResult {
        if (request.artifacts.isEmpty()) {
            throw BadRequestException("local session artifacts are required")
        }
        if (request.artifacts.size > MAX_ARTIFACTS) {
            throw BadRequestException("local session may include at most $MAX_ARTIFACTS artifacts")
        }

        val repoRoot = approvedRepoRoot.toRealPath()
        var totalBytes = 0L
        var totalDiffBytes = 0L
        val accepted = mutableListOf<LocalSessionPreflightArtifact>()
        val ignored = mutableListOf<IgnoredLocalSessionArtifact>()
        val findings = mutableListOf<LocalSessionSecretFinding>()
        val filePaths = mutableSetOf<String>()

        request.artifacts.forEachIndexed { index, artifact ->
            requireAllowedItemType(artifact.itemType)
            val normalizedPath = artifact.repoRelativePath?.let(::normalizeArtifactPath)
            if (artifact.itemType in LocalAiSessionPolicy.itemTypesRequiringPath && normalizedPath == null) {
                throw BadRequestException("${artifact.itemType} artifacts require a repo-relative path")
            }
            if (artifact.itemType !in LocalAiSessionPolicy.itemTypesRequiringPath && normalizedPath != null) {
                throw BadRequestException("${artifact.itemType} artifacts must not include a repo-relative path")
            }
            if (artifact.itemType in LocalAiSessionPolicy.itemTypesRequiringPath && normalizedPath != null) {
                filePaths += normalizedPath
                if (filePaths.size > MAX_FILES) {
                    throw BadRequestException("local session may include at most $MAX_FILES files")
                }
            }
            val resolvedPath = normalizedPath?.let { resolveContainedPath(repoRoot, it) }
            requireSafeContentHash(artifact.contentHash)

            val byteSize = artifact.effectiveByteSize()
            val metadataBytes = artifact.metadataByteSize()
            if (byteSize > MAX_SESSION_BYTES.toLong() - totalBytes) {
                throw BadRequestException("local session exceeds maximum size")
            }
            totalBytes += byteSize
            if (metadataBytes > MAX_SESSION_BYTES.toLong() - totalBytes) {
                throw BadRequestException("local session exceeds maximum size")
            }
            totalBytes += metadataBytes
            if (LocalAiSessionPolicy.isDiff(artifact.itemType)) {
                totalDiffBytes =
                    if (totalDiffBytes > MAX_DIFF_BYTES.toLong() || byteSize > MAX_DIFF_BYTES.toLong() - totalDiffBytes) {
                        MAX_DIFF_BYTES + 1L
                    } else {
                        totalDiffBytes + byteSize
                    }
            }

            val metadataFindingsByKey = linkedMapOf<String, List<SecretFinding>>()
            artifact.metadata.forEach { (key, value) ->
                val normalizedKey = key.trim()
                val valueFindings = secretScanner.scan(value.trim())
                if (valueFindings.isNotEmpty()) {
                    metadataFindingsByKey[normalizedKey] = metadataFindingsByKey[normalizedKey].orEmpty() + valueFindings
                }
            }
            val safeMetadata = sanitizeMetadata(artifact.metadata)
            val pathsToScan = listOfNotNull(normalizedPath, resolvedPath?.realRepoRelativePath).distinct()
            val pathFindings = pathsToScan.flatMap(secretScanner::scan)
            val resultPath = normalizedPath.takeIf { pathFindings.isEmpty() }
            val ignoreReason = normalizedPath?.let { ignoreReason(it) ?: resolvedPath?.realRepoRelativePath?.let(::ignoreReason) }
            if (ignoreReason != null) {
                val ignoredContentFindings = artifact.content?.let(secretScanner::scan).orEmpty()
                val ignoredMetadataFindings = metadataFindingsByKey.values.flatten()
                findings +=
                    (pathFindings + ignoredContentFindings + ignoredMetadataFindings).map {
                        LocalSessionSecretFinding(index, artifact.itemType, resultPath, it)
                    }
                ignored +=
                    IgnoredLocalSessionArtifact(
                        itemType = artifact.itemType,
                        repoRelativePath = resultPath,
                        contentHash = safeReportedContentHash(artifact.contentHash),
                        reason = ignoreReason,
                )
                return@forEachIndexed
            }

            val metadataForResult = linkedMapOf<String, String>()
            safeMetadata.forEach { (key, value) ->
                if (metadataFindingsByKey[key].orEmpty().isEmpty()) {
                    metadataForResult[key] = value.take(MAX_METADATA_VALUE_CHARS)
                }
            }
            val contentFindings = artifact.content?.let(secretScanner::scan).orEmpty()
            val metadataFindings = metadataFindingsByKey.values.flatten()
            val artifactFindings = pathFindings + metadataFindings + contentFindings
            val hasUnsafeContentPath = artifact.content?.let(::containsAbsolutePath) == true

            val limitReason = artifact.effectiveLimitReason(byteSize, totalDiffBytes, hasUnsafeContentPath)
            findings += artifactFindings.map { LocalSessionSecretFinding(index, artifact.itemType, resultPath, it) }

            accepted +=
                LocalSessionPreflightArtifact(
                    itemType = artifact.itemType,
                    repoRelativePath = resultPath,
                    contentHash =
                        if (artifact.content != null && artifactFindings.isEmpty() && !hasUnsafeContentPath) {
                            sha256Hex(artifact.content)
                        } else {
                            safeReportedContentHash(artifact.contentHash)
                        },
                    sizeBytes = byteSize,
                    metadata = metadataForResult,
                    contentText = if (LocalAiSessionPolicy.requiresGenerationContent(artifact.itemType) && limitReason == null && artifactFindings.isEmpty()) artifact.content else null,
                    contentTruncated = artifact.contentTruncated || limitReason != null,
                    limitReason = limitReason,
                    secretFindings = artifactFindings,
                )
        }

        val hasSecrets = findings.isNotEmpty()
        val hasMissingRequiredContent = accepted.any { LocalAiSessionPolicy.requiresGenerationContent(it.itemType) && it.contentText == null }
        val hasGenerationArtifact = accepted.any { it.itemType in LocalAiSessionPolicy.generationItemTypes }
        return LocalSessionPreflightResult(
            status =
                when {
                    hasSecrets -> "quarantined_secret"
                    hasMissingRequiredContent -> "rejected_by_path_or_size"
                    else -> "scanned_clean"
                },
            generationEligible = !hasSecrets && !hasMissingRequiredContent && hasGenerationArtifact,
            totalBytes = totalBytes,
            artifacts = accepted,
            ignoredArtifacts = ignored,
            secretFindings = findings,
        )
    }

    fun normalizeArtifactPath(path: String): String {
        val trimmed = Normalizer.normalize(path.trim(), Normalizer.Form.NFC)
        if (trimmed.isBlank()) {
            throw BadRequestException("artifact path is required")
        }
        if (trimmed.length > MAX_PATH_CHARS) {
            throw BadRequestException("artifact path exceeds maximum length")
        }
        if (trimmed.contains('\u0000')) {
            throw BadRequestException("artifact path contains invalid characters")
        }
        if (trimmed.startsWith("/") || trimmed.startsWith("~") || windowsDrivePattern.matches(trimmed)) {
            throw BadRequestException("artifact path must be relative")
        }
        if (trimmed.contains('\\')) {
            throw BadRequestException("artifact path must use forward slashes")
        }
        if (urlEncodedTraversalPattern.containsMatchIn(trimmed)) {
            throw BadRequestException("artifact path contains encoded traversal")
        }

        val parts = trimmed.split("/")
        if (parts.any { it.isBlank() || it == "." || it == ".." }) {
            throw BadRequestException("artifact path must stay inside the approved repository")
        }
        return parts.joinToString("/")
    }

    private fun resolveContainedPath(
        repoRoot: Path,
        repoRelativePath: String,
    ): ResolvedArtifactPath {
        val target = repoRoot.resolve(repoRelativePath).normalize()
        if (!target.startsWith(repoRoot)) {
            throw BadRequestException("artifact path must stay inside the approved repository")
        }

        var resolved = repoRoot
        repoRelativePath.split("/").forEach { part ->
            resolved = resolved.resolve(part).normalize()
            if (!resolved.startsWith(repoRoot)) {
                throw BadRequestException("artifact path must stay inside the approved repository")
            }
            if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(resolved)) {
                val real = resolved.toRealPath()
                if (!real.startsWith(repoRoot)) {
                    throw BadRequestException("artifact path must not escape through symlinks")
                }
                resolved = real
            }
        }
        if (!resolved.startsWith(repoRoot)) {
            throw BadRequestException("artifact path must stay inside the approved repository")
        }
        return ResolvedArtifactPath(realRepoRelativePath = repoRoot.relativize(resolved).toPortablePath())
    }

    private fun requireAllowedItemType(itemType: String) {
        if (itemType !in LocalAiSessionPolicy.itemTypes) {
            throw BadRequestException("artifact itemType is not supported")
        }
    }

    private fun requireSafeContentHash(contentHash: String) {
        if (!sha256Pattern.matches(contentHash)) {
            throw BadRequestException("artifact contentHash must be a SHA-256 hex digest")
        }
    }

    private fun sanitizeMetadata(metadata: Map<String, String>): Map<String, String> {
        val safe = linkedMapOf<String, String>()
        var totalBytes = 0
        metadata.forEach { (key, value) ->
            val normalizedKey = key.trim()
            if (normalizedKey !in allowedMetadataKeys) {
                return@forEach
            }
            val rawValue = value.trim()
            val entryBytes = normalizedKey.toByteArray(Charsets.UTF_8).size + rawValue.toByteArray(Charsets.UTF_8).size
            totalBytes += entryBytes
            if (totalBytes > MAX_METADATA_BYTES) {
                throw BadRequestException("artifact metadata exceeds maximum size")
            }
            if (rawValue.isBlank() || containsAbsolutePath(rawValue)) {
                return@forEach
            }
            val normalizedValue = normalizeMetadataValue(normalizedKey, rawValue) ?: return@forEach
            safe[normalizedKey] = normalizedValue
        }
        return safe
    }

    private fun normalizeMetadataValue(
        key: String,
        value: String,
    ): String? =
        when (key) {
            "language", "event", "tool", "provider" -> value.takeIf { metadataSlugPattern.matches(it) }
            "exitCode" -> value.toIntOrNull()?.takeIf { it in 0..255 }?.toString()
            "durationMs" -> value.toLongOrNull()?.takeIf { it in 0..MAX_METADATA_DURATION_MS }?.toString()
            "repoIdentityHash" -> value.takeIf { metadataIdentityPattern.matches(it) }
            "repoRelativePath" -> value.takeIf { normalizeOptionalMetadataPath(it) != null }
            "changedAt", "activityWindowStartedAt", "activityWindowEndedAt" -> normalizeMetadataInstant(value)
            "confidence" -> normalizeMetadataConfidence(value)
            "reasonCodes" -> normalizeMetadataReasonCodes(value)
            "truncated" ->
                when (value.lowercase(Locale.ROOT)) {
                    "true" -> "true"
                    "false" -> "false"
                    else -> null
                }
            else -> null
        }

    private fun normalizeOptionalMetadataPath(value: String): String? =
        runCatching { normalizeArtifactPath(value) }.getOrNull()

    private fun normalizeMetadataInstant(value: String): String? =
        runCatching { Instant.parse(value).toString() }.getOrNull()

    private fun normalizeMetadataConfidence(value: String): String? {
        val confidence = value.toBigDecimalOrNull() ?: return null
        return confidence
            .takeIf { it >= BigDecimal.ZERO && it <= BigDecimal.ONE }
            ?.stripTrailingZeros()
            ?.toPlainString()
    }

    private fun normalizeMetadataReasonCodes(value: String): String? {
        val codes = value.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (codes.isEmpty() || codes.size > MAX_REASON_CODES) return null
        if (codes.any { !metadataReasonCodePattern.matches(it) }) return null
        if (codes.any { it !in allowedMetadataReasonCodes }) return null
        return codes.distinct().joinToString(",")
    }

    private fun containsAbsolutePath(value: String): Boolean =
        value.startsWith("/") ||
            value.startsWith("~") ||
            windowsDrivePattern.matches(value) ||
            embeddedAbsolutePathPattern.containsMatchIn(value) ||
            embeddedHomePathPattern.containsMatchIn(value) ||
            embeddedWindowsPathPattern.containsMatchIn(value) ||
            embeddedUncPathPattern.containsMatchIn(value)

    private fun ignoreReason(path: String): String? {
        val segments = path.split("/")
        val name = segments.last().lowercase(Locale.ROOT)
        if (name.startsWith(".env")) return "sensitive_file"
        if (segments.any { it.startsWith(".") }) return "hidden_path"
        if (segments.any { it.lowercase(Locale.ROOT) in ignoredDirectories }) return "ignored_directory"
        if (sensitiveFilePattern.matches(name)) return "sensitive_file"
        if (binaryOrArchivePattern.matches(name)) return "binary_or_archive"
        return null
    }

    private fun LocalAiSessionArtifactRequest.effectiveByteSize(): Long {
        if (sizeBytes != null && sizeBytes < 0L) {
            throw BadRequestException("artifact sizeBytes must be non-negative")
        }
        val contentBytes = content?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        return maxOf(sizeBytes ?: 0L, contentBytes)
    }

    private fun LocalAiSessionArtifactRequest.metadataByteSize(): Long {
        var totalBytes = 0L
        metadata.forEach { (key, value) ->
            if (key.length > MAX_METADATA_BYTES || value.length > MAX_METADATA_BYTES) {
                throw BadRequestException("artifact metadata exceeds maximum size")
            }
            val entryBytes = key.toByteArray(Charsets.UTF_8).size.toLong() + value.toByteArray(Charsets.UTF_8).size.toLong()
            if (entryBytes > MAX_METADATA_BYTES.toLong() - totalBytes) {
                throw BadRequestException("artifact metadata exceeds maximum size")
            }
            totalBytes += entryBytes
        }
        return totalBytes
    }

    private fun LocalAiSessionArtifactRequest.effectiveLimitReason(
        byteSize: Long,
        totalDiffBytes: Long,
        hasUnsafeContentPath: Boolean,
    ): String? {
        if (hasUnsafeContentPath) {
            return UNSAFE_CONTENT_PATH_LIMIT_REASON
        }
        if (contentTruncated) {
            return normalizeLimitReason(limitReason) ?: "client_truncated"
        }
        if (LocalAiSessionPolicy.isDiff(itemType) && (byteSize > MAX_DIFF_BYTES || totalDiffBytes > MAX_DIFF_BYTES)) {
            return "diff_too_large"
        }
        if (!LocalAiSessionPolicy.isDiff(itemType) && byteSize > MAX_TEXT_ARTIFACT_BYTES) {
            return "artifact_too_large"
        }
        return normalizeLimitReason(limitReason)
    }

    private fun normalizeLimitReason(limitReason: String?): String? {
        val normalized = limitReason?.trim()
        return if (normalized in allowedLimitReasons) normalized else null
    }

    private fun safeReportedContentHash(contentHash: String): String =
        sha256Hex("local-session-reported-content-hash:$contentHash")

    companion object {
        const val MAX_ARTIFACTS = 400
        const val MAX_FILES = 100
        const val MAX_TEXT_ARTIFACT_BYTES = 200 * 1024
        const val MAX_DIFF_BYTES = 1024 * 1024
        const val MAX_SESSION_BYTES = 5 * 1024 * 1024
        const val MAX_PATH_CHARS = 1000
        const val MAX_METADATA_BYTES = 16 * 1024
        const val MAX_METADATA_VALUE_CHARS = 500
        const val MAX_METADATA_DURATION_MS = 86_400_000L
        const val UNSAFE_CONTENT_PATH_LIMIT_REASON = "unsafe_content_path"

        private const val MAX_REASON_CODES = 20
        private val allowedMetadataKeys =
            setOf(
                "language",
                "event",
                "exitCode",
                "tool",
                "provider",
                "durationMs",
                "repoIdentityHash",
                "repoRelativePath",
                "changedAt",
                "activityWindowStartedAt",
                "activityWindowEndedAt",
                "confidence",
                "reasonCodes",
                "truncated",
            )
        private val allowedLimitReasons = setOf("client_truncated", "artifact_too_large", "diff_too_large", "gui_correlation_metadata_only")
        private val metadataSlugPattern = Regex("^[A-Za-z0-9][A-Za-z0-9_.+#-]{0,63}$")
        private val metadataIdentityPattern = Regex("^[A-Za-z0-9._:-]{3,128}$")
        private val metadataReasonCodePattern = Regex("^[A-Za-z0-9][A-Za-z0-9_.:#@+-]{0,119}$")
        private val allowedMetadataReasonCodes =
            setOf(
                "gui_activity_window",
                "repo_changed",
                "cli_shim",
                "patch_match",
                "single_ai_tool",
                "competing_ai_tools",
            )
        private val sha256Pattern = Regex("^[a-fA-F0-9]{64}$")
        private val windowsDrivePattern = Regex("^[A-Za-z]:.*")
        private val embeddedAbsolutePathPattern = Regex("""(^|[^A-Za-z0-9._-])/[^\s]+""")
        private val embeddedHomePathPattern = Regex("""(^|[^A-Za-z0-9._-])~[^\s]+""")
        private val embeddedWindowsPathPattern = Regex("""(^|[^A-Za-z0-9._-])[A-Za-z]:[\\/][^\s]+""")
        private val embeddedUncPathPattern = Regex("""(^|[^A-Za-z0-9._-])\\\\[^\s]+""")
        private val urlEncodedTraversalPattern = Regex("%(?:2e|2f|5c|25)", RegexOption.IGNORE_CASE)
        private val ignoredDirectories = setOf("node_modules", "vendor", "dist", "build", "target", ".gradle", ".git")
        private val sensitiveFilePattern = Regex(""".*\.(?:pem|key|crt|cer|p12|pfx|jks|keystore)$""")
        private val binaryOrArchivePattern = Regex(""".*\.(?:png|jpe?g|gif|webp|ico|pdf|zip|tar|gz|tgz|jar|class|so|dylib|dll|exe|bin|mp4|mov|mp3|wav)$""")
    }
}

private fun Path.toPortablePath(): String =
    joinToString("/") { it.toString() }

data class LocalSessionPreflightResult(
    val status: String,
    val generationEligible: Boolean,
    val totalBytes: Long,
    val artifacts: List<LocalSessionPreflightArtifact>,
    val ignoredArtifacts: List<IgnoredLocalSessionArtifact>,
    val secretFindings: List<LocalSessionSecretFinding>,
)

data class LocalSessionPreflightArtifact(
    val itemType: String,
    val repoRelativePath: String?,
    val contentHash: String,
    val sizeBytes: Long,
    val metadata: Map<String, String>,
    val contentText: String?,
    val contentTruncated: Boolean,
    val limitReason: String?,
    val secretFindings: List<SecretFinding>,
)

data class IgnoredLocalSessionArtifact(
    val itemType: String,
    val repoRelativePath: String?,
    val contentHash: String,
    val reason: String,
)

data class LocalSessionSecretFinding(
    val artifactIndex: Int,
    val itemType: String,
    val repoRelativePath: String?,
    val finding: SecretFinding,
)

private data class ResolvedArtifactPath(
    val realRepoRelativePath: String,
)
