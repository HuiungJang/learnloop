package com.aicodelearning.evidence

import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.auth.sha256Hex
import com.aicodelearning.organization.AuthorizationService
import com.aicodelearning.platform.BadRequestException
import com.aicodelearning.platform.LocalOwnerAccess
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class EvidenceRetentionSettingsService(
    private val repository: EvidenceRetentionSettingsRepository,
    private val authorizationService: AuthorizationService,
    private val localOwnerAccess: LocalOwnerAccess,
) {
    @Transactional(readOnly = true)
    fun read(
        currentUser: CurrentUser,
        organizationId: String,
    ): EvidenceRetentionSettings {
        val normalizedOrganizationId = requireOrganizationId(organizationId)
        requireOwnerAccess(currentUser, normalizedOrganizationId)
        return repository
            .findByOrganizationIdAndOwnerUserId(normalizedOrganizationId, currentUser.id)
            ?.toSettings()
            ?: defaultSettings(normalizedOrganizationId, currentUser.id)
    }

    @Transactional
    fun update(
        currentUser: CurrentUser,
        request: UpdateEvidenceRetentionSettingsRequest,
    ): EvidenceRetentionSettings {
        val organizationId = requireOrganizationId(request.organizationId)
        requireOwnerAccess(currentUser, organizationId)
        val normalized = normalize(request)
        val now = Instant.now()
        val entity = findOrCreate(organizationId, currentUser.id, now)
        entity.retentionMode = normalized.mode
        entity.retentionDays = normalized.retentionDays
        entity.updatedAt = now
        return repository.save(entity).toSettings()
    }

    @Transactional
    fun recordCleanupProgress(
        currentUser: CurrentUser,
        organizationId: String,
        purgedItems: Int,
        reclaimedBytes: Long,
        remainingItems: Int,
        finishedAt: Instant,
    ): EvidenceRetentionSettings {
        val normalizedOrganizationId = requireOrganizationId(organizationId)
        requireOwnerAccess(currentUser, normalizedOrganizationId)
        val entity = findOrCreate(normalizedOrganizationId, currentUser.id, finishedAt)
        entity.lastCleanupAt = finishedAt
        entity.lastCleanupPurgedItems = purgedItems.coerceAtLeast(0)
        entity.lastCleanupReclaimedBytes = reclaimedBytes.coerceAtLeast(0)
        entity.lastCleanupRemainingItems = remainingItems.coerceAtLeast(0)
        entity.updatedAt = finishedAt
        return repository.save(entity).toSettings()
    }

    private fun requireOrganizationId(organizationId: String): String =
        organizationId.trim().ifBlank { throw BadRequestException("organizationId is required") }

    private fun requireOwnerAccess(
        currentUser: CurrentUser,
        organizationId: String,
    ) {
        localOwnerAccess.requireLocalOwner(currentUser)
        authorizationService.requireOrganizationMember(currentUser, organizationId, "admin")
    }

    private fun normalize(request: UpdateEvidenceRetentionSettingsRequest): NormalizedRetentionSettings {
        val mode = request.retentionMode.trim().lowercase()
        return when (mode) {
            RETENTION_MODE_DEFAULT -> {
                val days = request.retentionDays ?: DEFAULT_RETENTION_DAYS
                if (days !in 1..MAX_RETENTION_DAYS) throw BadRequestException("retentionDays must be between 1 and $MAX_RETENTION_DAYS")
                NormalizedRetentionSettings(mode, days)
            }
            RETENTION_MODE_DISABLED -> NormalizedRetentionSettings(mode, null)
            RETENTION_MODE_IMMEDIATE -> NormalizedRetentionSettings(mode, 0)
            else -> throw BadRequestException("retentionMode is not supported")
        }
    }

    private fun EvidenceRetentionSettingsEntity.toSettings(): EvidenceRetentionSettings =
        EvidenceRetentionSettings(
            organizationId = organizationId,
            ownerUserId = ownerUserId,
            retentionMode = retentionMode,
            retentionDays = retentionDays,
            automaticCleanupEnabled = retentionMode != RETENTION_MODE_DISABLED,
            immediatePurge = retentionMode == RETENTION_MODE_IMMEDIATE,
            updatedAt = updatedAt,
            lastCleanupAt = lastCleanupAt,
            lastCleanupPurgedItems = lastCleanupPurgedItems,
            lastCleanupReclaimedBytes = lastCleanupReclaimedBytes,
            lastCleanupRemainingItems = lastCleanupRemainingItems,
        )

    private fun defaultSettings(
        organizationId: String,
        ownerUserId: String,
    ): EvidenceRetentionSettings =
        EvidenceRetentionSettings(
            organizationId = organizationId,
            ownerUserId = ownerUserId,
            retentionMode = RETENTION_MODE_DEFAULT,
            retentionDays = DEFAULT_RETENTION_DAYS,
            automaticCleanupEnabled = true,
            immediatePurge = false,
            updatedAt = null,
            lastCleanupAt = null,
            lastCleanupPurgedItems = 0,
            lastCleanupReclaimedBytes = 0,
            lastCleanupRemainingItems = 0,
        )

    private data class NormalizedRetentionSettings(
        val mode: String,
        val retentionDays: Int?,
    )

    private fun findOrCreate(
        organizationId: String,
        ownerUserId: String,
        now: Instant,
    ): EvidenceRetentionSettingsEntity =
        repository.findByOrganizationIdAndOwnerUserId(organizationId, ownerUserId)
            ?: EvidenceRetentionSettingsEntity(
                id = settingsId(organizationId, ownerUserId),
                organizationId = organizationId,
                ownerUserId = ownerUserId,
                retentionMode = RETENTION_MODE_DEFAULT,
                retentionDays = DEFAULT_RETENTION_DAYS,
                createdAt = now,
                updatedAt = now,
            )

    companion object {
        const val RETENTION_MODE_DEFAULT = "default"
        const val RETENTION_MODE_DISABLED = "disabled"
        const val RETENTION_MODE_IMMEDIATE = "immediate"
        const val DEFAULT_RETENTION_DAYS = 30
        const val MAX_RETENTION_DAYS = 3650

        fun settingsId(
            organizationId: String,
            ownerUserId: String,
        ): String = "retention_${sha256Hex("$organizationId:$ownerUserId").take(24)}"
    }
}

data class UpdateEvidenceRetentionSettingsRequest(
    val organizationId: String = "",
    val retentionMode: String = EvidenceRetentionSettingsService.RETENTION_MODE_DEFAULT,
    val retentionDays: Int? = null,
)

data class EvidenceRetentionSettings(
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
