package com.aicodelearning.provider

import com.aicodelearning.auth.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class ProviderController(
    private val providerService: ProviderService,
) {
    @GetMapping("/api/providers")
    fun listProviders(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam organizationId: String,
    ): ProviderListResponse =
        ProviderListResponse(
            providers = providerService.list(currentUser, organizationId).map { it.toResponse() },
        )

    @PostMapping("/api/providers")
    @ResponseStatus(HttpStatus.CREATED)
    fun createProvider(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: ProviderCreateRequest,
    ): ProviderCreateResponse = ProviderCreateResponse(provider = providerService.create(currentUser, request).toResponse())
}

data class ProviderCreateRequest(
    @field:NotBlank
    val organizationId: String = "",
    @field:NotBlank
    val provider: String = "",
    @field:NotBlank
    val model: String = "",
    val baseUrl: String? = null,
    val scope: String = "personal",
    val credential: String? = null,
    val retentionMode: String = "standard",
    val authType: String = "byok",
)

data class ProviderCreateResponse(
    val provider: ProviderResponse,
)

data class ProviderListResponse(
    val providers: List<ProviderResponse>,
)

data class ProviderResponse(
    val id: String,
    val organizationId: String,
    val ownerUserId: String?,
    val provider: String,
    val model: String,
    val baseUrl: String?,
    val scope: String,
    val authType: String,
    val retentionMode: String,
    val credentialRef: String,
    val credentialFingerprint: String,
    val secretPreview: String?,
    val status: String,
    val orgApproved: Boolean,
    val createdAt: Instant,
)

private fun ProviderEntity.toResponse(): ProviderResponse =
    ProviderResponse(
        id = id,
        organizationId = organizationId,
        ownerUserId = ownerUserId,
        provider = provider,
        model = model,
        baseUrl = baseUrl,
        scope = scope,
        authType = authType,
        retentionMode = retentionMode,
        credentialRef = credentialRef,
        credentialFingerprint = credentialFingerprint,
        secretPreview = secretPreview,
        status = status,
        orgApproved = orgApproved,
        createdAt = createdAt,
    )
