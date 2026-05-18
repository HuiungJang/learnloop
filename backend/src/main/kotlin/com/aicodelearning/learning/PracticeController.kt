package com.aicodelearning.learning

import com.aicodelearning.auth.CurrentUser
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class PracticeController(
    private val practiceService: PracticeService,
) {
    @GetMapping("/api/problems/{id}/practice")
    fun detail(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
    ): PracticeProblemDetailResponse = PracticeProblemDetailResponse(problem = practiceService.detail(currentUser, id))
}

data class PracticeProblemDetailResponse(
    val problem: PracticeProblemResponse,
)
