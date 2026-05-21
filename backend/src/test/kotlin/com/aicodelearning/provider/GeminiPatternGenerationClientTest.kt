package com.aicodelearning.provider

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.URI

class GeminiPatternGenerationClientTest {
    private val objectMapper = ObjectMapper()
    private val client =
        GeminiPatternGenerationClient(
            httpClient = ProviderHttpClient(ProviderGenerationProperties(allowLoopbackBaseUrl = true)),
            outputParser = PatternOutputParser(objectMapper),
            objectMapper = objectMapper,
        )

    @Test
    fun `sends generateContent request and parses candidate text`() {
        FakeProviderServer.start().use { server ->
            server.enqueue(
                body =
                    objectMapper.writeValueAsString(
                        mapOf(
                            "candidates" to
                                listOf(
                                    mapOf(
                                        "content" to mapOf("parts" to listOf(mapOf("text" to providerPatternOutput(objectMapper, "Gemini Pattern")))),
                                    ),
                                ),
                        ),
                    ),
            )

            val result = client.generate(clientRequest(server.baseUrl))

            assertEquals("Gemini Pattern", result.title)
            val request = server.awaitRequestCount(1).single()
            assertEquals("POST", request.method)
            assertEquals("/v1beta/models/gemini%201.5:generateContent?key=gemini-secret", request.path)
            val body = objectMapper.readTree(request.body)
            assertEquals("application/json", body["generationConfig"]["responseMimeType"].asText())
            assertEquals(321, body["generationConfig"]["maxOutputTokens"].asInt())
            assertTrue(body["generationConfig"].has("responseSchema"))
        }
    }

    @Test
    fun `maps missing candidate text to safe failure code`() {
        FakeProviderServer.start().use { server ->
            server.enqueue(body = """{"candidates":[{"content":{"parts":[]}}]}""")

            val exception =
                assertThrows(ProviderGenerationException::class.java) {
                    client.generate(clientRequest(server.baseUrl))
                }

            assertEquals(ProviderFailureCode.PROVIDER_MISSING_OUTPUT, exception.failureCode)
        }
    }

    @Test
    fun `maps provider HTTP error to safe failure code`() {
        FakeProviderServer.start().use { server ->
            server.enqueue(status = 500, body = """{"error":"do-not-leak"}""")

            val exception =
                assertThrows(ProviderGenerationException::class.java) {
                    client.generate(clientRequest(server.baseUrl))
                }

            assertEquals(ProviderFailureCode.PROVIDER_HTTP_ERROR, exception.failureCode)
        }
    }

    @Test
    fun `maps malformed provider JSON to safe failure code`() {
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
    fun `maps invalid schema output to safe failure code`() {
        FakeProviderServer.start().use { server ->
            server.enqueue(
                body =
                    objectMapper.writeValueAsString(
                        mapOf(
                            "candidates" to listOf(mapOf("content" to mapOf("parts" to listOf(mapOf("text" to """{"patterns":[]}"""))))),
                        ),
                    ),
            )

            val exception =
                assertThrows(ProviderGenerationException::class.java) {
                    client.generate(clientRequest(server.baseUrl))
                }

            assertEquals(ProviderFailureCode.PROVIDER_INVALID_SCHEMA, exception.failureCode)
        }
    }

    private fun clientRequest(baseUrl: String): PatternGenerationClientRequest =
        PatternGenerationClientRequest(
            providerConfig =
                ResolvedProviderConfig(
                    providerConfigId = "provider-gemini",
                    provider = ProviderCatalog.GEMINI,
                    model = "gemini 1.5",
                    baseUri = URI(baseUrl),
                    credential = "gemini-secret",
                    maxOutputTokens = 321,
                ),
            promptText = "Return JSON for a Gemini pattern.",
            evidenceExcerpt = "Gemini retry timeout",
        )
}
