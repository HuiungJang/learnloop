package com.aicodelearning.provider

import com.fasterxml.jackson.databind.ObjectMapper

fun providerPatternOutput(
    objectMapper: ObjectMapper,
    title: String,
): String =
    objectMapper.writeValueAsString(
        mapOf(
            "patterns" to
                listOf(
                    mapOf(
                        "title" to title,
                        "summary" to "Provider generated summary.",
                        "confidence" to 0.91,
                        "tags" to listOf(mapOf("type" to "framework", "name" to "Provider React")),
                        "evidenceRefs" to listOf("bundle"),
                        "languageAgnosticExplanation" to "Keep the boundary explicit.",
                        "implementationGuidance" to listOf("Validate provider output before persistence."),
                        "commonFailureModes" to listOf("Invalid provider JSON."),
                        "problems" to
                            listOf(
                                mapOf("type" to "qa", "difficulty" to "beginner", "prompt" to "When is this pattern useful?", "referenceAnswer" to "When provider output must be validated before use."),
                                mapOf("type" to "short_implementation", "difficulty" to "intermediate", "prompt" to "Implement a validated adapter.", "referenceAnswer" to "Parse into a strict DTO before persistence."),
                                mapOf("type" to "debugging", "difficulty" to "intermediate", "prompt" to "What should fail safely?", "referenceAnswer" to "Invalid JSON and HTTP failures should create no assets."),
                            ),
                        "reviewRisks" to listOf("correctness"),
                    ),
                ),
        ),
    )
