package com.aicodelearning.runner

import com.aicodelearning.learning.PracticeContract
import com.aicodelearning.platform.BadRequestException
import org.springframework.stereotype.Component

data class RunnerLanguageDescriptor(
    val language: String,
    val displayName: String,
    val harnessId: String,
    val imageRef: String,
    val imageSource: String,
    val selectedByDefault: Boolean,
    val estimatedCompressedSizeMb: Int,
)

object RunnerImageSources {
    const val LOCAL = "local"
    const val REGISTRY = "registry"
    const val BUNDLED = "bundled"

    val all = setOf(LOCAL, REGISTRY, BUNDLED)
}

@Component
class RunnerImageCatalog(
    private val environment: Map<String, String> = System.getenv(),
) {
    private val version = environment["APP_RUNNER_IMAGE_VERSION"]?.takeIf { it.isNotBlank() } ?: "latest"
    private val explicitImageSource =
        environment["APP_RUNNER_IMAGE_SOURCE"]
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?.also {
                if (it !in RunnerImageSources.all) {
                    throw BadRequestException("runner image source is not supported")
                }
            }
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
    ): RunnerLanguageDescriptor {
        val overrideImage = environment[overrideKey]?.takeIf { it.isNotBlank() }
        val imageRef = overrideImage ?: defaultImage
        return RunnerLanguageDescriptor(
            language = language,
            displayName = displayName,
            harnessId = harnessId,
            imageRef = imageRef,
            imageSource = sourceFor(imageRef, overrideImage != null),
            selectedByDefault = selectedByDefault,
            estimatedCompressedSizeMb = estimatedCompressedSizeMb,
        )
    }

    private fun sourceFor(
        imageRef: String,
        fromOverride: Boolean,
    ): String =
        explicitImageSource
            ?: if ((!fromOverride && registryPrefix.isNotBlank()) || looksLikeRegistryImage(imageRef)) {
                RunnerImageSources.REGISTRY
            } else {
                RunnerImageSources.LOCAL
            }

    private fun looksLikeRegistryImage(imageRef: String): Boolean {
        val firstComponent = imageRef.substringBefore('/')
        return imageRef.contains('/') &&
            (firstComponent.contains('.') || firstComponent.contains(':') || firstComponent == "localhost")
    }
}
