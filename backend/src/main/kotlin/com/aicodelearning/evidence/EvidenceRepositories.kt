package com.aicodelearning.evidence

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SourceBundleRepository : JpaRepository<SourceBundleEntity, String>, JpaSpecificationExecutor<SourceBundleEntity> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select bundle from SourceBundleEntity bundle where bundle.id = :id")
    fun findForUpdateById(
        @Param("id") id: String,
    ): SourceBundleEntity?

    fun findFirstByOrganizationIdAndSourceKindAndContentHashAndDeletedAtIsNullOrderByCreatedAtDesc(
        organizationId: String,
        sourceKind: String,
        contentHash: String,
    ): SourceBundleEntity?

    fun findByOrganizationIdAndDeletedAtIsNull(organizationId: String): List<SourceBundleEntity>

    fun findByOrganizationIdAndDeletedAtIsNullOrderByCreatedAtDesc(
        organizationId: String,
        pageable: Pageable,
    ): Page<SourceBundleEntity>

    fun findByOrganizationId(organizationId: String): List<SourceBundleEntity>

    fun findByOrganizationIdAndRepositoryUrlAndDeletedAtIsNull(
        organizationId: String,
        repositoryUrl: String,
    ): List<SourceBundleEntity>

    fun findByOrganizationIdAndRepositoryUrl(
        organizationId: String,
        repositoryUrl: String,
    ): List<SourceBundleEntity>

    fun findFirstByOrganizationIdAndSourceKindAndDedupeKeyAndDeletedAtIsNullOrderByCreatedAtDesc(
        organizationId: String,
        sourceKind: String,
        dedupeKey: String,
    ): SourceBundleEntity?
}

fun SourceBundleRepository.findExistingForUpdateSortedById(ids: Collection<String>): List<SourceBundleEntity> =
    ids
        .distinct()
        .sorted()
        .mapNotNull { findForUpdateById(it) }

interface EvidenceItemRepository : JpaRepository<EvidenceItemEntity, String> {
    fun findByBundleId(bundleId: String): List<EvidenceItemEntity>

    fun findByBundleIdIn(bundleIds: Collection<String>): List<EvidenceItemEntity>
}

interface SourceBundleAttributionEventRepository : JpaRepository<SourceBundleAttributionEventEntity, String>

interface LocalRepositoryConsentRepository : JpaRepository<LocalRepositoryConsentEntity, String> {
    fun findByOrganizationIdAndRepoIdentityHash(
        organizationId: String,
        repoIdentityHash: String,
    ): LocalRepositoryConsentEntity?

    fun findByOrganizationIdOrderByUpdatedAtDesc(organizationId: String): List<LocalRepositoryConsentEntity>
}

interface EvidenceRetentionSettingsRepository : JpaRepository<EvidenceRetentionSettingsEntity, String> {
    fun findByOrganizationIdAndOwnerUserId(
        organizationId: String,
        ownerUserId: String,
    ): EvidenceRetentionSettingsEntity?
}
