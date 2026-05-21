package com.aicodelearning.audit

import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.auth.sha256Hex
import com.aicodelearning.platform.prefixedId
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class AuditService(
    private val auditLogRepository: AuditLogRepository,
    private val objectMapper: ObjectMapper,
) {
    fun append(
        actor: CurrentUser,
        organizationId: String,
        eventType: String,
        targetType: String,
        targetId: String,
        metadata: Map<String, Any?> = emptyMap(),
    ): AuditLogEntity {
        val previous = auditLogRepository.findTopByOrganizationIdOrderByCreatedAtDescIdDesc(organizationId)
        val now = Instant.now()
        val metadataJson = objectMapper.writeValueAsString(sanitizeMetadata(metadata))
        val requestId = prefixedId("req")
        val eventHash =
            sha256Hex(
                listOf(
                    previous?.eventHash.orEmpty(),
                    actor.id,
                    organizationId,
                    eventType,
                    targetType,
                    targetId,
                    requestId,
                    metadataJson,
                    now.toString(),
                ).joinToString("|"),
            )

        return auditLogRepository.save(
            AuditLogEntity(
                id = prefixedId("audit"),
                actorUserId = actor.id,
                organizationId = organizationId,
                eventType = eventType,
                targetType = targetType,
                targetId = targetId,
                requestId = requestId,
                metadataJson = metadataJson,
                previousHash = previous?.eventHash,
                eventHash = eventHash,
                createdAt = now,
            ),
        )
    }

    fun sanitizeMetadataJson(metadataJson: String): String {
        val metadata =
            try {
                objectMapper.readValue(metadataJson, Map::class.java).mapKeys { it.key.toString() }
            } catch (_: Exception) {
                emptyMap()
            }
        return objectMapper.writeValueAsString(sanitizeMetadata(metadata))
    }

    private fun sanitizeMetadata(metadata: Map<String, Any?>): Map<String, Any?> =
        metadata
            .filterKeys { it in allowedMetadataKeys && !sensitiveKeyRegex.containsMatchIn(it) }
            .mapNotNull { (key, value) -> sanitizeValue(value)?.let { key to it.value } }
            .toMap()

    private fun sanitizeValue(value: Any?): SanitizedValue? =
        when (value) {
            null -> SanitizedValue(null)
            is String ->
                when {
                    unsafeMetadataValueRegex.containsMatchIn(value) -> null
                    sensitiveValueRegex.containsMatchIn(value) -> SanitizedValue("[redacted]")
                    else -> SanitizedValue(value.take(MAX_METADATA_STRING_CHARS))
                }
            is Number, is Boolean -> SanitizedValue(value)
            is Iterable<*> -> SanitizedValue(value.mapNotNull { sanitizeValue(it)?.value })
            is Array<*> -> SanitizedValue(value.mapNotNull { sanitizeValue(it)?.value })
            else -> sanitizeValue(value.toString())
        }

    private data class SanitizedValue(
        val value: Any?,
    )

    private companion object {
        const val MAX_METADATA_STRING_CHARS = 200
        val allowedMetadataKeys =
            setOf(
                "scope",
                "sourceKind",
                "status",
                "secretFindingTypes",
                "bundleId",
                "autoAttribution",
                "userAttribution",
                "attributionConfidence",
                "attributionReasons",
                "provider",
                "model",
                "authType",
                "retentionMode",
                "failureCode",
            )
        val sensitiveKeyRegex = Regex("credential|token|password|apiKey|rawContent|content|prompt|response|diff|stdout|stderr|path", RegexOption.IGNORE_CASE)
        val unsafeMetadataValueRegex = Regex("\\b(?:rawContent|content|prompt|response|diff|stdout|stderr|path)\\s*:", RegexOption.IGNORE_CASE)
        val sensitiveValueRegex =
            Regex(
                "sk-[A-Za-z0-9_-]{20,}|ghp_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----|\\b(?:api[_-]?key|password|secret|token)\\s*[:=]\\s*[\"']?[^\"'\\s]{8,}",
                RegexOption.IGNORE_CASE,
            )
    }
}
