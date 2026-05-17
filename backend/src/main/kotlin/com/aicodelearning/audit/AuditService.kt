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
        val metadataJson = objectMapper.writeValueAsString(redact(metadata))
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

    private fun redact(metadata: Map<String, Any?>): Map<String, Any?> =
        metadata.mapValues { (key, value) ->
            if (sensitiveKeyRegex.containsMatchIn(key)) {
                "[redacted]"
            } else {
                value
            }
        }

    private companion object {
        val sensitiveKeyRegex = Regex("credential|token|password|apiKey|rawContent|content", RegexOption.IGNORE_CASE)
    }
}
