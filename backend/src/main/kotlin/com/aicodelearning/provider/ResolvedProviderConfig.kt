package com.aicodelearning.provider

import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.platform.BadRequestException
import com.aicodelearning.platform.ForbiddenException
import com.aicodelearning.platform.NotFoundException
import org.springframework.stereotype.Service
import java.net.URI

@Service
class ProviderConfigResolver(
    private val providerRepository: ProviderRepository,
    private val credentialCryptoService: CredentialCryptoService,
    private val baseUrlValidator: ProviderBaseUrlValidator,
    private val properties: ProviderGenerationProperties,
) {
    fun resolve(
        currentUser: CurrentUser,
        organizationId: String,
        providerConfigId: String,
        visibility: String,
    ): ResolvedProviderConfig {
        val provider = providerRepository.findById(providerConfigId).orElseThrow { NotFoundException("Provider not found") }
        if (provider.organizationId != organizationId || provider.status != "active") {
            throw BadRequestException("Provider is not active for this organization")
        }
        if (provider.scope == "personal" && provider.ownerUserId != currentUser.id) {
            throw ForbiddenException("Personal provider can only be used by its owner")
        }
        if (visibility == "organization" && !provider.orgApproved) {
            throw ForbiddenException("Organization publication requires an approved provider")
        }

        val providerFamily =
            try {
                ProviderCatalog.normalize(provider.provider)
            } catch (_: BadRequestException) {
                throw ProviderGenerationException(ProviderFailureCode.PROVIDER_CONFIGURATION_INVALID)
            }
        if (providerFamily == ProviderCatalog.LOCAL) {
            if (!ProviderCatalog.isCanonicalLocalMock(provider)) {
                throw ProviderGenerationException(ProviderFailureCode.PROVIDER_CONFIGURATION_INVALID)
            }
            return ResolvedProviderConfig(
                providerConfigId = provider.id,
                provider = ProviderCatalog.LOCAL,
                model = provider.model,
                baseUri = null,
                credential = null,
                maxOutputTokens = 0,
            )
        }

        val sealedCredential =
            SealedCredential(
                algorithm = provider.credentialAlgorithm ?: throw ProviderGenerationException(ProviderFailureCode.PROVIDER_CONFIGURATION_INVALID),
                iv = provider.credentialIv ?: throw ProviderGenerationException(ProviderFailureCode.PROVIDER_CONFIGURATION_INVALID),
                tag = provider.credentialTag ?: throw ProviderGenerationException(ProviderFailureCode.PROVIDER_CONFIGURATION_INVALID),
                ciphertext = provider.credentialCiphertext ?: throw ProviderGenerationException(ProviderFailureCode.PROVIDER_CONFIGURATION_INVALID),
            )
        val credential =
            credentialCryptoService.open(
                providerId = provider.id,
                organizationId = provider.organizationId,
                provider = providerFamily,
                model = provider.model,
                credential = sealedCredential,
            )
        val baseUrl = provider.baseUrl ?: throw ProviderGenerationException(ProviderFailureCode.PROVIDER_CONFIGURATION_INVALID)
        val normalizedBaseUrl =
            try {
                baseUrlValidator.normalizeAndValidate(baseUrl)
            } catch (_: BadRequestException) {
                throw ProviderGenerationException(ProviderFailureCode.PROVIDER_CONFIGURATION_INVALID)
            }

        return ResolvedProviderConfig(
            providerConfigId = provider.id,
            provider = providerFamily,
            model = provider.model,
            baseUri = URI(normalizedBaseUrl),
            credential = credential,
            maxOutputTokens = properties.maxOutputTokens,
        )
    }
}

data class ResolvedProviderConfig(
    val providerConfigId: String,
    val provider: String,
    val model: String,
    val baseUri: URI?,
    val credential: String?,
    val maxOutputTokens: Int,
)
