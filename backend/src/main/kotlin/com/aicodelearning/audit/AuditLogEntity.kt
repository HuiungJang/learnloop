package com.aicodelearning.audit

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "audit_logs")
class AuditLogEntity(
    @Id
    var id: String = "",

    @Column(name = "actor_user_id")
    var actorUserId: String? = null,

    @Column(name = "organization_id", nullable = false)
    var organizationId: String = "",

    @Column(name = "event_type", nullable = false)
    var eventType: String = "",

    @Column(name = "target_type", nullable = false)
    var targetType: String = "",

    @Column(name = "target_id", nullable = false)
    var targetId: String = "",

    @Column(name = "request_id", nullable = false)
    var requestId: String = "",

    @Column(name = "metadata_json", nullable = false)
    var metadataJson: String = "{}",

    @Column(name = "previous_hash")
    var previousHash: String? = null,

    @Column(name = "event_hash", nullable = false)
    var eventHash: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
