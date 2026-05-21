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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant

@RestController
class EvidenceController(
    private val evidenceService: EvidenceService,
    private val evidenceRetentionSettingsService: EvidenceRetentionSettingsService,
    private val evidenceRetentionDryRunService: EvidenceRetentionDryRunService,
    private val evidenceRetentionCleanupService: EvidenceRetentionCleanupService,
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

    @GetMapping("/api/evidence")
    fun listEvidence(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam organizationId: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") pageSize: Int,
    ): EvidenceListResponse {
        val result = evidenceService.listBundles(currentUser, organizationId, page, pageSize)
        return EvidenceListResponse(
            bundles = result.bundles.map { it.toSummaryResponse() },
            page = result.page,
            pageSize = result.pageSize,
            total = result.total,
        )
    }

    @GetMapping("/api/evidence/{bundleId}")
    fun readEvidence(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable bundleId: String,
    ): EvidenceDetailResponse {
        val detail = evidenceService.readBundle(currentUser, bundleId)
        return EvidenceDetailResponse(
            bundle = detail.bundle.toSummaryResponse(),
            evidenceItems = detail.items.map { it.toResponse(includeContent = true, contentLimit = EVIDENCE_DETAIL_EXCERPT_LIMIT) },
        )
    }

    @GetMapping("/api/local-repositories")
    fun listLocalRepositories(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam organizationId: String,
    ): LocalRepositoryConsentListResponse =
        LocalRepositoryConsentListResponse(
            repositories =
                evidenceService
                    .listLocalRepositoryConsents(currentUser, organizationId)
                    .map { it.toResponse() },
        )

    @PatchMapping("/api/local-repositories/{repoIdentityHash}")
    fun updateLocalRepository(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable repoIdentityHash: String,
        @Valid @RequestBody request: LocalRepositoryConsentRequest,
    ): LocalRepositoryConsentResponse =
        evidenceService
            .updateLocalRepositoryConsent(currentUser, repoIdentityHash, request)
            .toResponse()

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

    @GetMapping("/api/evidence/retention-settings")
    fun readRetentionSettings(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam organizationId: String,
    ): EvidenceRetentionSettingsResponse =
        evidenceRetentionSettingsService
            .read(currentUser, organizationId)
            .toResponse()

    @PatchMapping("/api/evidence/retention-settings")
    fun updateRetentionSettings(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody request: UpdateEvidenceRetentionSettingsRequest,
    ): EvidenceRetentionSettingsResponse =
        evidenceRetentionSettingsService
            .update(currentUser, request)
            .toResponse()

    @GetMapping("/api/evidence/retention-dry-run")
    fun previewRetentionCleanup(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam organizationId: String,
    ): EvidenceRetentionDryRunResponse =
        evidenceRetentionDryRunService
            .preview(currentUser, organizationId)
            .toResponse()

    @PostMapping("/api/evidence/retention-cleanup")
    fun runRetentionCleanup(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody request: EvidenceRetentionCleanupRequest,
    ): EvidenceRetentionCleanupResponse =
        evidenceRetentionCleanupService
            .run(currentUser, request)
            .toResponse()
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
    val bundle: SourceBundleSummaryResponse,
    val evidenceItems: List<EvidenceItemResponse>,
)

data class LocalRepositoryConsentListResponse(
    val repositories: List<LocalRepositoryConsentResponse>,
)

data class LocalRepositoryConsentResponse(
    val repoIdentityHash: String,
    val organizationId: String,
    val displayLabel: String,
    val repoRoot: String?,
    val status: String,
    val updatedAt: Instant,
)

data class LocalRepositoryConsentRequest(
    @field:NotBlank
    val organizationId: String = "",
    @field:NotBlank
    val displayLabel: String = "",
    @field:NotBlank
    val status: String = "",
    val repoRoot: String? = null,
)

data class EvidenceListResponse(
    val bundles: List<SourceBundleSummaryResponse>,
    val page: Int,
    val pageSize: Int,
    val total: Long,
)

data class SourceBundleSummaryResponse(
    val id: String,
    val organizationId: String,
    val teamId: String?,
    val projectId: String?,
    val title: String,
    val sourceKind: String,
    val status: String,
    val repositoryUrl: String?,
    val commitSha: String?,
    val branchName: String?,
    val createdAt: Instant,
    val autoAttribution: String,
    val userAttribution: String?,
    val attributionConfidence: BigDecimal?,
    val attributionReasonsJson: String,
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

data class EvidenceRetentionSettingsResponse(
    val organizationId: String,
    val ownerUserId: String,
    val retentionMode: String,
    val retentionDays: Int?,
    val automaticCleanupEnabled: Boolean,
    val immediatePurge: Boolean,
    val updatedAt: Instant?,
    val lastCleanupAt: Instant?,
    val lastCleanupPurgedItems: Int,
    val lastCleanupReclaimedBytes: Long,
    val lastCleanupRemainingItems: Int,
)

data class EvidenceRetentionDryRunResponse(
    val organizationId: String,
    val retentionMode: String,
    val retentionDays: Int?,
    val automaticCleanupEnabled: Boolean,
    val immediatePurge: Boolean,
    val cutoffAt: Instant?,
    val eligibleBundles: Int,
    val eligibleItems: Int,
    val rawMetadataBundles: Int,
    val estimatedReclaimedBytes: Long,
    val artifactCategories: List<EvidenceRetentionDryRunArtifactCategoryResponse>,
    val quarantinedBundles: Int,
    val quarantinedItems: Int,
    val quarantinedEstimatedBytes: Long,
    val quarantinedBehavior: String,
)

data class EvidenceRetentionDryRunArtifactCategoryResponse(
    val itemType: String,
    val itemCount: Int,
    val estimatedBytes: Long,
)

data class EvidenceRetentionCleanupResponse(
    val organizationId: String,
    val retentionMode: String,
    val retentionDays: Int?,
    val cutoffAt: Instant?,
    val lastCleanupAt: Instant?,
    val lastCleanupPurgedItems: Int,
    val lastCleanupReclaimedBytes: Long,
    val lastCleanupRemainingItems: Int,
    val batchSize: Int,
    val purgedBundles: Int,
    val purgedItems: Int,
    val reclaimedBytes: Long,
    val remainingEligibleItems: Int,
    val activeIngestionSkippedItems: Int,
    val filesystemArtifactsDeleted: Int,
    val skippedReason: String?,
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

fun SourceBundleEntity.toSummaryResponse(): SourceBundleSummaryResponse =
    SourceBundleSummaryResponse(
        id = id,
        organizationId = organizationId,
        teamId = teamId,
        projectId = projectId,
        title = title,
        sourceKind = sourceKind,
        status = status,
        repositoryUrl = repositoryUrl,
        commitSha = commitSha,
        branchName = branchName,
        createdAt = createdAt,
        autoAttribution = autoAttribution,
        userAttribution = userAttribution,
        attributionConfidence = attributionConfidence,
        attributionReasonsJson = attributionReasonsJson,
    )

fun LocalRepositoryConsentEntity.toResponse(): LocalRepositoryConsentResponse =
    LocalRepositoryConsentResponse(
        repoIdentityHash = repoIdentityHash,
        organizationId = organizationId,
        displayLabel = displayLabel,
        repoRoot = repoRoot,
        status = status,
        updatedAt = updatedAt,
    )

fun EvidenceRetentionSettings.toResponse(): EvidenceRetentionSettingsResponse =
    EvidenceRetentionSettingsResponse(
        organizationId = organizationId,
        ownerUserId = ownerUserId,
        retentionMode = retentionMode,
        retentionDays = retentionDays,
        automaticCleanupEnabled = automaticCleanupEnabled,
        immediatePurge = immediatePurge,
        updatedAt = updatedAt,
        lastCleanupAt = lastCleanupAt,
        lastCleanupPurgedItems = lastCleanupPurgedItems,
        lastCleanupReclaimedBytes = lastCleanupReclaimedBytes,
        lastCleanupRemainingItems = lastCleanupRemainingItems,
    )

fun EvidenceRetentionDryRun.toResponse(): EvidenceRetentionDryRunResponse =
    EvidenceRetentionDryRunResponse(
        organizationId = organizationId,
        retentionMode = retentionMode,
        retentionDays = retentionDays,
        automaticCleanupEnabled = automaticCleanupEnabled,
        immediatePurge = immediatePurge,
        cutoffAt = cutoffAt,
        eligibleBundles = eligibleBundles,
        eligibleItems = eligibleItems,
        rawMetadataBundles = rawMetadataBundles,
        estimatedReclaimedBytes = estimatedReclaimedBytes,
        artifactCategories = artifactCategories.map { it.toResponse() },
        quarantinedBundles = quarantinedBundles,
        quarantinedItems = quarantinedItems,
        quarantinedEstimatedBytes = quarantinedEstimatedBytes,
        quarantinedBehavior = quarantinedBehavior,
    )

fun EvidenceRetentionDryRunArtifactCategory.toResponse(): EvidenceRetentionDryRunArtifactCategoryResponse =
    EvidenceRetentionDryRunArtifactCategoryResponse(
        itemType = itemType,
        itemCount = itemCount,
        estimatedBytes = estimatedBytes,
    )

fun EvidenceRetentionCleanupRun.toResponse(): EvidenceRetentionCleanupResponse =
    EvidenceRetentionCleanupResponse(
        organizationId = organizationId,
        retentionMode = retentionMode,
        retentionDays = retentionDays,
        cutoffAt = cutoffAt,
        lastCleanupAt = lastCleanupAt,
        lastCleanupPurgedItems = lastCleanupPurgedItems,
        lastCleanupReclaimedBytes = lastCleanupReclaimedBytes,
        lastCleanupRemainingItems = lastCleanupRemainingItems,
        batchSize = batchSize,
        purgedBundles = purgedBundles,
        purgedItems = purgedItems,
        reclaimedBytes = reclaimedBytes,
        remainingEligibleItems = remainingEligibleItems,
        activeIngestionSkippedItems = activeIngestionSkippedItems,
        filesystemArtifactsDeleted = filesystemArtifactsDeleted,
        skippedReason = skippedReason,
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

private const val EVIDENCE_DETAIL_EXCERPT_LIMIT = 2_000

private fun IgnoredLocalSessionArtifact.toResponse(): IgnoredLocalSessionArtifactResponse =
    IgnoredLocalSessionArtifactResponse(
        itemType = itemType,
        repoRelativePath = repoRelativePath,
        contentHash = contentHash,
        reason = reason,
    )
