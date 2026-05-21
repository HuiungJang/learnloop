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
class RunnerRegistry(
    private val catalog: RunnerImageCatalog = RunnerImageCatalog(),
) {
    private val descriptorsByLanguage = catalog.languages().associateBy { it.language }
    private val harnesses =
        catalog
            .languages()
            .filter { it.language in runnableLanguages }
            .map { descriptor ->
                RunnerHarness(
                    id = descriptor.harnessId,
                    language = descriptor.language,
                    image = descriptor.imageRef,
                    command = commandFor(descriptor.language),
                    maxTimeoutMs = MAX_TIMEOUT_MS,
                    files = harnessFiles(descriptor.harnessId),
                )
            }.associateBy { "${it.language}:${it.id}" }

    fun find(
        language: String,
        harnessId: String,
    ): RunnerHarness? = harnesses["$language:$harnessId"]

    fun requiredImages(): List<String> =
        harnesses
            .values
            .filter { descriptorsByLanguage[it.language]?.selectedByDefault == true }
            .map { it.image }
            .distinct()
            .sorted()

    private fun commandFor(language: String): List<String> =
        when (language) {
            PracticeContract.LANGUAGE_TYPESCRIPT -> listOf("node", "/opt/learnloop-runner/run-tests.mjs")
            PracticeContract.LANGUAGE_KOTLIN,
            PracticeContract.LANGUAGE_JAVA,
            PracticeContract.LANGUAGE_SWIFT,
            PracticeContract.LANGUAGE_RUST -> listOf("/opt/learnloop-runner/run-tests.sh")
            else -> error("runner command is not registered for language: $language")
        }

    private fun harnessFiles(harnessId: String): List<RunnerHarnessFile> =
        listOf(
            RunnerHarnessFile(
                path = ".learnloop/harness.json",
                content = """{"harnessId":"$harnessId"}""",
            ),
        )

    private companion object {
        const val MAX_TIMEOUT_MS = 10_000L
        val runnableLanguages =
            setOf(
                PracticeContract.LANGUAGE_TYPESCRIPT,
                PracticeContract.LANGUAGE_KOTLIN,
                PracticeContract.LANGUAGE_JAVA,
                PracticeContract.LANGUAGE_SWIFT,
                PracticeContract.LANGUAGE_RUST,
            )
    }
}
