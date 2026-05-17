package com.aicodelearning.evidence

import com.aicodelearning.auth.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class EvidenceController(
    private val evidenceService: EvidenceService,
) {
    @PostMapping("/api/ingest/manual")
    @ResponseStatus(HttpStatus.CREATED)
    fun ingestManual(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: ManualIngestRequest,
    ): ManualIngestResponse {
        val result = evidenceService.ingestManual(currentUser, request)
        return ManualIngestResponse(bundle = result.bundle.toResponse(), evidenceItem = result.item.toResponse(includeContent = false))
    }

    @GetMapping("/api/evidence/{bundleId}")
    fun readEvidence(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable bundleId: String,
    ): EvidenceDetailResponse {
        val detail = evidenceService.readBundle(currentUser, bundleId)
        return EvidenceDetailResponse(
            bundle = detail.bundle.toResponse(),
            evidenceItems = detail.items.map { it.toResponse(includeContent = true) },
        )
    }
}

data class ManualIngestRequest(
    @field:NotBlank
    val organizationId: String = "",
    val teamId: String? = null,
    val projectId: String? = null,
    @field:NotBlank
    val title: String = "",
    val sourceKind: String = "code",
    val repositoryUrl: String? = null,
    val pullRequestUrl: String? = null,
    val commitSha: String? = null,
    val branchName: String? = null,
    val filePaths: List<String> = emptyList(),
    val provenance: Map<String, String> = emptyMap(),
    @field:Size(max = 20_000)
    @field:NotBlank
    val content: String = "",
)

data class ManualIngestResponse(
    val bundle: SourceBundleResponse,
    val evidenceItem: EvidenceItemResponse,
)

data class EvidenceDetailResponse(
    val bundle: SourceBundleResponse,
    val evidenceItems: List<EvidenceItemResponse>,
)

data class SourceBundleResponse(
    val id: String,
    val organizationId: String,
    val teamId: String?,
    val projectId: String?,
    val title: String,
    val sourceKind: String,
    val status: String,
    val repositoryUrl: String?,
    val pullRequestUrl: String?,
    val commitSha: String?,
    val branchName: String?,
    val filePathsJson: String,
    val provenanceJson: String,
    val contentHash: String,
    val secretFindingsJson: String,
    val createdAt: Instant,
)

data class EvidenceItemResponse(
    val id: String,
    val bundleId: String,
    val itemType: String,
    val contentText: String?,
    val contentHash: String,
    val createdAt: Instant,
)

private fun SourceBundleEntity.toResponse(): SourceBundleResponse =
    SourceBundleResponse(
        id = id,
        organizationId = organizationId,
        teamId = teamId,
        projectId = projectId,
        title = title,
        sourceKind = sourceKind,
        status = status,
        repositoryUrl = repositoryUrl,
        pullRequestUrl = pullRequestUrl,
        commitSha = commitSha,
        branchName = branchName,
        filePathsJson = filePathsJson,
        provenanceJson = provenanceJson,
        contentHash = contentHash,
        secretFindingsJson = secretFindingsJson,
        createdAt = createdAt,
    )

private fun EvidenceItemEntity.toResponse(includeContent: Boolean): EvidenceItemResponse =
    EvidenceItemResponse(
        id = id,
        bundleId = bundleId,
        itemType = itemType,
        contentText = if (includeContent) contentText else null,
        contentHash = contentHash,
        createdAt = createdAt,
    )
