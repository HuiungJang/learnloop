package com.aicodelearning.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProviderHttpClientTest {
    @Test
    fun `rejects oversized request before sending`() {
        FakeProviderServer.start().use { server ->
            val client = ProviderHttpClient(ProviderGenerationProperties(maxRequestBytes = 4))

            val exception =
                assertThrows(ProviderGenerationException::class.java) {
                    client.postJson(java.net.URI(server.baseUrl), emptyMap(), """{"too":"large"}""")
                }

            assertEquals(ProviderFailureCode.PROVIDER_UNUSABLE_OUTPUT, exception.failureCode)
            assertEquals(0, server.requests.size)
        }
    }

    @Test
    fun `rejects oversized response body`() {
        FakeProviderServer.start().use { server ->
            val client = ProviderHttpClient(ProviderGenerationProperties(maxResponseBytes = 8))
            server.enqueue(body = "response-body-is-too-large")

            val exception =
                assertThrows(ProviderGenerationException::class.java) {
                    client.postJson(java.net.URI(server.baseUrl), emptyMap(), "{}")
                }

            assertEquals(ProviderFailureCode.PROVIDER_UNUSABLE_OUTPUT, exception.failureCode)
            assertEquals(1, server.awaitRequestCount(1).size)
        }
    }

    @Test
    fun `rejects redirects without following`() {
        FakeProviderServer.start().use { server ->
            val client = ProviderHttpClient(ProviderGenerationProperties())
            server.enqueue(status = 302, body = "", headers = mapOf("location" to "https://example.com"))

            val exception =
                assertThrows(ProviderGenerationException::class.java) {
                    client.postJson(java.net.URI(server.baseUrl), emptyMap(), "{}")
                }

            assertEquals(ProviderFailureCode.PROVIDER_HTTP_ERROR, exception.failureCode)
            assertEquals(1, server.awaitRequestCount(1).size)
        }
    }
}
