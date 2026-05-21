package com.aicodelearning.provider

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.URI

class ClaudePatternGenerationClientTest {
    private val objectMapper = ObjectMapper()
    private val client =
        ClaudePatternGenerationClient(
            httpClient = ProviderHttpClient(ProviderGenerationProperties(allowLoopbackBaseUrl = true)),
            outputParser = PatternOutputParser(objectMapper),
            objectMapper = objectMapper,
        )

    @Test
    fun `sends messages request and parses tool input`() {
        FakeProviderServer.start().use { server ->
            server.enqueue(
                body =
                    objectMapper.writeValueAsString(
                        mapOf(
                            "stop_reason" to "tool_use",
                            "content" to
                                listOf(
                                    mapOf(
                                        "type" to "tool_use",
                                        "name" to "emit_pattern_generation",
                                        "input" to objectMapper.readTree(providerPatternOutput(objectMapper, "Claude Tool Pattern")),
                                    ),
                                ),
                        ),
                    ),
            )

            val result = client.generate(clientRequest(server.baseUrl))

            assertEquals("Claude Tool Pattern", result.title)
            val request = server.awaitRequestCount(1).single()
            assertEquals("POST", request.method)
            assertEquals("/v1/messages", request.path)
            assertEquals("claude-secret", request.header("x-api-key"))
            assertEquals("2023-06-01", request.header("anthropic-version"))
            val body = objectMapper.readTree(request.body)
            assertEquals("claude-fake-model", body["model"].asText())
            assertEquals(321, body["max_tokens"].asInt())
            assertEquals("emit_pattern_generation", body["tools"][0]["name"].asText())
            assertTrue(body["tools"][0].has("input_schema"))
            assertEquals("tool", body["tool_choice"]["type"].asText())
        }
    }

    @Test
    fun `maps max token stop to safe failure code`() {
        FakeProviderServer.start().use { server ->
            server.enqueue(body = """{"stop_reason":"max_tokens","content":[{"type":"text","text":"{}"}]}""")

            val exception =
                assertThrows(ProviderGenerationException::class.java) {
                    client.generate(clientRequest(server.baseUrl))
                }

            assertEquals(ProviderFailureCode.PROVIDER_UNUSABLE_OUTPUT, exception.failureCode)
        }
    }

    @Test
    fun `maps refusal to safe failure code`() {
        FakeProviderServer.start().use { server ->
            server.enqueue(body = """{"stop_reason":"refusal","content":[{"type":"text","text":"no"}]}""")

            val exception =
                assertThrows(ProviderGenerationException::class.java) {
                    client.generate(clientRequest(server.baseUrl))
                }

            assertEquals(ProviderFailureCode.PROVIDER_UNUSABLE_OUTPUT, exception.failureCode)
        }
    }

    @Test
    fun `maps malformed response to safe failure code`() {
        FakeProviderServer.start().use { server ->
            server.enqueue(body = """{"content":[]}""")

            val exception =
                assertThrows(ProviderGenerationException::class.java) {
                    client.generate(clientRequest(server.baseUrl))
                }

            assertEquals(ProviderFailureCode.PROVIDER_MISSING_OUTPUT, exception.failureCode)
        }
    }

    @Test
    fun `maps invalid JSON to safe failure code`() {
        FakeProviderServer.start().use { server ->
            server.enqueue(body = "not-json")

            val exception =
                assertThrows(ProviderGenerationException::class.java) {
                    client.generate(clientRequest(server.baseUrl))
                }

            assertEquals(ProviderFailureCode.PROVIDER_INVALID_JSON, exception.failureCode)
        }
    }

    @Test
    fun `maps HTTP error to safe failure code`() {
        FakeProviderServer.start().use { server ->
            server.enqueue(status = 403, body = """{"error":"do-not-leak"}""")

            val exception =
                assertThrows(ProviderGenerationException::class.java) {
                    client.generate(clientRequest(server.baseUrl))
                }

            assertEquals(ProviderFailureCode.PROVIDER_HTTP_ERROR, exception.failureCode)
        }
    }

    private fun clientRequest(baseUrl: String): PatternGenerationClientRequest =
        PatternGenerationClientRequest(
            providerConfig =
                ResolvedProviderConfig(
                    providerConfigId = "provider-claude",
                    provider = ProviderCatalog.CLAUDE,
                    model = "claude-fake-model",
                    baseUri = URI(baseUrl),
                    credential = "claude-secret",
                    maxOutputTokens = 321,
                ),
            promptText = "Return JSON for a Claude pattern.",
            evidenceExcerpt = "Claude retry timeout",
        )
}
