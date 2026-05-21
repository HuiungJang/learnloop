package com.aicodelearning.provider

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

interface PatternGenerationClient {
    fun supports(provider: String): Boolean

    fun generate(request: PatternGenerationClientRequest): PatternGenerationResult
}

data class PatternGenerationClientRequest(
    val providerConfig: ResolvedProviderConfig,
    val promptText: String,
    val evidenceExcerpt: String,
)

@Component
class PatternGenerationClientFactory(
    clients: List<PatternGenerationClient>,
) {
    private val clientsByPriority = clients.sortedBy { if (it is LocalPatternGenerationClient) 0 else 1 }

    fun clientFor(provider: String): PatternGenerationClient =
        clientsByPriority.firstOrNull { it.supports(provider) }
            ?: throw ProviderGenerationException(ProviderFailureCode.PROVIDER_CONFIGURATION_INVALID)
}

@Component
class LocalPatternGenerationClient : PatternGenerationClient {
    override fun supports(provider: String): Boolean = provider == ProviderCatalog.LOCAL

    override fun generate(request: PatternGenerationClientRequest): PatternGenerationResult {
        val normalized = request.evidenceExcerpt.lowercase()
        val tags =
            buildList {
                if ("react" in normalized || "queryclient" in normalized) add(GeneratedPatternTag("framework", "React"))
                if ("spring" in normalized || "@service" in normalized) add(GeneratedPatternTag("framework", "Spring"))
                if ("retry" in normalized || "timeout" in normalized) add(GeneratedPatternTag("pattern", "Retry/Timeout"))
                if ("oauth" in normalized || "token" in normalized || "authorization" in normalized) add(GeneratedPatternTag("api", "Auth API"))
                if (isEmpty()) add(GeneratedPatternTag("pattern", "Implementation Pattern"))
            }
        val primary = tags.first()
        return PatternGenerationResult(
            title = "${primary.name} Practice Pattern",
            summary = "A reusable ${primary.name.lowercase()} pattern extracted from AI-assisted implementation evidence.",
            tags = tags,
            problems =
                listOf(
                    GeneratedProblem("qa", "beginner", "When should a developer use the ${primary.name} approach shown in this pattern?", "Use it when the implementation has similar technical constraints while avoiding product-specific details."),
                    GeneratedProblem("short_implementation", "intermediate", "Implement a similar pattern in a neutral order-processing domain.", "A strong answer keeps the API boundary explicit, handles errors, and keeps domain names generic."),
                    GeneratedProblem("debugging", "intermediate", "What failure mode should be tested before reusing this pattern?", "Test the edge case that would break the boundary, such as timeout, invalid token, or missing dependency behavior."),
                ),
        )
    }
}

@Component
class OpenAiPatternGenerationClient(
    private val httpClient: ProviderHttpClient,
    private val outputParser: PatternOutputParser,
    private val objectMapper: ObjectMapper,
) : PatternGenerationClient {
    override fun supports(provider: String): Boolean = provider == ProviderCatalog.OPENAI || provider == ProviderCatalog.CODEX

    override fun generate(request: PatternGenerationClientRequest): PatternGenerationResult {
        val config = request.providerConfig
        val body =
            objectMapper.writeValueAsString(
                mapOf(
                    "model" to config.model,
                    "input" to request.promptText,
                    "max_output_tokens" to config.maxOutputTokens,
                    "text" to
                        mapOf(
                            "format" to
                                mapOf(
                                    "type" to "json_schema",
                                    "name" to "learnloop_pattern_generation",
                                    "strict" to true,
                                    "schema" to patternGenerationJsonSchema(),
                                ),
                        ),
                ),
            )
        val response =
            httpClient.postJson(
                uri = config.requiredBaseUri().appendPath("/v1/responses"),
                headers = mapOf("Authorization" to "Bearer ${config.requiredCredential()}"),
                body = body,
            )
        return outputParser.parse(extractOpenAiText(response.body))
    }

    private fun extractOpenAiText(body: String): String {
        val root = parseProviderBody(body)
        root.get("output_text")?.takeIf { it.isTextual && it.asText().isNotBlank() }?.let { return it.asText() }
        val texts =
            root.path("output")
                .flatMap { output -> output.path("content") }
                .filter { it.path("type").asText() in setOf("output_text", "text") && it.path("text").isTextual }
                .map { it.path("text").asText() }
                .filter { it.isNotBlank() }
        if (texts.isEmpty()) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_MISSING_OUTPUT)
        }
        return texts.joinToString("\n")
    }

    private fun parseProviderBody(body: String): JsonNode =
        try {
            objectMapper.readTree(body)
        } catch (_: Exception) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_INVALID_JSON)
        }
}

@Component
class GeminiPatternGenerationClient(
    private val httpClient: ProviderHttpClient,
    private val outputParser: PatternOutputParser,
    private val objectMapper: ObjectMapper,
) : PatternGenerationClient {
    override fun supports(provider: String): Boolean = provider == ProviderCatalog.GEMINI

    override fun generate(request: PatternGenerationClientRequest): PatternGenerationResult {
        val config = request.providerConfig
        val body =
            objectMapper.writeValueAsString(
                mapOf(
                    "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to request.promptText)))),
                    "generationConfig" to
                        mapOf(
                            "responseMimeType" to "application/json",
                            "responseSchema" to patternGenerationJsonSchema(),
                            "maxOutputTokens" to config.maxOutputTokens,
                        ),
                ),
            )
        val modelPath = URLEncoder.encode(config.model, StandardCharsets.UTF_8).replace("+", "%20")
        val uri = config.requiredBaseUri().appendPath("/v1beta/models/$modelPath:generateContent?key=${urlEncode(config.requiredCredential())}")
        val response = httpClient.postJson(uri = uri, headers = emptyMap(), body = body)
        return outputParser.parse(extractGeminiText(response.body))
    }

    private fun extractGeminiText(body: String): String {
        val root = parseProviderBody(body)
        val texts =
            root.path("candidates")
                .flatMap { candidate -> candidate.path("content").path("parts") }
                .filter { it.path("text").isTextual }
                .map { it.path("text").asText() }
                .filter { it.isNotBlank() }
        if (texts.isEmpty()) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_MISSING_OUTPUT)
        }
        return texts.joinToString("\n")
    }

    private fun parseProviderBody(body: String): JsonNode =
        try {
            objectMapper.readTree(body)
        } catch (_: Exception) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_INVALID_JSON)
        }
}

@Component
class ClaudePatternGenerationClient(
    private val httpClient: ProviderHttpClient,
    private val outputParser: PatternOutputParser,
    private val objectMapper: ObjectMapper,
) : PatternGenerationClient {
    override fun supports(provider: String): Boolean = provider == ProviderCatalog.CLAUDE

    override fun generate(request: PatternGenerationClientRequest): PatternGenerationResult {
        val config = request.providerConfig
        val body =
            objectMapper.writeValueAsString(
                mapOf(
                    "model" to config.model,
                    "max_tokens" to config.maxOutputTokens,
                    "messages" to listOf(mapOf("role" to "user", "content" to request.promptText)),
                    "tools" to
                        listOf(
                            mapOf(
                                "name" to "emit_pattern_generation",
                                "description" to "Emit LearnLoop pattern generation JSON.",
                                "input_schema" to patternGenerationJsonSchema(),
                            ),
                        ),
                    "tool_choice" to mapOf("type" to "tool", "name" to "emit_pattern_generation"),
                ),
            )
        val response =
            httpClient.postJson(
                uri = config.requiredBaseUri().appendPath("/v1/messages"),
                headers =
                    mapOf(
                        "x-api-key" to config.requiredCredential(),
                        "anthropic-version" to "2023-06-01",
                    ),
                body = body,
            )
        return outputParser.parse(extractClaudeJson(response.body))
    }

    private fun extractClaudeJson(body: String): String {
        val root = parseProviderBody(body)
        if (root.path("stop_reason").asText() == "max_tokens") {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_UNUSABLE_OUTPUT)
        }
        val toolInput =
            root.path("content")
                .firstOrNull { it.path("type").asText() == "tool_use" && it.path("input").isObject }
                ?.path("input")
        if (toolInput != null) {
            return objectMapper.writeValueAsString(toolInput)
        }
        val texts =
            root.path("content")
                .filter { it.path("type").asText() == "text" && it.path("text").isTextual }
                .map { it.path("text").asText() }
                .filter { it.isNotBlank() }
        if (texts.isEmpty()) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_MISSING_OUTPUT)
        }
        return texts.joinToString("\n")
    }

    private fun parseProviderBody(body: String): JsonNode =
        try {
            objectMapper.readTree(body)
        } catch (_: Exception) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_INVALID_JSON)
        }
}

private fun ResolvedProviderConfig.requiredBaseUri(): URI =
    baseUri ?: throw ProviderGenerationException(ProviderFailureCode.PROVIDER_CONFIGURATION_INVALID)

private fun ResolvedProviderConfig.requiredCredential(): String =
    credential ?: throw ProviderGenerationException(ProviderFailureCode.PROVIDER_CONFIGURATION_INVALID)

private fun URI.appendPath(pathAndQuery: String): URI {
    val base = toString().trimEnd('/')
    return URI.create("$base$pathAndQuery")
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
