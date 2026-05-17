package com.aicodelearning.source

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "source_links")
class SourceLinkEntity(
    @Id
    var id: String = "",

    @Column(name = "organization_id", nullable = false)
    var organizationId: String = "",

    @Column(name = "conversation_bundle_id", nullable = false)
    var conversationBundleId: String = "",

    @Column(name = "code_bundle_id", nullable = false)
    var codeBundleId: String = "",

    @Column(nullable = false)
    var status: String = "",

    @Column(nullable = false)
    var confidence: BigDecimal = BigDecimal.ZERO,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: String = "",

    @Column(name = "decided_by_user_id")
    var decidedByUserId: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "decided_at")
    var decidedAt: Instant? = null,
)
