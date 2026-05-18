package com.aicodelearning.runner

import com.aicodelearning.learning.PracticeContract
import org.springframework.stereotype.Component

data class RunnerHarness(
    val id: String,
    val language: String,
    val image: String,
    val command: List<String>,
    val maxTimeoutMs: Long,
)

@Component
class RunnerRegistry {
    private val harnesses =
        listOf(
            RunnerHarness(
                id = "typescript-vitest",
                language = PracticeContract.LANGUAGE_TYPESCRIPT,
                image = "learnloop-runner-typescript:latest",
                command = listOf("npm", "test", "--", "--run"),
                maxTimeoutMs = MAX_TIMEOUT_MS,
            ),
            RunnerHarness(
                id = "kotlin-junit",
                language = PracticeContract.LANGUAGE_KOTLIN,
                image = "learnloop-runner-kotlin:latest",
                command = listOf("./gradlew", "test"),
                maxTimeoutMs = MAX_TIMEOUT_MS,
            ),
            RunnerHarness(
                id = "java-junit",
                language = PracticeContract.LANGUAGE_JAVA,
                image = "learnloop-runner-java:latest",
                command = listOf("./gradlew", "test"),
                maxTimeoutMs = MAX_TIMEOUT_MS,
            ),
        ).associateBy { "${it.language}:${it.id}" }

    fun find(
        language: String,
        harnessId: String,
    ): RunnerHarness? = harnesses["$language:$harnessId"]

    private companion object {
        const val MAX_TIMEOUT_MS = 10_000L
    }
}
