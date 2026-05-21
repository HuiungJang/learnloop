package com.aicodelearning.provider

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "ai_providers")
class ProviderEntity(
    @Id
    var id: String = "",

    @Column(name = "organization_id", nullable = false)
    var organizationId: String = "",

    @Column(name = "owner_user_id")
    var ownerUserId: String? = null,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: String = "",

    @Column(nullable = false)
    var provider: String = "",

    @Column(nullable = false)
    var model: String = "",

    @Column(name = "base_url")
    var baseUrl: String? = null,

    @Column(nullable = false)
    var scope: String = "",

    @Column(name = "auth_type", nullable = false)
    var authType: String = "",

    @Column(name = "retention_mode", nullable = false)
    var retentionMode: String = "",

    @Column(name = "credential_ref", nullable = false)
    var credentialRef: String = "",

    @Column(name = "credential_fingerprint", nullable = false)
    var credentialFingerprint: String = "",

    @Column(name = "secret_preview")
    var secretPreview: String? = null,

    @Column(name = "credential_algorithm")
    var credentialAlgorithm: String? = null,

    @Column(name = "credential_iv")
    var credentialIv: String? = null,

    @Column(name = "credential_tag")
    var credentialTag: String? = null,

    @Column(name = "credential_ciphertext")
    var credentialCiphertext: String? = null,

    @Column(nullable = false)
    var status: String = "",

    @Column(name = "org_approved", nullable = false)
    var orgApproved: Boolean = false,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,
)
