package com.aicodelearning.evidence

import org.springframework.data.jpa.repository.JpaRepository

interface SourceBundleRepository : JpaRepository<SourceBundleEntity, String> {
    fun findFirstByOrganizationIdAndSourceKindAndContentHashOrderByCreatedAtDesc(
        organizationId: String,
        sourceKind: String,
        contentHash: String,
    ): SourceBundleEntity?
}

interface EvidenceItemRepository : JpaRepository<EvidenceItemEntity, String> {
    fun findByBundleId(bundleId: String): List<EvidenceItemEntity>
}
