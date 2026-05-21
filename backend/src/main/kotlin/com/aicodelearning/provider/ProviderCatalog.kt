package com.aicodelearning.provider

import com.aicodelearning.platform.BadRequestException
import org.springframework.stereotype.Component
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

object ProviderCatalog {
    const val LOCAL = "local"
    const val OPENAI = "openai"
    const val CODEX = "codex"
    const val GEMINI = "gemini"
    const val CLAUDE = "claude"
    const val LOCAL_MOCK_ID = "provider-local-mock"

    fun normalize(provider: String): String =
        when (provider.trim().lowercase(Locale.ROOT).replace("_", "-")) {
            "local", "local-mock", "mock" -> LOCAL
            "openai", "open-ai" -> OPENAI
            "codex", "openai-codex" -> CODEX
            "gemini", "google", "google-gemini" -> GEMINI
            "claude", "anthropic" -> CLAUDE
            else -> throw BadRequestException("provider is not supported")
        }

    fun defaultBaseUrl(provider: String): String? =
        when (provider) {
            OPENAI, CODEX -> "https://api.openai.com"
            GEMINI -> "https://generativelanguage.googleapis.com"
            CLAUDE -> "https://api.anthropic.com"
            LOCAL -> null
            else -> null
        }

    fun isCanonicalLocalMock(entity: ProviderEntity): Boolean =
        entity.id == LOCAL_MOCK_ID && entity.provider == LOCAL
}

@Component
class ProviderBaseUrlValidator(
    private val properties: ProviderGenerationProperties,
) {
    fun normalizeAndValidate(rawBaseUrl: String): String {
        val uri =
            try {
                URI(rawBaseUrl.trim())
            } catch (_: Exception) {
                throw BadRequestException("baseUrl must be a valid URL")
            }

        if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) {
            throw BadRequestException("baseUrl must include scheme and host")
        }
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) {
            throw BadRequestException("baseUrl must not include credentials, query, or fragment")
        }

        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = uri.host.lowercase(Locale.ROOT)
        val addresses = resolveHost(host)
        val loopbackOnly = addresses.isNotEmpty() && addresses.all { it.isLoopbackAddress }
        if (scheme != "https" && !(scheme == "http" && properties.allowLoopbackBaseUrl && loopbackOnly)) {
            throw BadRequestException("baseUrl must use https")
        }
        if (!properties.allowLoopbackBaseUrl && addresses.any { it.isUnsafePrivateAddress() }) {
            throw BadRequestException("baseUrl host is not allowed")
        }
        if (properties.allowLoopbackBaseUrl && addresses.any { it.isUnsafePrivateAddress() && !it.isLoopbackAddress }) {
            throw BadRequestException("baseUrl host is not allowed")
        }

        return URI(
            scheme,
            null,
            host,
            uri.port,
            uri.path.trimEnd('/').ifBlank { null },
            null,
            null,
        ).toString().trimEnd('/')
    }

    private fun resolveHost(host: String): List<InetAddress> =
        try {
            InetAddress.getAllByName(host).toList()
        } catch (_: Exception) {
            throw BadRequestException("baseUrl host cannot be resolved")
        }

    private fun InetAddress.isUnsafePrivateAddress(): Boolean {
        if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) {
            return true
        }
        if (this is Inet4Address) {
            val octets = address.map { it.toInt() and 0xff }
            return octets[0] == 10 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 169 && octets[1] == 254) ||
                (octets[0] == 100 && octets[1] in 64..127)
        }
        if (this is Inet6Address) {
            val first = address[0].toInt() and 0xff
            return first in 0xfc..0xfd
        }
        return false
    }
}
