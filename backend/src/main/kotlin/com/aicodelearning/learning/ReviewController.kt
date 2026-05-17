package com.aicodelearning.learning

import com.aicodelearning.auth.CurrentUser
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class ReviewController(
    private val reviewService: ReviewService,
) {
    @GetMapping("/api/review/tasks")
    fun queue(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam organizationId: String,
    ): ReviewQueueResponse = ReviewQueueResponse(reviewTasks = reviewService.queue(currentUser, organizationId))

    @PostMapping("/api/review/tasks/{id}/decision")
    fun decide(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
        @RequestBody request: ReviewDecisionRequest,
    ): ReviewDecisionResponse = reviewService.decide(currentUser, id, request)
}

data class ReviewQueueResponse(
    val reviewTasks: List<ReviewTaskResponse>,
)
