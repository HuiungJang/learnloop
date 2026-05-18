package com.aicodelearning.learning

import com.aicodelearning.auth.CurrentUser
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
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

    @GetMapping("/api/problems/{id}/attempts/me")
    fun currentUserAttempts(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
    ): PracticeAttemptListResponse = PracticeAttemptListResponse(attempts = practiceService.currentUserAttempts(currentUser, id))

    @PostMapping("/api/problems/{id}/attempts/local-sync")
    fun syncLocalAttempt(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
        @RequestBody request: PracticeAttemptSyncRequest,
    ): PracticeAttemptSyncResponse = PracticeAttemptSyncResponse(attempt = practiceService.syncLocalAttempt(currentUser, id, request))

    @PostMapping("/api/problems/{id}/runs")
    fun run(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
        @RequestBody request: PracticeRunRequest,
    ): PracticeRunCreateResponse = PracticeRunCreateResponse(run = practiceService.run(currentUser, id, request))
}

data class PracticeProblemDetailResponse(
    val problem: PracticeProblemResponse,
)

data class PracticeAttemptListResponse(
    val attempts: List<PracticeAttemptResponse>,
)

data class PracticeAttemptSyncResponse(
    val attempt: PracticeAttemptResponse,
)

data class PracticeRunRequest(
    val clientAttemptId: String? = null,
    val assetRevision: String,
    val language: String,
    val files: List<PracticeAttemptFileRequest>,
    val timeoutMs: Long? = null,
)

data class PracticeRunCreateResponse(
    val run: PracticeRunResultResponse,
)
