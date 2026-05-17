package com.aicodelearning.evidence

import com.aicodelearning.auth.sha256Hex
import org.springframework.stereotype.Component

@Component
class SecretScanner {
    fun scan(content: String): List<SecretFinding> =
        secretPatterns.flatMap { (type, pattern) ->
            pattern
                .findAll(content)
                .map {
                    SecretFinding(
                        type = type,
                        index = it.range.first,
                        fingerprint = sha256Hex(it.value).take(16),
                    )
                }
        }

    private companion object {
        val secretPatterns =
            listOf(
                "openai_key" to Regex("\\bsk-[A-Za-z0-9_-]{20,}\\b"),
                "github_token" to Regex("\\bghp_[A-Za-z0-9_]{20,}\\b"),
                "aws_access_key" to Regex("\\bAKIA[0-9A-Z]{16}\\b"),
                "private_key" to Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
                "assigned_secret" to Regex("\\b(?:api[_-]?key|password|secret|token)\\s*[:=]\\s*[\"']?[^\"'\\s]{8,}", RegexOption.IGNORE_CASE),
            )
    }
}

data class SecretFinding(
    val type: String,
    val index: Int,
    val fingerprint: String,
)
