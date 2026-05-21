package com.aicodelearning.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

class FakeProviderServerTest {
    @Test
    fun `records request and configured response`() {
        FakeProviderServer.start().use { server ->
            server.enqueue(status = HttpURLConnection.HTTP_CREATED, body = """{"ok":true}""", headers = mapOf("x-test" to "yes"))

            val connection = URI("${server.baseUrl}/v1/responses?mode=test").toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer test-secret")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write("""{"model":"fake"}""".toByteArray(StandardCharsets.UTF_8)) }

            assertEquals(HttpURLConnection.HTTP_CREATED, connection.responseCode)
            assertEquals("""{"ok":true}""", connection.inputStream.bufferedReader().use { it.readText() })
            assertEquals("yes", connection.getHeaderField("x-test"))

            val request = server.awaitRequestCount(1).single()
            assertEquals("POST", request.method)
            assertEquals("/v1/responses?mode=test", request.path)
            assertEquals("Bearer test-secret", request.header("authorization"))
            assertEquals("application/json", request.header("content-type"))
            assertEquals("""{"model":"fake"}""", request.body)
            assertEquals(HttpURLConnection.HTTP_CREATED, request.responseStatus)
            assertEquals("""{"ok":true}""", request.responseBody)
            assertTrue(request.elapsedMs >= 0)
        }
    }

    @Test
    fun `close releases the port`() {
        val first = FakeProviderServer.start()
        val port = first.port
        first.close()

        FakeProviderServer.start(port).use { rebound ->
            rebound.enqueue()
            val connection = URI("${rebound.baseUrl}/health").toURL().openConnection() as HttpURLConnection

            assertEquals(HttpURLConnection.HTTP_OK, connection.responseCode)
        }
    }
}
