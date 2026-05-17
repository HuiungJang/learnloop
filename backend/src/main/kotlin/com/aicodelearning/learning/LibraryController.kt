package com.aicodelearning.learning

import com.aicodelearning.auth.CurrentUser
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class LibraryController(
    private val patternReadService: PatternReadService,
) {
    @GetMapping("/api/library")
    fun library(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam organizationId: String,
    ): LibraryResponse = LibraryResponse(cards = patternReadService.listPublished(currentUser, organizationId))

    @GetMapping("/api/pattern-cards/{id}")
    fun detail(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
    ): PatternCardDetailResponse = PatternCardDetailResponse(patternCard = patternReadService.detail(currentUser, id))
}

data class LibraryResponse(
    val cards: List<PatternCardResponse>,
)

data class PatternCardDetailResponse(
    val patternCard: PatternCardResponse,
)
