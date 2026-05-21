package com.aicodelearning.evidence

import com.aicodelearning.audit.AuditService
import com.aicodelearning.auth.CurrentUser
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class EvidenceRetentionCleanupService(
    private val retentionSettingsService: EvidenceRetentionSettingsService,
    private val sourceBundleRepository: SourceBundleRepository,
    private val evidenceItemRepository: EvidenceItemRepository,
    private val auditService: AuditService,
) {
    @Transactional
    fun run(
        currentUser: CurrentUser,
        request: EvidenceRetentionCleanupRequest,
    ): EvidenceRetentionCleanupRun {
        val settings = retentionSettingsService.read(currentUser, request.organizationId)
        val batchSize = request.batchSize.coerceIn(1, MAX_BATCH_SIZE)
        if (!settings.automaticCleanupEnabled) {
            return EvidenceRetentionCleanupRun.empty(settings, batchSize, skippedReason = "cleanup_disabled")
        }

        val now = Instant.now()
        val cutoffAt =
            if (settings.immediatePurge) {
                now
            } else {
                now.minus((settings.retentionDays ?: EvidenceRetentionSettingsService.DEFAULT_RETENTION_DAYS).toLong(), ChronoUnit.DAYS)
            }
        val eligibleItemCount = evidenceItemRepository.countRetentionCleanupCandidates(settings.organizationId, cutoffAt)
        val activeSkippedItems = evidenceItemRepository.countActiveIngestionSkippedCandidates(settings.organizationId, cutoffAt).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val selectedItems =
            evidenceItemRepository.findRetentionCleanupCandidates(
                organizationId = settings.organizationId,
                cutoffAt = cutoffAt,
                pageable = PageRequest.of(0, batchSize),
            )
        if (selectedItems.isEmpty()) {
            val progress =
                retentionSettingsService.recordCleanupProgress(
                    currentUser = currentUser,
                    organizationId = settings.organizationId,
                    purgedItems = 0,
                    reclaimedBytes = 0,
                    remainingItems = 0,
                    finishedAt = Instant.now(),
                )
            return EvidenceRetentionCleanupRun.empty(
                settings = progress,
                batchSize = batchSize,
                cutoffAt = cutoffAt,
                skippedReason = "no_candidates",
                activeIngestionSkippedItems = activeSkippedItems,
            )
        }

        val reclaimedBytes = selectedItems.sumOf { it.estimatedRawBytes() }
        selectedItems.forEach {
            it.contentText = null
            if (it.rawPurgedAt == null) {
                it.rawPurgedAt = now
            }
            if (it.rawPurgeReason == null) {
                it.rawPurgeReason = RETENTION_PURGE_REASON
            }
        }
        val purgedBundleIds = selectedItems.map { it.bundleId }.toSet()
        sourceBundleRepository.findExistingForUpdateSortedById(purgedBundleIds).forEach {
            auditService.append(currentUser, it.organizationId, "evidence.retention_raw_purged", "source_bundle", it.id, mapOf("status" to "raw_purged"))
        }
        val remainingEligibleItems = (eligibleItemCount - selectedItems.size).coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val progress =
            retentionSettingsService.recordCleanupProgress(
                currentUser = currentUser,
                organizationId = settings.organizationId,
                purgedItems = selectedItems.size,
                reclaimedBytes = reclaimedBytes,
                remainingItems = remainingEligibleItems,
                finishedAt = Instant.now(),
            )

        return EvidenceRetentionCleanupRun(
            organizationId = settings.organizationId,
            retentionMode = settings.retentionMode,
            retentionDays = settings.retentionDays,
            cutoffAt = cutoffAt,
            lastCleanupAt = progress.lastCleanupAt,
            lastCleanupPurgedItems = progress.lastCleanupPurgedItems,
            lastCleanupReclaimedBytes = progress.lastCleanupReclaimedBytes,
            lastCleanupRemainingItems = progress.lastCleanupRemainingItems,
            batchSize = batchSize,
            purgedBundles = purgedBundleIds.size,
            purgedItems = selectedItems.size,
            reclaimedBytes = reclaimedBytes,
            remainingEligibleItems = remainingEligibleItems,
            activeIngestionSkippedItems = activeSkippedItems,
            filesystemArtifactsDeleted = 0,
            skippedReason = null,
        )
    }

    private fun EvidenceItemEntity.estimatedRawBytes(): Long =
        sizeBytes?.takeIf { it >= 0 }
            ?: contentText?.toByteArray(StandardCharsets.UTF_8)?.size?.toLong()
            ?: 0L

    companion object {
        const val RETENTION_PURGE_REASON = "retention_cleanup"
        private const val MAX_BATCH_SIZE = 500
    }
}

data class EvidenceRetentionCleanupRequest(
    val organizationId: String = "",
    val batchSize: Int = 100,
)

data class EvidenceRetentionCleanupRun(
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
) {
    companion object {
        fun empty(
            settings: EvidenceRetentionSettings,
            batchSize: Int,
            cutoffAt: Instant? = null,
            skippedReason: String,
            activeIngestionSkippedItems: Int = 0,
        ): EvidenceRetentionCleanupRun =
            EvidenceRetentionCleanupRun(
                organizationId = settings.organizationId,
                retentionMode = settings.retentionMode,
                retentionDays = settings.retentionDays,
                cutoffAt = cutoffAt,
                lastCleanupAt = settings.lastCleanupAt,
                lastCleanupPurgedItems = settings.lastCleanupPurgedItems,
                lastCleanupReclaimedBytes = settings.lastCleanupReclaimedBytes,
                lastCleanupRemainingItems = settings.lastCleanupRemainingItems,
                batchSize = batchSize,
                purgedBundles = 0,
                purgedItems = 0,
                reclaimedBytes = 0,
                remainingEligibleItems = 0,
                activeIngestionSkippedItems = activeIngestionSkippedItems,
                filesystemArtifactsDeleted = 0,
                skippedReason = skippedReason,
            )
    }
}
