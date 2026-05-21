package com.aicodelearning.provider

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.URI

class OpenAiPatternGenerationClientTest {
    private val objectMapper = ObjectMapper()
    private val client =
        OpenAiPatternGenerationClient(
            httpClient = ProviderHttpClient(ProviderGenerationProperties(allowLoopbackBaseUrl = true)),
            outputParser = PatternOutputParser(objectMapper),
            objectMapper = objectMapper,
        )

    @Test
    fun `sends responses request and parses nested output text`() {
        FakeProviderServer.start().use { server ->
            server.enqueue(
                body =
                    objectMapper.writeValueAsString(
                        mapOf(
                            "output" to
                                listOf(
                                    mapOf(
                                        "type" to "message",
                                        "content" to listOf(mapOf("type" to "output_text", "text" to patternOutput("OpenAI Nested Pattern"))),
                                    ),
                                ),
                        ),
                    ),
            )

            val result = client.generate(clientRequest(ProviderCatalog.OPENAI, server.baseUrl, "openai-secret"))

            assertEquals("OpenAI Nested Pattern", result.title)
            val request = server.awaitRequestCount(1).single()
            assertEquals("POST", request.method)
            assertEquals("/v1/responses", request.path)
            assertEquals("Bearer openai-secret", request.header("authorization"))
            val body = objectMapper.readTree(request.body)
            assertEquals("fake-model", body["model"].asText())
            assertEquals(321, body["max_output_tokens"].asInt())
            assertEquals("json_schema", body["text"]["format"]["type"].asText())
            assertEquals(true, body["text"]["format"]["strict"].asBoolean())
            assertTrue(body["text"]["format"].has("schema"))
        }
    }

    @Test
    fun `maps missing output to safe failure code`() {
        FakeProviderServer.start().use { server ->
            server.enqueue(body = """{"output":[]}""")

            val exception =
                assertThrows(ProviderGenerationException::class.java) {
                    client.generate(clientRequest(ProviderCatalog.CODEX, server.baseUrl, "codex-secret"))
                }

            assertEquals(ProviderFailureCode.PROVIDER_MISSING_OUTPUT, exception.failureCode)
            assertEquals(1, server.awaitRequestCount(1).size)
        }
    }

    @Test
    fun `maps provider HTTP error to safe failure code`() {
        FakeProviderServer.start().use { server ->
            server.enqueue(status = 401, body = """{"error":"do-not-leak"}""")

            val exception =
                assertThrows(ProviderGenerationException::class.java) {
                    client.generate(clientRequest(ProviderCatalog.OPENAI, server.baseUrl, "openai-secret"))
                }

            assertEquals(ProviderFailureCode.PROVIDER_HTTP_ERROR, exception.failureCode)
            assertEquals(1, server.awaitRequestCount(1).size)
        }
    }

    @Test
    fun `maps malformed provider JSON to safe failure code`() {
        FakeProviderServer.start().use { server ->
            server.enqueue(body = "not-json")

            val exception =
                assertThrows(ProviderGenerationException::class.java) {
                    client.generate(clientRequest(ProviderCatalog.OPENAI, server.baseUrl, "openai-secret"))
                }

            assertEquals(ProviderFailureCode.PROVIDER_INVALID_JSON, exception.failureCode)
        }
    }

    private fun clientRequest(
        provider: String,
        baseUrl: String,
        credential: String,
    ): PatternGenerationClientRequest =
        PatternGenerationClientRequest(
            providerConfig =
                ResolvedProviderConfig(
                    providerConfigId = "provider-test",
                    provider = provider,
                    model = "fake-model",
                    baseUri = URI(baseUrl),
                    credential = credential,
                    maxOutputTokens = 321,
                ),
            promptText = "Return JSON for a React retry pattern.",
            evidenceExcerpt = "React retry timeout",
        )

    private fun patternOutput(title: String): String = providerPatternOutput(objectMapper, title)
}
