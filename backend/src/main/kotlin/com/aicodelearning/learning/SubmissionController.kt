package com.aicodelearning.learning

import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.organization.AuthorizationService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class SubmissionController(
    private val submissionService: SubmissionService,
    private val proficiencyScoreRepository: ProficiencyScoreRepository,
    private val patternReadService: PatternReadService,
    private val authorizationService: AuthorizationService,
) {
    @PostMapping("/api/problems/{id}/submissions")
    @ResponseStatus(HttpStatus.CREATED)
    fun submit(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
        @RequestBody request: SubmissionRequest,
    ): SubmissionCreateResponse {
        val result = submissionService.submit(currentUser, id, request)
        return SubmissionCreateResponse(submission = result.submission.toResponse(), patternCard = result.patternCard)
    }

    @GetMapping("/api/progress")
    fun progress(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam organizationId: String,
    ): ProgressResponse {
        authorizationService.requireOrganizationMember(currentUser, organizationId, "learner")
        return ProgressResponse(
            proficiency =
                proficiencyScoreRepository
                    .findByUserIdAndOrganizationId(currentUser.id, organizationId)
                    .map { ProficiencyResponse(tagName = it.tagName, score = it.score) },
        )
    }

    @GetMapping("/api/recommendations")
    fun recommendations(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam organizationId: String,
    ): LibraryResponse = LibraryResponse(cards = patternReadService.listPublished(currentUser, organizationId, limit = 5))
}

data class SubmissionCreateResponse(
    val submission: SubmissionResponse,
    val patternCard: PatternCardResponse,
)

data class ProgressResponse(
    val proficiency: List<ProficiencyResponse>,
)

data class ProficiencyResponse(
    val tagName: String,
    val score: Int,
)
