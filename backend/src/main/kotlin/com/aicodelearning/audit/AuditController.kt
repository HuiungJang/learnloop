package com.aicodelearning.audit

import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.organization.AuthorizationService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class AuditController(
    private val auditLogRepository: AuditLogRepository,
    private val authorizationService: AuthorizationService,
) {
    @GetMapping("/api/audit")
    fun listAudit(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam organizationId: String,
    ): AuditListResponse {
        authorizationService.requireRole(currentUser, organizationId, "admin")

        return AuditListResponse(
            auditLogs =
                auditLogRepository
                    .findTop100ByOrganizationIdOrderByCreatedAtDesc(organizationId)
                    .map {
                        AuditLogResponse(
                            id = it.id,
                            actorUserId = it.actorUserId,
                            organizationId = it.organizationId,
                            eventType = it.eventType,
                            targetType = it.targetType,
                            targetId = it.targetId,
                            requestId = it.requestId,
                            metadataJson = it.metadataJson,
                            previousHash = it.previousHash,
                            eventHash = it.eventHash,
                            createdAt = it.createdAt,
                        )
                    },
        )
    }
}

data class AuditListResponse(
    val auditLogs: List<AuditLogResponse>,
)

data class AuditLogResponse(
    val id: String,
    val actorUserId: String?,
    val organizationId: String,
    val eventType: String,
    val targetType: String,
    val targetId: String,
    val requestId: String,
    val metadataJson: String,
    val previousHash: String?,
    val eventHash: String,
    val createdAt: Instant,
)
