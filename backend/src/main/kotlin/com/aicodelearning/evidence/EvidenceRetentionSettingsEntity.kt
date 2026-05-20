package com.aicodelearning.evidence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "local_evidence_retention_settings")
class EvidenceRetentionSettingsEntity(
    @Id
    var id: String = "",

    @Column(name = "organization_id", nullable = false)
    var organizationId: String = "",

    @Column(name = "owner_user_id", nullable = false)
    var ownerUserId: String = "",

    @Column(name = "retention_mode", nullable = false)
    var retentionMode: String = "",

    @Column(name = "retention_days")
    var retentionDays: Int? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "last_cleanup_at")
    var lastCleanupAt: Instant? = null,

    @Column(name = "last_cleanup_purged_items", nullable = false)
    var lastCleanupPurgedItems: Int = 0,

    @Column(name = "last_cleanup_reclaimed_bytes", nullable = false)
    var lastCleanupReclaimedBytes: Long = 0,

    @Column(name = "last_cleanup_remaining_items", nullable = false)
    var lastCleanupRemainingItems: Int = 0,
)
