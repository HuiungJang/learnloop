package com.aicodelearning.provider

import org.springframework.stereotype.Component
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration

@Component
class ProviderHttpClient(
    private val properties: ProviderGenerationProperties,
) {
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(properties.connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(NoProxySelector)
            .build()

    fun postJson(
        uri: URI,
        headers: Map<String, String>,
        body: String,
    ): ProviderHttpResponse {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        if (bodyBytes.size > properties.maxRequestBytes) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_UNUSABLE_OUTPUT)
        }

        val requestBuilder =
            HttpRequest
                .newBuilder(uri)
                .timeout(properties.requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }

        val response =
            try {
                client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
            } catch (_: HttpTimeoutException) {
                throw ProviderGenerationException(ProviderFailureCode.PROVIDER_TIMEOUT)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ProviderGenerationException(ProviderFailureCode.PROVIDER_TIMEOUT)
            } catch (_: IOException) {
                throw ProviderGenerationException(ProviderFailureCode.PROVIDER_NETWORK_ERROR)
            } catch (_: IllegalArgumentException) {
                throw ProviderGenerationException(ProviderFailureCode.PROVIDER_CONFIGURATION_INVALID)
            }

        val responseBody =
            response.body().use { stream ->
                val bytes = stream.readNBytes(properties.maxResponseBytes + 1)
                if (bytes.size > properties.maxResponseBytes) {
                    throw ProviderGenerationException(ProviderFailureCode.PROVIDER_UNUSABLE_OUTPUT)
                }
                String(bytes, StandardCharsets.UTF_8)
            }
        if (response.statusCode() in HTTP_REDIRECT_START..HTTP_REDIRECT_END) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_HTTP_ERROR)
        }
        if (response.statusCode() !in HttpURLConnection.HTTP_OK..299) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_HTTP_ERROR)
        }

        return ProviderHttpResponse(response.statusCode(), responseBody)
    }

    private object NoProxySelector : ProxySelector() {
        override fun select(uri: URI): List<java.net.Proxy> = listOf(java.net.Proxy.NO_PROXY)

        override fun connectFailed(
            uri: URI,
            sa: java.net.SocketAddress,
            ioe: IOException,
        ) {
            // Intentionally ignored. Provider calls never fall back to a proxy.
        }
    }

    private companion object {
        const val HTTP_REDIRECT_START = 300
        const val HTTP_REDIRECT_END = 399
    }
}

data class ProviderHttpResponse(
    val status: Int,
    val body: String,
)
