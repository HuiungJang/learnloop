package com.aicodelearning.evidence

import com.aicodelearning.audit.AuditService
import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.auth.sha256Hex
import com.aicodelearning.organization.AuthorizationService
import com.aicodelearning.platform.BadRequestException
import com.aicodelearning.platform.LocalOwnerAccess
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
    private val localOwnerAccess: LocalOwnerAccess,
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
        val existing = sourceBundleRepository.findFirstByOrganizationIdAndSourceKindAndContentHashAndDeletedAtIsNullOrderByCreatedAtDesc(
            request.organizationId,
            sourceKind,
            contentHash,
        )
        if (existing != null) {
            val existingItems = evidenceItemRepository.findByBundleId(existing.id)
            if (existingItems.isNotEmpty() && existingItems.none { it.rawPurgedAt != null }) {
                return ManualIngestResult(bundle = existing, item = existingItems.first())
            }
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
        if (bundle.deletedAt != null) {
            throw NotFoundException("Evidence bundle not found")
        }
        authorizationService.requireRole(currentUser, bundle.organizationId, "contributor", bundle.teamId, bundle.projectId)
        return EvidenceDetail(bundle = bundle, items = evidenceItemRepository.findByBundleId(bundle.id))
    }

    @Transactional
    fun deleteBundle(
        currentUser: CurrentUser,
        bundleId: String,
    ) {
        val bundle = sourceBundleRepository.findById(bundleId).orElseThrow { NotFoundException("Evidence bundle not found") }
        localOwnerAccess.requireLocalOwner(currentUser)
        authorizationService.requireRole(currentUser, bundle.organizationId, "contributor", bundle.teamId, bundle.projectId)
        if (bundle.deletedAt != null) {
            return
        }

        bundle.deletedAt = Instant.now()
        bundle.deletedByUserId = currentUser.id
        bundle.deletionReason = "local_owner_delete"
        auditService.append(currentUser, bundle.organizationId, "evidence.deleted", "source_bundle", bundle.id, mapOf("status" to "deleted"))
    }

    @Transactional
    fun purgeBundleRaw(
        currentUser: CurrentUser,
        bundleId: String,
    ): RawPurgeResponse {
        val bundle = sourceBundleRepository.findById(bundleId).orElseThrow { NotFoundException("Evidence bundle not found") }
        localOwnerAccess.requireLocalOwner(currentUser)
        authorizationService.requireRole(currentUser, bundle.organizationId, "contributor", bundle.teamId, bundle.projectId)
        return purgeRawForBundles(currentUser, listOf(bundle), BUNDLE_RAW_PURGE_REASON)
    }

    @Transactional
    fun purgeRaw(
        currentUser: CurrentUser,
        organizationId: String,
        repositoryUrl: String?,
        purgeAll: Boolean,
    ): RawPurgeResponse {
        localOwnerAccess.requireLocalOwner(currentUser)
        authorizationService.requireOrganizationMember(currentUser, organizationId, "contributor")
        if ((repositoryUrl.isNullOrBlank() && !purgeAll) || (!repositoryUrl.isNullOrBlank() && purgeAll)) {
            throw BadRequestException("Choose either repositoryUrl or purgeAll")
        }

        val bundles =
            if (!repositoryUrl.isNullOrBlank()) {
                sourceBundleRepository.findByOrganizationIdAndRepositoryUrl(organizationId, repositoryUrl)
            } else {
                sourceBundleRepository.findByOrganizationId(organizationId)
            }
        return purgeRawForBundles(currentUser, bundles, if (purgeAll) ALL_RAW_PURGE_REASON else REPOSITORY_RAW_PURGE_REASON)
    }

    private fun purgeRawForBundles(
        currentUser: CurrentUser,
        bundles: List<SourceBundleEntity>,
        reason: String,
    ): RawPurgeResponse {
        if (bundles.isEmpty()) {
            return RawPurgeResponse(purgedBundles = 0, purgedItems = 0)
        }
        bundles.forEach {
            authorizationService.requireRole(currentUser, it.organizationId, "contributor", it.teamId, it.projectId)
        }

        val now = Instant.now()
        val items = evidenceItemRepository.findByBundleIdIn(bundles.map { it.id })
        val changedItems =
            items.filter {
                it.contentText != null || it.rawPurgedAt == null
            }
        changedItems.forEach {
            it.contentText = null
            if (it.rawPurgedAt == null) {
                it.rawPurgedAt = now
            }
            if (it.rawPurgeReason == null) {
                it.rawPurgeReason = reason
            }
        }
        val bundlesWithRawMetadata =
            bundles.filter {
                it.filePathsJson != EMPTY_FILE_PATHS_JSON || it.provenanceJson != EMPTY_PROVENANCE_JSON
            }
        bundlesWithRawMetadata.forEach {
            it.filePathsJson = EMPTY_FILE_PATHS_JSON
            it.provenanceJson = EMPTY_PROVENANCE_JSON
        }
        val changedBundleIds = (changedItems.map { it.bundleId } + bundlesWithRawMetadata.map { it.id }).toSet()
        bundles.filter { it.id in changedBundleIds }.forEach {
            auditService.append(currentUser, it.organizationId, "evidence.raw_purged", "source_bundle", it.id, mapOf("status" to "raw_purged"))
        }
        return RawPurgeResponse(purgedBundles = changedBundleIds.size, purgedItems = changedItems.size)
    }

    private companion object {
        val allowedSourceKinds = setOf("code", "diff", "commit", "pull_request", "conversation", "supporting_context")
        const val BUNDLE_RAW_PURGE_REASON = "local_owner_bundle_raw_purge"
        const val REPOSITORY_RAW_PURGE_REASON = "local_owner_repository_raw_purge"
        const val ALL_RAW_PURGE_REASON = "local_owner_all_raw_purge"
        const val EMPTY_FILE_PATHS_JSON = "[]"
        const val EMPTY_PROVENANCE_JSON = "{}"
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
