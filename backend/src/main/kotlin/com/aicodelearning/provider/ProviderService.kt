package com.aicodelearning.provider

import com.aicodelearning.audit.AuditService
import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.auth.sha256Hex
import com.aicodelearning.organization.AuthorizationService
import com.aicodelearning.platform.BadRequestException
import com.aicodelearning.platform.prefixedId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ProviderService(
    private val providerRepository: ProviderRepository,
    private val authorizationService: AuthorizationService,
    private val auditService: AuditService,
) {
    @Transactional(readOnly = true)
    fun list(
        currentUser: CurrentUser,
        organizationId: String,
    ): List<ProviderEntity> {
        authorizationService.requireOrganizationMember(currentUser, organizationId, "learner")
        val organizationProviders = providerRepository.findByOrganizationIdAndScopeIn(organizationId, listOf("organization"))
        val personalProviders = providerRepository.findByOrganizationIdAndOwnerUserId(organizationId, currentUser.id)
        return (organizationProviders + personalProviders).distinctBy { it.id }
    }

    @Transactional
    fun create(
        currentUser: CurrentUser,
        request: ProviderCreateRequest,
    ): ProviderEntity {
        val scope = request.scope.ifBlank { "personal" }
        if (scope !in allowedScopes) {
            throw BadRequestException("scope must be organization or personal")
        }
        if (request.credential.length < 8) {
            throw BadRequestException("credential must be at least 8 characters")
        }

        if (scope == "organization") {
            authorizationService.requireRole(currentUser, request.organizationId, "admin")
        } else {
            authorizationService.requireOrganizationMember(currentUser, request.organizationId, "learner")
        }

        val digest = sha256Hex(request.credential)
        val entity =
            providerRepository.save(
                ProviderEntity(
                    id = prefixedId("provider"),
                    organizationId = request.organizationId,
                    ownerUserId = if (scope == "personal") currentUser.id else null,
                    createdByUserId = currentUser.id,
                    provider = request.provider,
                    model = request.model,
                    scope = scope,
                    authType = request.authType,
                    retentionMode = request.retentionMode,
                    credentialRef = "vault://${digest.take(24)}",
                    credentialFingerprint = digest.take(16),
                    secretPreview = if (request.credential.length >= 4) "***${request.credential.takeLast(4)}" else "***",
                    status = "active",
                    orgApproved = scope == "organization",
                    createdAt = Instant.now(),
                ),
            )

        auditService.append(
            actor = currentUser,
            organizationId = entity.organizationId,
            eventType = "provider.created",
            targetType = "provider",
            targetId = entity.id,
            metadata =
                mapOf(
                    "provider" to entity.provider,
                    "model" to entity.model,
                    "scope" to entity.scope,
                    "credential" to request.credential,
                ),
        )

        return entity
    }

    private companion object {
        val allowedScopes = setOf("organization", "personal")
    }
}
