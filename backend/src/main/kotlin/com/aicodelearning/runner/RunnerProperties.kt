package com.aicodelearning.runner

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

@ConfigurationProperties(prefix = "app.runner")
data class RunnerProperties(
    val enabled: Boolean = true,
    val baseUrl: String = "",
    val token: String = "",
    val image: String = "learnloop-runner:latest",
    val dockerCommand: String = "docker",
    val commandTimeout: Duration = Duration.ofSeconds(2),
    val requireLimits: Boolean = true,
)

@Configuration
@EnableConfigurationProperties(RunnerProperties::class)
class RunnerConfiguration
