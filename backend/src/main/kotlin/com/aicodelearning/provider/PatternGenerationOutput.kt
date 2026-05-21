package com.aicodelearning.provider

import com.aicodelearning.learning.PatternRecognitionPromptBuilder
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

data class PatternGenerationResult(
    val title: String,
    val summary: String,
    val tags: List<GeneratedPatternTag>,
    val problems: List<GeneratedProblem>,
)

data class GeneratedPatternTag(
    val type: String,
    val name: String,
)

data class GeneratedProblem(
    val type: String,
    val difficulty: String,
    val prompt: String,
    val referenceAnswer: String,
)

@Component
class PatternOutputParser(
    private val objectMapper: ObjectMapper,
) {
    fun parse(jsonText: String): PatternGenerationResult {
        if (jsonText.length > MAX_RESPONSE_CHARS) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_UNUSABLE_OUTPUT)
        }
        if (jsonText.contains(PatternRecognitionPromptBuilder.INTERNAL_PROMPT_MARKER) || likelySecretRegex.containsMatchIn(jsonText)) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_UNUSABLE_OUTPUT)
        }

        val root =
            try {
                objectMapper.readTree(jsonText)
            } catch (_: Exception) {
                throw ProviderGenerationException(ProviderFailureCode.PROVIDER_INVALID_JSON)
            }
        val patterns = root.requiredArray("patterns")
        if (patterns.size() != 1) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_INVALID_SCHEMA)
        }
        val pattern = patterns[0]
        val tags =
            pattern
                .requiredArray("tags")
                .map { tag ->
                    GeneratedPatternTag(
                        type = tag.requiredText("type").requireAllowed(allowedTagTypes),
                        name = tag.requiredText("name").requireBoundedText(MAX_TAG_NAME),
                    )
                }
        val problems =
            pattern
                .requiredArray("problems")
                .map { problem ->
                    GeneratedProblem(
                        type = problem.requiredText("type").requireAllowed(allowedProblemTypes),
                        difficulty = problem.requiredText("difficulty").requireAllowed(allowedDifficulties),
                        prompt = problem.requiredText("prompt").requireBoundedText(MAX_PROMPT),
                        referenceAnswer = problem.requiredText("referenceAnswer").requireBoundedText(MAX_REFERENCE_ANSWER),
                    )
                }
        if (tags.isEmpty() || tags.size > MAX_TAGS || problems.size != REQUIRED_PROBLEM_COUNT) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_INVALID_SCHEMA)
        }

        return PatternGenerationResult(
            title = pattern.requiredText("title").requireBoundedText(MAX_TITLE),
            summary = pattern.requiredText("summary").requireBoundedText(MAX_SUMMARY),
            tags = tags,
            problems = problems,
        )
    }

    private fun JsonNode.requiredArray(field: String): JsonNode {
        val child = get(field)
        if (child == null || !child.isArray) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_INVALID_SCHEMA)
        }
        return child
    }

    private fun JsonNode.requiredText(field: String): String {
        val child = get(field)
        if (child == null || !child.isTextual || child.asText().isBlank()) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_INVALID_SCHEMA)
        }
        return child.asText().trim()
    }

    private fun String.requireAllowed(allowed: Set<String>): String {
        if (this !in allowed) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_INVALID_SCHEMA)
        }
        return this
    }

    private fun String.requireBoundedText(maxLength: Int): String {
        if (length > maxLength) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_INVALID_SCHEMA)
        }
        return this
    }

    private companion object {
        const val MAX_RESPONSE_CHARS = 65_536
        const val MAX_TITLE = 160
        const val MAX_SUMMARY = 1_000
        const val MAX_TAG_NAME = 80
        const val MAX_PROMPT = 4_000
        const val MAX_REFERENCE_ANSWER = 4_000
        const val MAX_TAGS = 8
        const val REQUIRED_PROBLEM_COUNT = 3
        val allowedTagTypes = setOf("framework", "library", "api", "algorithm", "design_pattern", "configuration", "testing", "pattern")
        val allowedProblemTypes = setOf("qa", "short_implementation", "debugging")
        val allowedDifficulties = setOf("beginner", "intermediate", "advanced")
        val likelySecretRegex = Regex("""(?i)\b(api[_-]?key|token|password|secret)\s*[:=]\s*["']?[^"'\s,;]+""")
    }
}

fun patternGenerationJsonSchema(): Map<String, Any> =
    mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "required" to listOf("patterns"),
        "properties" to
            mapOf(
                "patterns" to
                    mapOf(
                        "type" to "array",
                        "minItems" to 1,
                        "maxItems" to 1,
                        "items" to
                            mapOf(
                                "type" to "object",
                                "additionalProperties" to false,
                                "required" to listOf("title", "summary", "confidence", "tags", "evidenceRefs", "languageAgnosticExplanation", "implementationGuidance", "commonFailureModes", "problems", "reviewRisks"),
                                "properties" to
                                    mapOf(
                                        "title" to mapOf("type" to "string", "maxLength" to 160),
                                        "summary" to mapOf("type" to "string", "maxLength" to 1000),
                                        "confidence" to mapOf("type" to "number"),
                                        "tags" to
                                            mapOf(
                                                "type" to "array",
                                                "minItems" to 1,
                                                "maxItems" to 8,
                                                "items" to
                                                    mapOf(
                                                        "type" to "object",
                                                        "additionalProperties" to false,
                                                        "required" to listOf("type", "name"),
                                                        "properties" to
                                                            mapOf(
                                                                "type" to mapOf("type" to "string", "enum" to listOf("framework", "library", "api", "algorithm", "design_pattern", "configuration", "testing", "pattern")),
                                                                "name" to mapOf("type" to "string", "maxLength" to 80),
                                                            ),
                                                    ),
                                            ),
                                        "evidenceRefs" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "maxItems" to 5),
                                        "languageAgnosticExplanation" to mapOf("type" to "string", "maxLength" to 1000),
                                        "implementationGuidance" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "maxItems" to 5),
                                        "commonFailureModes" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "maxItems" to 5),
                                        "problems" to
                                            mapOf(
                                                "type" to "array",
                                                "minItems" to 3,
                                                "maxItems" to 3,
                                                "items" to
                                                    mapOf(
                                                        "type" to "object",
                                                        "additionalProperties" to false,
                                                        "required" to listOf("type", "difficulty", "prompt", "referenceAnswer"),
                                                        "properties" to
                                                            mapOf(
                                                                "type" to mapOf("type" to "string", "enum" to listOf("qa", "short_implementation", "debugging")),
                                                                "difficulty" to mapOf("type" to "string", "enum" to listOf("beginner", "intermediate", "advanced")),
                                                                "prompt" to mapOf("type" to "string", "maxLength" to 4000),
                                                                "referenceAnswer" to mapOf("type" to "string", "maxLength" to 4000),
                                                            ),
                                                    ),
                                            ),
                                        "reviewRisks" to mapOf("type" to "array", "items" to mapOf("type" to "string", "enum" to listOf("security", "privacy", "correctness", "deduplication")), "maxItems" to 4),
                                    ),
                            ),
                    ),
            ),
    )
