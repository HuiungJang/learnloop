package com.aicodelearning.evidence

import com.aicodelearning.auth.CurrentUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class EvidenceRetentionDryRunService(
    private val retentionSettingsService: EvidenceRetentionSettingsService,
    private val sourceBundleRepository: SourceBundleRepository,
    private val evidenceItemRepository: EvidenceItemRepository,
) {
    @Transactional(readOnly = true)
    fun preview(
        currentUser: CurrentUser,
        organizationId: String,
    ): EvidenceRetentionDryRun {
        val settings = retentionSettingsService.read(currentUser, organizationId)
        if (!settings.automaticCleanupEnabled) {
            return EvidenceRetentionDryRun.empty(settings, quarantinedBehavior = QUARANTINED_DISABLED_BEHAVIOR)
        }

        val now = Instant.now()
        val cutoffAt =
            if (settings.immediatePurge) {
                now
            } else {
                now.minus((settings.retentionDays ?: EvidenceRetentionSettingsService.DEFAULT_RETENTION_DAYS).toLong(), ChronoUnit.DAYS)
            }
        val bundles = sourceBundleRepository.findByOrganizationId(settings.organizationId)
        if (bundles.isEmpty()) {
            return EvidenceRetentionDryRun.empty(settings, cutoffAt, QUARANTINED_INCLUDED_BEHAVIOR)
        }

        val bundleById = bundles.associateBy { it.id }
        val items =
            evidenceItemRepository
                .findByBundleIdIn(bundleById.keys)
                .filter { it.rawCleanupCandidate(cutoffAt) }
        val rawMetadataBundles =
            bundles.filter {
                !it.createdAt.isAfter(cutoffAt) && it.hasRawMetadata()
            }

        val rawMetadataBytes = rawMetadataBundles.sumOf { it.rawMetadataBytes() }
        val itemBytes = items.sumOf { it.estimatedRawBytes() }
        val bundleIds = (items.map { it.bundleId } + rawMetadataBundles.map { it.id }).toSet()
        val quarantinedBundleIds = bundleIds.filter { bundleById[it]?.isQuarantined() == true }.toSet()
        val quarantinedItems = items.filter { it.bundleId in quarantinedBundleIds }
        val artifactCategories =
            items
                .groupBy { safeArtifactCategory(it.itemType) }
                .map { (itemType, categoryItems) ->
                    EvidenceRetentionDryRunArtifactCategory(
                        itemType = itemType,
                        itemCount = categoryItems.size,
                        estimatedBytes = categoryItems.sumOf { it.estimatedRawBytes() },
                    )
                }
                .sortedBy { it.itemType }

        return EvidenceRetentionDryRun(
            organizationId = settings.organizationId,
            retentionMode = settings.retentionMode,
            retentionDays = settings.retentionDays,
            automaticCleanupEnabled = settings.automaticCleanupEnabled,
            immediatePurge = settings.immediatePurge,
            cutoffAt = cutoffAt,
            eligibleBundles = bundleIds.size,
            eligibleItems = items.size,
            rawMetadataBundles = rawMetadataBundles.size,
            estimatedReclaimedBytes = itemBytes + rawMetadataBytes,
            artifactCategories = artifactCategories,
            quarantinedBundles = quarantinedBundleIds.size,
            quarantinedItems = quarantinedItems.size,
            quarantinedEstimatedBytes = quarantinedItems.sumOf { it.estimatedRawBytes() },
            quarantinedBehavior = QUARANTINED_INCLUDED_BEHAVIOR,
        )
    }

    private fun EvidenceItemEntity.rawCleanupCandidate(cutoffAt: Instant): Boolean =
        !createdAt.isAfter(cutoffAt) && (contentText != null || rawPurgedAt == null)

    private fun EvidenceItemEntity.estimatedRawBytes(): Long =
        sizeBytes?.takeIf { it >= 0 }
            ?: contentText?.toByteArray(StandardCharsets.UTF_8)?.size?.toLong()
            ?: 0L

    private fun SourceBundleEntity.hasRawMetadata(): Boolean =
        filePathsJson != EMPTY_FILE_PATHS_JSON || provenanceJson != EMPTY_PROVENANCE_JSON

    private fun SourceBundleEntity.rawMetadataBytes(): Long =
        (if (filePathsJson == EMPTY_FILE_PATHS_JSON) 0L else filePathsJson.toByteArray(StandardCharsets.UTF_8).size.toLong()) +
            (if (provenanceJson == EMPTY_PROVENANCE_JSON) 0L else provenanceJson.toByteArray(StandardCharsets.UTF_8).size.toLong())

    private fun SourceBundleEntity.isQuarantined(): Boolean =
        status == STATUS_BLOCKED_SENSITIVE || status == STATUS_QUARANTINED_SECRET

    private fun safeArtifactCategory(itemType: String): String =
        itemType.takeIf { SAFE_ARTIFACT_CATEGORY.matches(it) } ?: UNKNOWN_ARTIFACT_CATEGORY

    companion object {
        private const val EMPTY_FILE_PATHS_JSON = "[]"
        private const val EMPTY_PROVENANCE_JSON = "{}"
        private const val STATUS_BLOCKED_SENSITIVE = "blocked_sensitive"
        private const val STATUS_QUARANTINED_SECRET = "quarantined_secret"
        private const val UNKNOWN_ARTIFACT_CATEGORY = "unknown"
        private const val QUARANTINED_INCLUDED_BEHAVIOR = "included_without_secret_details"
        private const val QUARANTINED_DISABLED_BEHAVIOR = "not_scanned_while_cleanup_disabled"
        private val SAFE_ARTIFACT_CATEGORY = Regex("[a-z0-9_]{1,64}")
    }
}

data class EvidenceRetentionDryRun(
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
    val artifactCategories: List<EvidenceRetentionDryRunArtifactCategory>,
    val quarantinedBundles: Int,
    val quarantinedItems: Int,
    val quarantinedEstimatedBytes: Long,
    val quarantinedBehavior: String,
) {
    companion object {
        fun empty(
            settings: EvidenceRetentionSettings,
            cutoffAt: Instant? = null,
            quarantinedBehavior: String,
        ): EvidenceRetentionDryRun =
            EvidenceRetentionDryRun(
                organizationId = settings.organizationId,
                retentionMode = settings.retentionMode,
                retentionDays = settings.retentionDays,
                automaticCleanupEnabled = settings.automaticCleanupEnabled,
                immediatePurge = settings.immediatePurge,
                cutoffAt = cutoffAt,
                eligibleBundles = 0,
                eligibleItems = 0,
                rawMetadataBundles = 0,
                estimatedReclaimedBytes = 0,
                artifactCategories = emptyList(),
                quarantinedBundles = 0,
                quarantinedItems = 0,
                quarantinedEstimatedBytes = 0,
                quarantinedBehavior = quarantinedBehavior,
            )
    }
}

data class EvidenceRetentionDryRunArtifactCategory(
    val itemType: String,
    val itemCount: Int,
    val estimatedBytes: Long,
)
