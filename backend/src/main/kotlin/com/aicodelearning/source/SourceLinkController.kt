package com.aicodelearning.source

import com.aicodelearning.auth.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

@RestController
class SourceLinkController(
    private val sourceLinkService: SourceLinkService,
) {
    @PostMapping("/api/source-links/suggest")
    fun suggest(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: SourceLinkSuggestRequest,
    ): SourceLinkListResponse =
        SourceLinkListResponse(
            links = listOf(sourceLinkService.suggest(currentUser, request.conversationBundleId, request.codeBundleId).toResponse()),
        )

    @PostMapping("/api/source-links/{id}/confirm")
    fun confirm(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
    ): SourceLinkResponse = sourceLinkService.decide(currentUser, id, "confirmed").toResponse()

    @PostMapping("/api/source-links/{id}/reject")
    fun reject(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
    ): SourceLinkResponse = sourceLinkService.decide(currentUser, id, "rejected").toResponse()
}

data class SourceLinkSuggestRequest(
    @field:NotBlank
    val conversationBundleId: String = "",
    @field:NotBlank
    val codeBundleId: String = "",
)

data class SourceLinkListResponse(
    val links: List<SourceLinkResponse>,
)

data class SourceLinkResponse(
    val id: String,
    val organizationId: String,
    val conversationBundleId: String,
    val codeBundleId: String,
    val status: String,
    val confidence: BigDecimal,
)

fun SourceLinkEntity.toResponse(): SourceLinkResponse =
    SourceLinkResponse(
        id = id,
        organizationId = organizationId,
        conversationBundleId = conversationBundleId,
        codeBundleId = codeBundleId,
        status = status,
        confidence = confidence,
    )
