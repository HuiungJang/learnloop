package com.aicodelearning.provider

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.max

class FakeProviderServer private constructor(
    private val server: HttpServer,
) : AutoCloseable {
    private val responses = LinkedBlockingQueue<FakeProviderResponse>()
    private val capturedRequests = CopyOnWriteArrayList<FakeProviderRequest>()
    private val executor = Executors.newCachedThreadPool()

    val port: Int
        get() = server.address.port

    val baseUrl: String
        get() = "http://127.0.0.1:$port"

    val requests: List<FakeProviderRequest>
        get() = capturedRequests.toList()

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = executor
        server.start()
    }

    fun enqueue(response: FakeProviderResponse) {
        responses.add(response)
    }

    fun enqueue(
        status: Int = HttpURLConnection.HTTP_OK,
        body: String = "{}",
        headers: Map<String, String> = mapOf("content-type" to "application/json"),
    ) {
        enqueue(FakeProviderResponse(status = status, body = body, headers = headers))
    }

    fun awaitRequestCount(
        expected: Int,
        timeout: Duration = Duration.ofSeconds(2),
    ): List<FakeProviderRequest> {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (capturedRequests.size >= expected) {
                return requests
            }
            Thread.sleep(10)
        }
        if (capturedRequests.size >= expected) {
            return requests
        }
        throw AssertionError("Expected at least $expected fake provider requests but received ${capturedRequests.size}")
    }

    private fun handle(exchange: HttpExchange) {
        val startedAt = System.nanoTime()
        val requestBody = exchange.requestBody.use { String(it.readBytes(), StandardCharsets.UTF_8) }
        val response =
            responses.poll()
                ?: FakeProviderResponse(
                    status = HttpURLConnection.HTTP_INTERNAL_ERROR,
                    body = """{"error":"No fake provider response enqueued"}""",
                )
        val elapsedMs = max(0L, Duration.ofNanos(System.nanoTime() - startedAt).toMillis())

        capturedRequests.add(
            FakeProviderRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.toString(),
                headers = exchange.requestHeaders.toLowercaseMap(),
                body = requestBody,
                responseStatus = response.status,
                responseBody = response.body,
                elapsedMs = elapsedMs,
            ),
        )

        val responseBytes = response.body.toByteArray(StandardCharsets.UTF_8)
        response.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        exchange.sendResponseHeaders(response.status, responseBytes.size.toLong())
        exchange.responseBody.use { it.write(responseBytes) }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        fun start(port: Int = 0): FakeProviderServer {
            val address = InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)
            return FakeProviderServer(HttpServer.create(address, 0))
        }
    }
}

data class FakeProviderResponse(
    val status: Int = HttpURLConnection.HTTP_OK,
    val body: String = "{}",
    val headers: Map<String, String> = mapOf("content-type" to "application/json"),
)

data class FakeProviderRequest(
    val method: String,
    val path: String,
    val headers: Map<String, List<String>>,
    val body: String,
    val responseStatus: Int,
    val responseBody: String,
    val elapsedMs: Long,
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]?.firstOrNull()
}

private fun com.sun.net.httpserver.Headers.toLowercaseMap(): Map<String, List<String>> =
    entries.associate { (name, values) -> name.lowercase(Locale.ROOT) to values.toList() }
