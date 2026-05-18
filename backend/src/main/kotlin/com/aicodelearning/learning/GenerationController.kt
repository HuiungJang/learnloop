package com.aicodelearning.learning

import com.aicodelearning.auth.CurrentUser
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class GenerationController(
    private val generationService: GenerationService,
) {
    @PostMapping("/api/generation/run", "/api/generation/runs")
    @ResponseStatus(HttpStatus.CREATED)
    fun run(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody request: GenerationRequest,
    ): GenerationResponse = generationService.run(currentUser, request)

    @GetMapping("/api/conversion-traces")
    fun traces(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam organizationId: String,
    ): ConversionTraceListResponse = generationService.traces(currentUser, organizationId)
}
