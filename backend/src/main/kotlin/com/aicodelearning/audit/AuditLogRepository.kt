package com.aicodelearning.audit

import org.springframework.data.jpa.repository.JpaRepository

interface AuditLogRepository : JpaRepository<AuditLogEntity, String> {
    fun findTopByOrganizationIdOrderByCreatedAtDescIdDesc(organizationId: String): AuditLogEntity?

    fun findTop100ByOrganizationIdOrderByCreatedAtDesc(
        organizationId: String,
    ): List<AuditLogEntity>
}
