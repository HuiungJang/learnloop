package com.aicodelearning.ingest

import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.evidence.EvidenceService
import com.aicodelearning.evidence.ManualIngestRequest
import com.aicodelearning.evidence.SourceBundleResponse
import com.aicodelearning.evidence.toResponse
import com.aicodelearning.platform.BadRequestException
import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class CodexObsidianController(
    private val evidenceService: EvidenceService,
) {
    @PostMapping("/api/ingest/codex-obsidian")
    @ResponseStatus(HttpStatus.CREATED)
    fun ingest(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: CodexObsidianRequest,
    ): CodexObsidianResponse {
        val content = extractConversationText(request.exportData)
        val result =
            evidenceService.ingestManual(
                currentUser,
                ManualIngestRequest(
                    organizationId = request.organizationId,
                    teamId = request.teamId,
                    projectId = request.projectId,
                    title = request.exportData?.get("title")?.asText() ?: "Codex Obsidian Import",
                    sourceKind = "conversation",
                    provenance = mapOf("source" to "codex-obsidian-sync"),
                    content = content.ifBlank { "Empty conversation export" },
                ),
            )
        return CodexObsidianResponse(bundle = result.bundle.toResponse(), itemErrors = emptyList())
    }

    private fun extractConversationText(exportData: JsonNode?): String {
        if (exportData == null || exportData.get("schemaVersion")?.asInt() != 1) {
            throw BadRequestException("codex-obsidian export schemaVersion must be 1")
        }
        return exportData
            .get("conversations")
            ?.flatMap { conversation ->
                conversation.get("messages")?.map { message ->
                    "${message.get("role")?.asText() ?: "unknown"}: ${message.get("content")?.asText() ?: ""}"
                } ?: emptyList()
            }
            ?.joinToString("\n")
            .orEmpty()
    }
}

data class CodexObsidianRequest(
    @field:NotBlank
    val organizationId: String = "",
    val teamId: String? = null,
    val projectId: String? = null,
    val exportData: JsonNode? = null,
)

data class CodexObsidianResponse(
    val bundle: SourceBundleResponse,
    val itemErrors: List<String>,
)
