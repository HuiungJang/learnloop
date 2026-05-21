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
    private val credentialCryptoService: CredentialCryptoService,
    private val baseUrlValidator: ProviderBaseUrlValidator,
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
        val providerFamily = ProviderCatalog.normalize(request.provider)
        val credential = request.credential.orEmpty()
        if (providerFamily == ProviderCatalog.LOCAL) {
            throw BadRequestException("local provider is managed by the application")
        }
        if (credential.length < 8) {
            throw BadRequestException("credential must be at least 8 characters")
        }
        if (!credentialCryptoService.isConfigured()) {
            throw BadRequestException("credential encryption key is not configured")
        }
        val normalizedBaseUrl =
            baseUrlValidator.normalizeAndValidate(
                request.baseUrl?.takeIf { it.isNotBlank() }
                    ?: ProviderCatalog.defaultBaseUrl(providerFamily)
                    ?: throw BadRequestException("baseUrl is required"),
            )

        if (scope == "organization") {
            authorizationService.requireRole(currentUser, request.organizationId, "admin")
        } else {
            authorizationService.requireOrganizationMember(currentUser, request.organizationId, "learner")
        }

        val id = prefixedId("provider")
        val digest = sha256Hex(credential)
        val sealedCredential =
            credentialCryptoService.seal(
                providerId = id,
                organizationId = request.organizationId,
                provider = providerFamily,
                model = request.model,
                credential = credential,
            )
        val entity =
            providerRepository.save(
                ProviderEntity(
                    id = id,
                    organizationId = request.organizationId,
                    ownerUserId = if (scope == "personal") currentUser.id else null,
                    createdByUserId = currentUser.id,
                    provider = providerFamily,
                    model = request.model,
                    baseUrl = normalizedBaseUrl,
                    scope = scope,
                    authType = request.authType,
                    retentionMode = request.retentionMode,
                    credentialRef = "encrypted://${id}",
                    credentialFingerprint = digest.take(16),
                    secretPreview = "***${credential.takeLast(4)}",
                    credentialAlgorithm = sealedCredential.algorithm,
                    credentialIv = sealedCredential.iv,
                    credentialTag = sealedCredential.tag,
                    credentialCiphertext = sealedCredential.ciphertext,
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
                    "authType" to entity.authType,
                    "retentionMode" to entity.retentionMode,
                    "status" to entity.status,
                ),
        )

        return entity
    }

    private companion object {
        val allowedScopes = setOf("organization", "personal")
    }
}
