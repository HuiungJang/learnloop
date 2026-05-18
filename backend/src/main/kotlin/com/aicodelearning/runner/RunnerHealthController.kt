package com.aicodelearning.runner

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class RunnerHealthController(
    private val runnerHealthService: RunnerHealthService,
) {
    @GetMapping("/api/runner/health")
    fun health(): RunnerHealthResponse = runnerHealthService.health()
}
