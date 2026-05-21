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
    val dockerCommand: String = "docker",
    val commandTimeout: Duration = Duration.ofSeconds(2),
    val installCommandTimeout: Duration = Duration.ofMinutes(10),
    val requireLimits: Boolean = true,
    val workspaceContainerRoot: String = "",
    val workspaceHostRoot: String = "",
)

@Configuration
@EnableConfigurationProperties(RunnerProperties::class)
class RunnerConfiguration
