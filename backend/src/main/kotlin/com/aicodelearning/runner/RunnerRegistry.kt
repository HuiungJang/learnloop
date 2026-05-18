package com.aicodelearning.runner

import com.aicodelearning.learning.PracticeContract
import org.springframework.stereotype.Component

data class RunnerHarness(
    val id: String,
    val language: String,
    val image: String,
    val command: List<String>,
    val maxTimeoutMs: Long,
    val files: List<RunnerHarnessFile>,
)

data class RunnerHarnessFile(
    val path: String,
    val content: String,
)

@Component
class RunnerRegistry {
    private val harnesses =
        listOf(
            RunnerHarness(
                id = "typescript-node-test",
                language = PracticeContract.LANGUAGE_TYPESCRIPT,
                image = "learnloop-runner-typescript:latest",
                command = listOf("node", "/opt/learnloop-runner/run-tests.mjs"),
                maxTimeoutMs = MAX_TIMEOUT_MS,
                files = harnessFiles("typescript-node-test"),
            ),
            RunnerHarness(
                id = "kotlin-junit",
                language = PracticeContract.LANGUAGE_KOTLIN,
                image = "learnloop-runner-kotlin:latest",
                command = listOf("./gradlew", "test"),
                maxTimeoutMs = MAX_TIMEOUT_MS,
                files = harnessFiles("kotlin-junit"),
            ),
            RunnerHarness(
                id = "java-junit",
                language = PracticeContract.LANGUAGE_JAVA,
                image = "learnloop-runner-java:latest",
                command = listOf("./gradlew", "test"),
                maxTimeoutMs = MAX_TIMEOUT_MS,
                files = harnessFiles("java-junit"),
            ),
        ).associateBy { "${it.language}:${it.id}" }

    fun find(
        language: String,
        harnessId: String,
    ): RunnerHarness? = harnesses["$language:$harnessId"]

    private fun harnessFiles(harnessId: String): List<RunnerHarnessFile> =
        listOf(
            RunnerHarnessFile(
                path = ".learnloop/harness.json",
                content = """{"harnessId":"$harnessId"}""",
            ),
        )

    private companion object {
        const val MAX_TIMEOUT_MS = 10_000L
    }
}
