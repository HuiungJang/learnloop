package com.aicodelearning.platform

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/health")
class HealthController {
    @GetMapping
    fun health(): HealthResponse = HealthResponse(status = "ok")
}

data class HealthResponse(
    val status: String,
)
