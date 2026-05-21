package com.aicodelearning.evidence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "local_repository_consents")
class LocalRepositoryConsentEntity(
    @Id
    var id: String = "",

    @Column(name = "organization_id", nullable = false)
    var organizationId: String = "",

    @Column(name = "repo_identity_hash", nullable = false)
    var repoIdentityHash: String = "",

    @Column(name = "display_label", nullable = false)
    var displayLabel: String = "",

    @Column(nullable = false)
    var status: String = "",

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
