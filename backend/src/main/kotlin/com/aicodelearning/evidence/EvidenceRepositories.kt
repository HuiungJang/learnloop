package com.aicodelearning.evidence

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SourceBundleRepository : JpaRepository<SourceBundleEntity, String> {
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
