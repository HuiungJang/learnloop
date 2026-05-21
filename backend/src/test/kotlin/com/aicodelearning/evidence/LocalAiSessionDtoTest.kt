package com.aicodelearning.evidence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class LocalAiSessionDtoTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `local AI session request serializes full artifact contract`() {
        val request =
            LocalAiSessionIngestRequest(
                organizationId = "org-demo",
                teamId = "team-platform",
                projectId = "project-learning",
                title = "Codex session",
                repoIdentityHash = "repo-identity-abc123",
                repositoryDisplayLabel = "example/project",
                repositoryUrl = "file:///Users/example/project",
                commitSha = "0123456789abcdef",
                branchName = "feature/local-session",
                toolProvider = "codex-cli",
                toolSessionId = "session-123",
                toolEventId = "event-456",
                timestampBucket = "2026-05-19T08:00Z",
                idempotencyKey = "repo-identity-abc123:event-456",
                attributionConfidence = BigDecimal("0.92000"),
                attributionReasons = listOf("tool_session", "changed_files"),
                artifacts =
                    listOf(
                        artifact("prompt", content = "Refactor this service"),
                        artifact("ai_response", content = "I changed the service"),
                        artifact("file_before", path = "src/service.ts", content = "export const oldValue = 1"),
                        artifact("file_after", path = "src/service.ts", content = "export const newValue = 2"),
                        artifact("diff", path = "src/service.ts", content = "-oldValue\n+newValue"),
                        artifact("tool_event", metadata = mapOf("event" to "command_exit", "exitCode" to "0"), content = null, limitReason = "stdout_truncated"),
                    ),
            )

        val json = objectMapper.writeValueAsString(request)
        val decoded = objectMapper.readValue<LocalAiSessionIngestRequest>(json)

        assertTrue(json.contains("local_ai_session"))
        assertEquals("local_ai_session", decoded.sourceKind)
        assertEquals("repo-identity-abc123", decoded.repoIdentityHash)
        assertEquals("example/project", decoded.repositoryDisplayLabel)
        assertEquals("codex-cli", decoded.toolProvider)
        assertEquals("event-456", decoded.toolEventId)
        assertEquals("2026-05-19T08:00Z", decoded.timestampBucket)
        assertEquals("repo-identity-abc123:event-456", decoded.idempotencyKey)
        assertEquals(0, requireNotNull(decoded.attributionConfidence).compareTo(BigDecimal("0.92")))
        assertEquals(
            listOf("prompt", "ai_response", "file_before", "file_after", "diff", "tool_event"),
            decoded.artifacts.map { it.itemType },
        )
        assertEquals("src/service.ts", decoded.artifacts[2].repoRelativePath)
        assertEquals(false, decoded.artifacts[2].contentTruncated)
        assertEquals("command_exit", decoded.artifacts.last().metadata["event"])
        assertEquals("stdout_truncated", decoded.artifacts.last().limitReason)
    }

    @Test
    fun `local session evidence response can bound raw content excerpts`() {
        val response =
            EvidenceItemEntity(
                id = "evidence-local-session",
                bundleId = "bundle-local-session",
                itemType = "prompt",
                contentText = "x".repeat(2_100),
                contentHash = "a".repeat(64),
            ).toResponse(includeContent = true, contentLimit = 2_000)

        assertEquals(2_000, response.contentText?.length)
    }

    private fun artifact(
        itemType: String,
        path: String? = null,
        metadata: Map<String, String> = emptyMap(),
        limitReason: String? = null,
        content: String?,
    ): LocalAiSessionArtifactRequest =
        LocalAiSessionArtifactRequest(
            itemType = itemType,
            repoRelativePath = path,
            sizeBytes = content?.length?.toLong() ?: 0,
            metadata = metadata,
            contentHash = itemType.padEnd(64, '0').take(64),
            contentTruncated = false,
            limitReason = limitReason,
            content = content,
        )
}
