package com.aicodelearning.runner

import com.aicodelearning.learning.PracticeContract
import com.aicodelearning.platform.BadRequestException
import org.springframework.stereotype.Component

data class RunnerLanguageDescriptor(
    val language: String,
    val displayName: String,
    val harnessId: String,
    val imageRef: String,
    val selectedByDefault: Boolean,
    val estimatedCompressedSizeMb: Int,
)

@Component
class RunnerImageCatalog(
    private val environment: Map<String, String> = System.getenv(),
) {
    private val version = environment["APP_RUNNER_IMAGE_VERSION"]?.takeIf { it.isNotBlank() } ?: "latest"
    private val registryPrefix =
        environment["APP_RUNNER_IMAGE_REGISTRY"]
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?.let { "$it/" }
            ?: ""

    private val descriptors =
        listOf(
            descriptor(
                language = PracticeContract.LANGUAGE_TYPESCRIPT,
                displayName = "TypeScript",
                harnessId = "typescript-node-test",
                defaultImage = "learnloop-runner-typescript:$version",
                overrideKey = "APP_RUNNER_TYPESCRIPT_IMAGE",
                selectedByDefault = true,
                estimatedCompressedSizeMb = 210,
            ),
            descriptor(
                language = PracticeContract.LANGUAGE_KOTLIN,
                displayName = "Kotlin",
                harnessId = "kotlin-junit",
                defaultImage = "learnloop-runner-kotlin:$version",
                overrideKey = "APP_RUNNER_KOTLIN_IMAGE",
                selectedByDefault = true,
                estimatedCompressedSizeMb = 440,
            ),
            descriptor(
                language = PracticeContract.LANGUAGE_JAVA,
                displayName = "Java",
                harnessId = "java-junit",
                defaultImage = "learnloop-runner-java:$version",
                overrideKey = "APP_RUNNER_JAVA_IMAGE",
                selectedByDefault = true,
                estimatedCompressedSizeMb = 355,
            ),
            descriptor(
                language = PracticeContract.LANGUAGE_SWIFT,
                displayName = "Swift",
                harnessId = "swift-xctest",
                defaultImage = "${registryPrefix}learnloop-runner-swift:$version",
                overrideKey = "APP_RUNNER_SWIFT_IMAGE",
                selectedByDefault = false,
                estimatedCompressedSizeMb = 1_100,
            ),
            descriptor(
                language = PracticeContract.LANGUAGE_RUST,
                displayName = "Rust",
                harnessId = "rust-cargo-test",
                defaultImage = "${registryPrefix}learnloop-runner-rust:$version",
                overrideKey = "APP_RUNNER_RUST_IMAGE",
                selectedByDefault = false,
                estimatedCompressedSizeMb = 290,
            ),
        )

    fun languages(): List<RunnerLanguageDescriptor> = descriptors

    fun requireLanguage(language: String): RunnerLanguageDescriptor =
        descriptors.find { it.language == language }
            ?: throw BadRequestException("runner language is not supported")

    private fun descriptor(
        language: String,
        displayName: String,
        harnessId: String,
        defaultImage: String,
        overrideKey: String,
        selectedByDefault: Boolean,
        estimatedCompressedSizeMb: Int,
    ): RunnerLanguageDescriptor =
        RunnerLanguageDescriptor(
            language = language,
            displayName = displayName,
            harnessId = harnessId,
            imageRef = environment[overrideKey]?.takeIf { it.isNotBlank() } ?: defaultImage,
            selectedByDefault = selectedByDefault,
            estimatedCompressedSizeMb = estimatedCompressedSizeMb,
        )
}
