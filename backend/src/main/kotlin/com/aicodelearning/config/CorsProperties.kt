package com.aicodelearning.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.cors")
data class CorsProperties(
    val allowedOrigins: List<String> = listOf(
        "http://localhost:5173",
        "http://127.0.0.1:5173",
    ),
)
