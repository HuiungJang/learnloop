package com.aicodelearning.provider

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app.provider-generation")
data class ProviderGenerationProperties(
    val credentialEncryptionKey: String = "",
    val allowLoopbackBaseUrl: Boolean = false,
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val requestTimeout: Duration = Duration.ofSeconds(15),
    val maxRequestBytes: Int = 65_536,
    val maxResponseBytes: Int = 65_536,
    val maxOutputTokens: Int = 1_024,
)
