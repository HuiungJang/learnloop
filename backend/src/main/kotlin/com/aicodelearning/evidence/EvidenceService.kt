package com.aicodelearning.evidence

import com.aicodelearning.audit.AuditService
import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.auth.sha256Hex
import com.aicodelearning.organization.AuthorizationService
import com.aicodelearning.platform.BadRequestException
import com.aicodelearning.platform.NotFoundException
import com.aicodelearning.platform.prefixedId
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class EvidenceService(
    private val sourceBundleRepository: SourceBundleRepository,
    private val evidenceItemRepository: EvidenceItemRepository,
    private val authorizationService: AuthorizationService,
    private val secretScanner: SecretScanner,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun ingestManual(
        currentUser: CurrentUser,
        request: ManualIngestRequest,
    ): ManualIngestResult {
        val sourceKind = request.sourceKind.ifBlank { "code" }
        if (sourceKind !in allowedSourceKinds) {
            throw BadRequestException("sourceKind is not supported")
        }

        authorizationService.requireRole(currentUser, request.organizationId, "contributor", request.teamId, request.projectId)

        val findings = secretScanner.scan(request.content)
        val status = if (findings.isEmpty()) "ready" else "blocked_sensitive"
        val contentHash = sha256Hex(request.content)
        val existing = sourceBundleRepository.findFirstByOrganizationIdAndSourceKindAndContentHashOrderByCreatedAtDesc(
            request.organizationId,
            sourceKind,
            contentHash,
        )
        if (existing != null) {
            return ManualIngestResult(bundle = existing, item = evidenceItemRepository.findByBundleId(existing.id).first())
        }

        val now = Instant.now()

        val bundle =
            sourceBundleRepository.save(
                SourceBundleEntity(
                    id = prefixedId("bundle"),
                    organizationId = request.organizationId,
                    teamId = request.teamId,
                    projectId = request.projectId,
                    createdByUserId = currentUser.id,
                    title = request.title,
                    sourceKind = sourceKind,
                    status = status,
                    repositoryUrl = request.repositoryUrl,
                    pullRequestUrl = request.pullRequestUrl,
                    commitSha = request.commitSha,
                    branchName = request.branchName,
                    filePathsJson = objectMapper.writeValueAsString(request.filePaths),
                    provenanceJson = objectMapper.writeValueAsString(request.provenance),
                    contentHash = contentHash,
                    secretFindingsJson = objectMapper.writeValueAsString(findings),
                    createdAt = now,
                ),
            )

        val item =
            evidenceItemRepository.save(
                EvidenceItemEntity(
                    id = prefixedId("evidence"),
                    bundleId = bundle.id,
                    itemType = sourceKind,
                    contentText = if (findings.isEmpty()) request.content else null,
                    contentHash = contentHash,
                    createdAt = now,
                ),
            )

        auditService.append(
            actor = currentUser,
            organizationId = request.organizationId,
            eventType = "evidence.manual_ingested",
            targetType = "source_bundle",
            targetId = bundle.id,
            metadata =
                mapOf(
                    "sourceKind" to sourceKind,
                    "status" to status,
                    "secretFindingTypes" to findings.map { it.type },
                ),
        )

        return ManualIngestResult(bundle = bundle, item = item)
    }

    @Transactional(readOnly = true)
    fun readBundle(
        currentUser: CurrentUser,
        bundleId: String,
    ): EvidenceDetail {
        val bundle = sourceBundleRepository.findById(bundleId).orElseThrow { NotFoundException("Evidence bundle not found") }
        authorizationService.requireRole(currentUser, bundle.organizationId, "contributor", bundle.teamId, bundle.projectId)
        return EvidenceDetail(bundle = bundle, items = evidenceItemRepository.findByBundleId(bundle.id))
    }

    private companion object {
        val allowedSourceKinds = setOf("code", "diff", "commit", "pull_request", "conversation", "supporting_context")
    }
}

data class ManualIngestResult(
    val bundle: SourceBundleEntity,
    val item: EvidenceItemEntity,
)

data class EvidenceDetail(
    val bundle: SourceBundleEntity,
    val items: List<EvidenceItemEntity>,
)
