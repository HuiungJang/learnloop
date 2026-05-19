package com.aicodelearning.evidence

import org.springframework.data.jpa.repository.JpaRepository

interface SourceBundleRepository : JpaRepository<SourceBundleEntity, String> {
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

interface EvidenceItemRepository : JpaRepository<EvidenceItemEntity, String> {
    fun findByBundleId(bundleId: String): List<EvidenceItemEntity>

    fun findByBundleIdIn(bundleIds: Collection<String>): List<EvidenceItemEntity>
}

interface SourceBundleAttributionEventRepository : JpaRepository<SourceBundleAttributionEventEntity, String>
