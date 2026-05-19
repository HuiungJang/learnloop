package com.aicodelearning.evidence

import com.aicodelearning.auth.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant

@RestController
class EvidenceController(
    private val evidenceService: EvidenceService,
) {
    @PostMapping("/api/ingest/manual")
    @ResponseStatus(HttpStatus.CREATED)
    fun ingestManual(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: ManualIngestRequest,
    ): ManualIngestResponse {
        val result = evidenceService.ingestManual(currentUser, request)
        return ManualIngestResponse(bundle = result.bundle.toResponse(), evidenceItem = result.item.toResponse(includeContent = false))
    }

    @PostMapping("/api/ingest/local-ai-session")
    @ResponseStatus(HttpStatus.CREATED)
    fun ingestLocalAiSession(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: LocalAiSessionIngestRequest,
    ): LocalAiSessionIngestResponse {
        val result = evidenceService.ingestLocalAiSession(currentUser, request)
        return LocalAiSessionIngestResponse(
            bundle = result.bundle.toResponse(),
            evidenceItems = result.items.map { it.toResponse(includeContent = false) },
            ignoredArtifacts = result.ignoredArtifacts.map { it.toResponse() },
            duplicate = result.duplicate,
        )
    }

    @GetMapping("/api/evidence/{bundleId}")
    fun readEvidence(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable bundleId: String,
    ): EvidenceDetailResponse {
        val detail = evidenceService.readBundle(currentUser, bundleId)
        val contentLimit = if (detail.bundle.sourceKind == LocalAiSessionPolicy.SOURCE_KIND) LOCAL_SESSION_DETAIL_EXCERPT_LIMIT else null
        return EvidenceDetailResponse(
            bundle = detail.bundle.toResponse(),
            evidenceItems = detail.items.map { it.toResponse(includeContent = true, contentLimit = contentLimit) },
        )
    }

    @DeleteMapping("/api/evidence/{bundleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteEvidence(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable bundleId: String,
    ) {
        evidenceService.deleteBundle(currentUser, bundleId)
    }

    @PatchMapping("/api/evidence/{bundleId}/attribution")
    fun updateAttribution(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable bundleId: String,
        @Valid @RequestBody request: AttributionOverrideRequest,
    ): SourceBundleResponse = evidenceService.updateAttribution(currentUser, bundleId, request).toResponse()

    @PostMapping("/api/evidence/{bundleId}/purge-raw")
    fun purgeBundleRaw(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable bundleId: String,
    ): RawPurgeResponse = evidenceService.purgeBundleRaw(currentUser, bundleId)

    @PostMapping("/api/evidence/purge-raw")
    fun purgeRaw(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: ScopedRawPurgeRequest,
    ): RawPurgeResponse =
        evidenceService.purgeRaw(
            currentUser = currentUser,
            organizationId = request.organizationId,
            repositoryUrl = request.repositoryUrl,
            purgeAll = request.purgeAll,
        )
}

data class ManualIngestRequest(
    @field:NotBlank
    val organizationId: String = "",
    val teamId: String? = null,
    val projectId: String? = null,
    @field:NotBlank
    val title: String = "",
    val sourceKind: String = "code",
    val repositoryUrl: String? = null,
    val pullRequestUrl: String? = null,
    val commitSha: String? = null,
    val branchName: String? = null,
    val filePaths: List<String> = emptyList(),
    val provenance: Map<String, String> = emptyMap(),
    @field:Size(max = 20_000)
    @field:NotBlank
    val content: String = "",
)

data class LocalAiSessionIngestRequest(
    @field:NotBlank
    val organizationId: String = "",
    val teamId: String? = null,
    val projectId: String? = null,
    @field:NotBlank
    val title: String = "",
    val sourceKind: String = LocalAiSessionPolicy.SOURCE_KIND,
    @field:NotBlank
    val repoIdentityHash: String = "",
    @field:NotBlank
    val repositoryDisplayLabel: String = "",
    val repositoryUrl: String? = null,
    val commitSha: String? = null,
    val branchName: String? = null,
    @field:NotBlank
    val toolProvider: String = "",
    val toolSessionId: String? = null,
    val toolEventId: String? = null,
    val timestampBucket: String? = null,
    @field:NotBlank
    val idempotencyKey: String = "",
    val autoAttribution: String = "ai_assisted",
    val attributionConfidence: BigDecimal? = null,
    val attributionReasons: List<String> = emptyList(),
    @field:Size(min = 1)
    val artifacts: List<LocalAiSessionArtifactRequest> = emptyList(),
)

data class LocalAiSessionArtifactRequest(
    @field:NotBlank
    val itemType: String = "",
    val repoRelativePath: String? = null,
    val sizeBytes: Long? = null,
    val metadata: Map<String, String> = emptyMap(),
    @field:NotBlank
    val contentHash: String = "",
    val contentTruncated: Boolean = false,
    val limitReason: String? = null,
    val content: String? = null,
)

data class ManualIngestResponse(
    val bundle: SourceBundleResponse,
    val evidenceItem: EvidenceItemResponse,
)

data class LocalAiSessionIngestResponse(
    val bundle: SourceBundleResponse,
    val evidenceItems: List<EvidenceItemResponse>,
    val ignoredArtifacts: List<IgnoredLocalSessionArtifactResponse>,
    val duplicate: Boolean,
)

data class IgnoredLocalSessionArtifactResponse(
    val itemType: String,
    val repoRelativePath: String?,
    val contentHash: String,
    val reason: String,
)

data class EvidenceDetailResponse(
    val bundle: SourceBundleResponse,
    val evidenceItems: List<EvidenceItemResponse>,
)

data class ScopedRawPurgeRequest(
    @field:NotBlank
    val organizationId: String = "",
    val repositoryUrl: String? = null,
    val purgeAll: Boolean = false,
)

data class AttributionOverrideRequest(
    @field:NotBlank
    val userAttribution: String = "",
    val attributionConfidence: BigDecimal? = null,
    @field:Size(max = 20)
    val attributionReasons: List<String> = emptyList(),
)

data class RawPurgeResponse(
    val purgedBundles: Int,
    val purgedItems: Int,
)

data class SourceBundleResponse(
    val id: String,
    val organizationId: String,
    val teamId: String?,
    val projectId: String?,
    val title: String,
    val sourceKind: String,
    val status: String,
    val repositoryUrl: String?,
    val pullRequestUrl: String?,
    val commitSha: String?,
    val branchName: String?,
    val filePathsJson: String,
    val provenanceJson: String,
    val contentHash: String,
    val secretFindingsJson: String,
    val createdAt: Instant,
    val deletedAt: Instant?,
    val deletedByUserId: String?,
    val deletionReason: String?,
    val autoAttribution: String,
    val userAttribution: String?,
    val attributionConfidence: BigDecimal?,
    val attributionReasonsJson: String,
)

data class EvidenceItemResponse(
    val id: String,
    val bundleId: String,
    val itemType: String,
    val contentText: String?,
    val contentHash: String,
    val createdAt: Instant,
    val rawPurgedAt: Instant?,
    val rawPurgeReason: String?,
    val repoRelativePath: String?,
    val sizeBytes: Long?,
    val metadataJson: String,
    val contentTruncated: Boolean,
    val limitReason: String?,
)

fun SourceBundleEntity.toResponse(): SourceBundleResponse =
    SourceBundleResponse(
        id = id,
        organizationId = organizationId,
        teamId = teamId,
        projectId = projectId,
        title = title,
        sourceKind = sourceKind,
        status = status,
        repositoryUrl = repositoryUrl,
        pullRequestUrl = pullRequestUrl,
        commitSha = commitSha,
        branchName = branchName,
        filePathsJson = filePathsJson,
        provenanceJson = provenanceJson,
        contentHash = contentHash,
        secretFindingsJson = secretFindingsJson,
        createdAt = createdAt,
        deletedAt = deletedAt,
        deletedByUserId = deletedByUserId,
        deletionReason = deletionReason,
        autoAttribution = autoAttribution,
        userAttribution = userAttribution,
        attributionConfidence = attributionConfidence,
        attributionReasonsJson = attributionReasonsJson,
    )

fun EvidenceItemEntity.toResponse(
    includeContent: Boolean,
    contentLimit: Int? = null,
): EvidenceItemResponse =
    EvidenceItemResponse(
        id = id,
        bundleId = bundleId,
        itemType = itemType,
        contentText = if (includeContent) contentText?.bounded(contentLimit) else null,
        contentHash = contentHash,
        createdAt = createdAt,
        rawPurgedAt = rawPurgedAt,
        rawPurgeReason = rawPurgeReason,
        repoRelativePath = repoRelativePath,
        sizeBytes = sizeBytes,
        metadataJson = metadataJson,
        contentTruncated = contentTruncated,
        limitReason = limitReason,
    )

private fun String.bounded(limit: Int?): String =
    if (limit == null || length <= limit) {
        this
    } else {
        take(limit)
    }

private const val LOCAL_SESSION_DETAIL_EXCERPT_LIMIT = 2_000

private fun IgnoredLocalSessionArtifact.toResponse(): IgnoredLocalSessionArtifactResponse =
    IgnoredLocalSessionArtifactResponse(
        itemType = itemType,
        repoRelativePath = repoRelativePath,
        contentHash = contentHash,
        reason = reason,
    )
