package com.aicodelearning.evidence

import com.aicodelearning.audit.AuditService
import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.auth.sha256Hex
import com.aicodelearning.organization.AuthorizationService
import com.aicodelearning.organization.MembershipSummary
import com.aicodelearning.platform.BadRequestException
import com.aicodelearning.platform.ForbiddenException
import com.aicodelearning.platform.LocalOwnerAccess
import com.aicodelearning.platform.NotFoundException
import com.aicodelearning.platform.prefixedId
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.net.URI
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Service
class EvidenceService(
    private val sourceBundleRepository: SourceBundleRepository,
    private val evidenceItemRepository: EvidenceItemRepository,
    private val sourceBundleAttributionEventRepository: SourceBundleAttributionEventRepository,
    private val localRepositoryConsentRepository: LocalRepositoryConsentRepository,
    private val authorizationService: AuthorizationService,
    private val localOwnerAccess: LocalOwnerAccess,
    private val secretScanner: SecretScanner,
    private val localSessionArtifactPreflight: LocalSessionArtifactPreflight,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val transactionManager: PlatformTransactionManager,
) {
    private val localSessionIngestLocks = ConcurrentHashMap<String, Any>()

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

    fun ingestLocalAiSession(
        currentUser: CurrentUser,
        request: LocalAiSessionIngestRequest,
    ): LocalAiSessionIngestResult {
        if (request.sourceKind != LocalAiSessionPolicy.SOURCE_KIND) {
            throw BadRequestException("sourceKind must be local_ai_session")
        }
        localOwnerAccess.requireLocalOwner(currentUser)
        authorizationService.requireRole(currentUser, request.organizationId, "contributor", request.teamId, request.projectId)
        requireApprovedLocalRepository(request)

        val repoRoot = approvedRepoRoot(request)
        val preflight = localSessionArtifactPreflight.validate(request, repoRoot)
        if (preflight.artifacts.isEmpty()) {
            throw BadRequestException("local session has no storable artifacts")
        }
        val dedupeKey = localSessionDedupeKey(request, preflight)
        val lock = localSessionIngestLocks.computeIfAbsent(dedupeKey) { Any() }

        try {
            synchronized(lock) {
                return TransactionTemplate(transactionManager).execute {
                    findLocalSessionDuplicate(request.organizationId, dedupeKey, preflight)
                        ?: persistLocalAiSession(currentUser, request, preflight, dedupeKey)
                } ?: throw IllegalStateException("local session ingest transaction did not return a result")
            }
        } catch (ex: DataIntegrityViolationException) {
            return findLocalSessionDuplicateAfterConflict(request.organizationId, dedupeKey, preflight) ?: throw ex
        } finally {
            localSessionIngestLocks.remove(dedupeKey, lock)
        }
    }

    @Transactional(readOnly = true)
    fun listBundles(
        currentUser: CurrentUser,
        organizationId: String,
        page: Int,
        pageSize: Int,
    ): EvidenceList {
        val visibleScope = visibleBundleSpecification(currentUser, organizationId, "contributor")
        val normalizedPage = page.coerceAtLeast(0)
        val normalizedPageSize = pageSize.coerceIn(1, MAX_EVIDENCE_LIST_PAGE_SIZE)
        val result =
            sourceBundleRepository.findAll(
                visibleScope,
                PageRequest.of(
                    normalizedPage,
                    normalizedPageSize,
                    Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")),
                ),
            )
        return EvidenceList(
            bundles = result.content,
            page = normalizedPage,
            pageSize = normalizedPageSize,
            total = result.totalElements,
        )
    }

    @Transactional(readOnly = true)
    fun listLocalRepositoryConsents(
        currentUser: CurrentUser,
        organizationId: String,
    ): List<LocalRepositoryConsentEntity> {
        localOwnerAccess.requireLocalOwner(currentUser)
        authorizationService.requireOrganizationMember(currentUser, organizationId, "contributor")
        return localRepositoryConsentRepository.findByOrganizationIdOrderByUpdatedAtDesc(organizationId)
    }

    @Transactional
    fun updateLocalRepositoryConsent(
        currentUser: CurrentUser,
        repoIdentityHash: String,
        request: LocalRepositoryConsentRequest,
    ): LocalRepositoryConsentEntity {
        localOwnerAccess.requireLocalOwner(currentUser)
        authorizationService.requireOrganizationMember(currentUser, request.organizationId, "contributor")
        val normalizedHash = normalizeRepoIdentityHash(repoIdentityHash)
        val normalizedLabel = request.displayLabel.trim().takeIf { it.isNotBlank() } ?: throw BadRequestException("displayLabel is required")
        if (normalizedLabel.length > MAX_REPOSITORY_DISPLAY_LABEL_LENGTH) {
            throw BadRequestException("displayLabel is too long")
        }
        val normalizedStatus = normalizeRepositoryConsentStatus(request.status)
        val now = Instant.now()
        val consent =
            localRepositoryConsentRepository.findByOrganizationIdAndRepoIdentityHash(request.organizationId, normalizedHash)
                ?: LocalRepositoryConsentEntity(
                    id = prefixedId("repo_consent"),
                    organizationId = request.organizationId,
                    repoIdentityHash = normalizedHash,
                    createdByUserId = currentUser.id,
                    createdAt = now,
                )
        consent.displayLabel = normalizedLabel
        consent.status = normalizedStatus
        consent.updatedAt = now
        return localRepositoryConsentRepository.save(consent)
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
        val items = evidenceItemRepository.findByBundleId(bundle.id)
        return EvidenceDetail(
            bundle = bundle,
            items = if (bundle.sourceKind == LocalAiSessionPolicy.SOURCE_KIND) items.stableLocalSessionItemOrder() else items,
        )
    }

    @Transactional
    fun deleteBundle(
        currentUser: CurrentUser,
        bundleId: String,
    ) {
        val bundle = sourceBundleRepository.findForUpdateById(bundleId) ?: throw NotFoundException("Evidence bundle not found")
        localOwnerAccess.requireLocalOwner(currentUser)
        authorizationService.requireRole(currentUser, bundle.organizationId, "contributor", bundle.teamId, bundle.projectId)
        if (bundle.deletedAt != null) {
            return
        }
        markBundleDeleted(currentUser, bundle)
    }

    @Transactional
    fun updateAttribution(
        currentUser: CurrentUser,
        bundleId: String,
        request: AttributionOverrideRequest,
    ): SourceBundleEntity {
        val bundle = sourceBundleRepository.findForUpdateById(bundleId) ?: throw NotFoundException("Evidence bundle not found")
        if (bundle.deletedAt != null) {
            throw NotFoundException("Evidence bundle not found")
        }
        if (bundle.sourceKind != LocalAiSessionPolicy.SOURCE_KIND) {
            throw BadRequestException("attribution override supports local AI session evidence only")
        }
        localOwnerAccess.requireLocalOwner(currentUser)
        authorizationService.requireRole(currentUser, bundle.organizationId, "contributor", bundle.teamId, bundle.projectId)

        val userAttribution = requireAllowedUserAttribution(request.userAttribution)
        val overrideConfidence = requireValidConfidence(request.attributionConfidence)
        val overrideReasons = safeAttributionReasons(request.attributionReasons)
        val overrideReasonsJson = objectMapper.writeValueAsString(overrideReasons)
        val now = Instant.now()
        requireValidAttributionState(bundle, userAttribution)

        bundle.userAttribution = userAttribution

        sourceBundleAttributionEventRepository.save(
            SourceBundleAttributionEventEntity(
                id = prefixedId("attr_event"),
                bundleId = bundle.id,
                organizationId = bundle.organizationId,
                actorUserId = currentUser.id,
                eventType = "user_override",
                autoAttribution = bundle.autoAttribution,
                userAttribution = bundle.userAttribution,
                attributionConfidence = overrideConfidence,
                attributionReasonsJson = overrideReasonsJson,
                createdAt = now,
            ),
        )
        auditService.append(
            actor = currentUser,
            organizationId = bundle.organizationId,
            eventType = "evidence.attribution_overridden",
            targetType = "source_bundle",
            targetId = bundle.id,
            metadata =
                mapOf(
                    "bundleId" to bundle.id,
                    "status" to bundle.status,
                    "autoAttribution" to bundle.autoAttribution,
                    "userAttribution" to userAttribution,
                    "attributionConfidence" to overrideConfidence,
                    "attributionReasons" to overrideReasons,
                ),
        )
        if (userAttribution == LocalAiSessionPolicy.USER_ATTRIBUTION_DELETE) {
            markBundleDeleted(currentUser, bundle)
        }

        return bundle
    }

    @Transactional
    fun purgeBundleRaw(
        currentUser: CurrentUser,
        bundleId: String,
    ): RawPurgeResponse {
        val bundle = sourceBundleRepository.findForUpdateById(bundleId) ?: throw NotFoundException("Evidence bundle not found")
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
        return purgeRawForBundles(currentUser, sourceBundleRepository.findExistingForUpdateSortedById(bundles.map { it.id }), if (purgeAll) ALL_RAW_PURGE_REASON else REPOSITORY_RAW_PURGE_REASON)
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
        val bundlesWithClearedDedupe =
            bundles.filter {
                it.dedupeKey != null && changedItems.any { item -> item.bundleId == it.id }
            }
        bundlesWithClearedDedupe.forEach {
            it.dedupeKey = null
        }
        val changedBundleIds = (changedItems.map { it.bundleId } + bundlesWithRawMetadata.map { it.id } + bundlesWithClearedDedupe.map { it.id }).toSet()
        bundles.filter { it.id in changedBundleIds }.forEach {
            auditService.append(currentUser, it.organizationId, "evidence.raw_purged", "source_bundle", it.id, mapOf("status" to "raw_purged"))
        }
        return RawPurgeResponse(purgedBundles = changedBundleIds.size, purgedItems = changedItems.size)
    }

    private fun markBundleDeleted(
        currentUser: CurrentUser,
        bundle: SourceBundleEntity,
    ) {
        if (bundle.deletedAt != null) {
            return
        }
        bundle.deletedAt = Instant.now()
        bundle.deletedByUserId = currentUser.id
        bundle.deletionReason = "local_owner_delete"
        auditService.append(currentUser, bundle.organizationId, "evidence.deleted", "source_bundle", bundle.id, mapOf("status" to "deleted"))
    }

    private fun persistLocalAiSession(
        currentUser: CurrentUser,
        request: LocalAiSessionIngestRequest,
        preflight: LocalSessionPreflightResult,
        dedupeKey: String,
    ): LocalAiSessionIngestResult {
        val now = Instant.now()
        val autoAttribution = requireAllowedAutoAttribution(request.autoAttribution)
        val status =
            when {
                preflight.generationEligible -> LocalAiSessionPolicy.STATUS_GENERATION_ELIGIBLE
                autoAttribution == LocalAiSessionPolicy.AUTO_ATTRIBUTION_GUI_CORRELATED -> LocalAiSessionPolicy.STATUS_USER_CONFIRMATION_REQUIRED
                else -> preflight.status
            }
        val findings = preflight.secretFindings
        val bundle =
            sourceBundleRepository.save(
                SourceBundleEntity(
                    id = prefixedId("bundle"),
                    organizationId = request.organizationId,
                    teamId = request.teamId,
                    projectId = request.projectId,
                    createdByUserId = currentUser.id,
                    title = safeLocalSessionTitle(request),
                    sourceKind = LocalAiSessionPolicy.SOURCE_KIND,
                    status = status,
                    repositoryUrl = localRepositoryReference(request),
                    commitSha = safeCommitSha(request.commitSha),
                    branchName = safeBranchName(request.branchName),
                    filePathsJson = objectMapper.writeValueAsString(preflight.artifacts.mapNotNull { it.repoRelativePath }.distinct()),
                    provenanceJson = objectMapper.writeValueAsString(localSessionProvenance(request)),
                    contentHash = localSessionBundleHash(dedupeKey, preflight),
                    secretFindingsJson = objectMapper.writeValueAsString(findings),
                    createdAt = now,
                    autoAttribution = autoAttribution,
                    attributionConfidence = requireValidConfidence(request.attributionConfidence),
                    attributionReasonsJson = objectMapper.writeValueAsString(safeAttributionReasons(request.attributionReasons)),
                    dedupeKey = dedupeKey,
                ),
            )
        val items =
            evidenceItemRepository.saveAll(
                preflight.artifacts.map { artifact ->
                    EvidenceItemEntity(
                        id = prefixedId("evidence"),
                        bundleId = bundle.id,
                        itemType = artifact.itemType,
                        contentText = artifact.contentText,
                        contentHash = artifact.contentHash,
                        createdAt = now,
                        repoRelativePath = artifact.repoRelativePath,
                        sizeBytes = artifact.sizeBytes,
                        metadataJson = objectMapper.writeValueAsString(artifact.metadata),
                        contentTruncated = artifact.contentTruncated,
                        limitReason = artifact.limitReason,
                    )
                },
            ).toList()

        sourceBundleAttributionEventRepository.save(
            SourceBundleAttributionEventEntity(
                id = prefixedId("attr_event"),
                bundleId = bundle.id,
                organizationId = bundle.organizationId,
                actorUserId = currentUser.id,
                eventType = "auto_detected",
                autoAttribution = bundle.autoAttribution,
                attributionConfidence = bundle.attributionConfidence,
                attributionReasonsJson = bundle.attributionReasonsJson,
                createdAt = now,
            ),
        )
        auditService.append(
            actor = currentUser,
            organizationId = bundle.organizationId,
            eventType = "evidence.local_session_ingested",
            targetType = "source_bundle",
            targetId = bundle.id,
            metadata =
                mapOf(
                    "sourceKind" to bundle.sourceKind,
                    "status" to bundle.status,
                    "secretFindingTypes" to findings.map { it.finding.type },
                ),
        )

        return LocalAiSessionIngestResult(
            bundle = bundle,
            items = items.stableLocalSessionItemOrder(),
            ignoredArtifacts = preflight.ignoredArtifacts,
            duplicate = false,
        )
    }

    private fun findLocalSessionDuplicate(
        organizationId: String,
        dedupeKey: String,
        preflight: LocalSessionPreflightResult,
    ): LocalAiSessionIngestResult? =
        sourceBundleRepository
            .findFirstByOrganizationIdAndSourceKindAndDedupeKeyAndDeletedAtIsNullOrderByCreatedAtDesc(
                organizationId,
                LocalAiSessionPolicy.SOURCE_KIND,
                dedupeKey,
            )?.let {
                LocalAiSessionIngestResult(
                    bundle = it,
                    items = evidenceItemRepository.findByBundleId(it.id).stableLocalSessionItemOrder(),
                    ignoredArtifacts = preflight.ignoredArtifacts,
                    duplicate = true,
                )
            }

    private fun findLocalSessionDuplicateAfterConflict(
        organizationId: String,
        dedupeKey: String,
        preflight: LocalSessionPreflightResult,
    ): LocalAiSessionIngestResult? =
        TransactionTemplate(transactionManager).execute {
            findLocalSessionDuplicate(organizationId, dedupeKey, preflight)
        }

    private fun approvedRepoRoot(request: LocalAiSessionIngestRequest): Path {
        val repositoryUrl = request.repositoryUrl?.trim().orEmpty()
        if (repositoryUrl.isBlank()) {
            throw BadRequestException("repositoryUrl is required for local session ingestion")
        }
        val uri =
            try {
                URI(repositoryUrl)
            } catch (_: Exception) {
                throw BadRequestException("repositoryUrl must be a valid file URI")
            }
        if (uri.scheme != "file") {
            throw BadRequestException("repositoryUrl must be a file URI")
        }
        return try {
            Path.of(uri).toRealPath()
        } catch (_: Exception) {
            throw BadRequestException("repositoryUrl must point to an approved local repository")
        }
    }

    private fun requireApprovedLocalRepository(request: LocalAiSessionIngestRequest) {
        val normalizedHash = normalizeRepoIdentityHash(request.repoIdentityHash)
        val consent = localRepositoryConsentRepository.findByOrganizationIdAndRepoIdentityHash(request.organizationId, normalizedHash)
        if (consent?.status != REPOSITORY_CONSENT_APPROVED) {
            throw BadRequestException("local repository must be approved before ingestion")
        }
    }

    private fun visibleBundleSpecification(
        currentUser: CurrentUser,
        organizationId: String,
        role: String,
    ): Specification<SourceBundleEntity> {
        val memberships =
            currentUser.memberships.filter {
                it.organizationId == organizationId && roleMeetsRequired(it.role, role)
            }
        if (memberships.isEmpty()) {
            throw ForbiddenException("Not allowed for this organization scope")
        }
        val hasOrganizationWideAccess = memberships.any { it.role == "admin" || (it.teamId == null && it.projectId == null) }

        return Specification { root, _, criteriaBuilder ->
            val base =
                criteriaBuilder.and(
                    criteriaBuilder.equal(root.get<String>("organizationId"), organizationId),
                    criteriaBuilder.isNull(root.get<Instant>("deletedAt")),
                )
            if (hasOrganizationWideAccess) {
                base
            } else {
                val scopePredicates = memberships.map { scopedBundlePredicate(it, root, criteriaBuilder) }
                criteriaBuilder.and(base, criteriaBuilder.or(*scopePredicates.toTypedArray()))
            }
        }
    }

    private fun scopedBundlePredicate(
        membership: MembershipSummary,
        root: Root<SourceBundleEntity>,
        criteriaBuilder: CriteriaBuilder,
    ): Predicate =
        when {
            membership.teamId != null && membership.projectId != null ->
                criteriaBuilder.and(
                    criteriaBuilder.equal(root.get<String>("teamId"), membership.teamId),
                    criteriaBuilder.equal(root.get<String>("projectId"), membership.projectId),
                )
            membership.teamId != null ->
                criteriaBuilder.equal(root.get<String>("teamId"), membership.teamId)
            membership.projectId != null ->
                criteriaBuilder.equal(root.get<String>("projectId"), membership.projectId)
            else ->
                criteriaBuilder.disjunction()
        }

    private fun roleMeetsRequired(
        actualRole: String,
        requiredRole: String,
    ): Boolean {
        val actual = ROLE_ORDER.indexOf(actualRole)
        val required = ROLE_ORDER.indexOf(requiredRole)
        return actual >= 0 && required >= 0 && actual >= required
    }

    private fun normalizeRepoIdentityHash(value: String): String {
        val normalized = value.trim()
        if (!REPO_IDENTITY_HASH_PATTERN.matches(normalized)) {
            throw BadRequestException("repoIdentityHash is invalid")
        }
        return normalized
    }

    private fun normalizeRepositoryConsentStatus(value: String): String {
        val normalized = value.trim()
        if (normalized !in REPOSITORY_CONSENT_STATUSES) {
            throw BadRequestException("repository consent status is invalid")
        }
        return normalized
    }

    private fun localSessionDedupeKey(
        request: LocalAiSessionIngestRequest,
        preflight: LocalSessionPreflightResult,
    ): String {
        val eventKey =
            request.toolEventId?.trim()?.takeIf { it.isNotBlank() }
                ?: request.timestampBucket?.trim()?.takeIf { it.isNotBlank() }
                ?: request.idempotencyKey.trim().takeIf { it.isNotBlank() }
                ?: throw BadRequestException("local session idempotency key is required")
        val artifactPart =
            preflight.artifacts
                .sortedWith(compareBy({ it.itemType }, { it.repoRelativePath.orEmpty() }, { it.contentHash }))
                .joinToString("\n") {
                    listOf(it.itemType, it.repoRelativePath.orEmpty(), it.contentHash).joinToString(":")
                }
        val ignoredPart =
            preflight.ignoredArtifacts
                .sortedWith(compareBy({ it.itemType }, { it.repoRelativePath.orEmpty() }, { it.contentHash }, { it.reason }))
                .joinToString("\n") {
                    listOf(it.itemType, it.repoRelativePath.orEmpty(), it.contentHash, it.reason).joinToString(":")
                }
        val findingPart =
            preflight.secretFindings
                .sortedWith(compareBy({ it.itemType }, { it.repoRelativePath.orEmpty() }, { it.finding.type }, { it.finding.fingerprint }))
                .joinToString("\n") {
                    listOf(it.itemType, it.repoRelativePath.orEmpty(), it.finding.type, it.finding.fingerprint).joinToString(":")
                }
        return sha256Hex(
            listOf(
                LocalAiSessionPolicy.SOURCE_KIND,
                request.repoIdentityHash.trim(),
                eventKey,
                preflight.status,
                preflight.generationEligible.toString(),
                artifactPart,
                ignoredPart,
                findingPart,
            ).joinToString("\n"),
        )
    }

    private fun localSessionBundleHash(
        dedupeKey: String,
        preflight: LocalSessionPreflightResult,
    ): String =
        sha256Hex(
            (listOf(dedupeKey) + preflight.artifacts.map { "${it.itemType}:${it.contentHash}" })
                .joinToString("\n"),
        )

    private fun localRepositoryReference(request: LocalAiSessionIngestRequest): String =
        "local://repo/${sha256Hex("local-repo:${request.repoIdentityHash.trim()}").take(32)}"

    private fun localSessionProvenance(request: LocalAiSessionIngestRequest): Map<String, String> =
        linkedMapOf<String, String>().apply {
            put("repoIdentityHash", sha256Hex("local-repo:${request.repoIdentityHash.trim()}"))
            safeRequestToken(request.toolProvider)?.let { put("toolProvider", it) }
            request.toolSessionId?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("toolSessionIdHash", sha256Hex("tool-session:$it"))
            }
            request.toolEventId?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("toolEventIdHash", sha256Hex("tool-event:$it"))
            }
            request.timestampBucket?.let(::safeRequestToken)?.let {
                put("timestampBucket", it)
            }
        }

    private fun safeLocalSessionTitle(request: LocalAiSessionIngestRequest): String {
        val title = request.title.trim()
        return if (title.isNotBlank() && title.length <= MAX_LOCAL_SESSION_TITLE_CHARS && secretScanner.scan(title).isEmpty() && !unsafeLocalPathPattern.containsMatchIn(title)) {
            title
        } else {
            "Local AI session"
        }
    }

    private fun requireAllowedAutoAttribution(autoAttribution: String): String {
        val normalized = autoAttribution.trim()
        if (normalized !in allowedLocalAutoAttributions) {
            throw BadRequestException("autoAttribution is not supported")
        }
        return normalized
    }

    private fun requireAllowedUserAttribution(userAttribution: String): String {
        val normalized = userAttribution.trim()
        if (normalized !in allowedLocalUserAttributions) {
            throw BadRequestException("userAttribution is not supported")
        }
        return normalized
    }

    private fun requireValidConfidence(confidence: BigDecimal?): BigDecimal? {
        if (confidence != null && (confidence < BigDecimal.ZERO || confidence > BigDecimal.ONE)) {
            throw BadRequestException("attributionConfidence must be between 0 and 1")
        }
        return confidence
    }

    private fun safeAttributionReasons(reasons: List<String>): List<String> =
        reasons.mapNotNull { safeRequestToken(it) }
            .filter { it in allowedAttributionReasons }
            .distinct()
            .take(MAX_ATTRIBUTION_REASONS)

    private fun requireValidAttributionState(
        bundle: SourceBundleEntity,
        userAttribution: String,
    ) {
        if (userAttribution == LocalAiSessionPolicy.USER_ATTRIBUTION_USE_FOR_GENERATION && bundle.status != LocalAiSessionPolicy.STATUS_GENERATION_ELIGIBLE) {
            throw BadRequestException("evidence is not safe for generation")
        }
    }

    private fun safeCommitSha(commitSha: String?): String? =
        commitSha?.trim()?.takeIf { commitShaPattern.matches(it) }

    private fun safeBranchName(branchName: String?): String? =
        branchName
            ?.trim()
            ?.take(MAX_BRANCH_CHARS)
            ?.takeIf { it.isNotBlank() && safeBranchPattern.matches(it) && secretScanner.scan(it).isEmpty() && !unsafeLocalPathPattern.containsMatchIn(it) }

    private fun safeRequestToken(value: String): String? =
        value.trim().takeIf { safeTokenPattern.matches(it) && secretScanner.scan(it).isEmpty() }

    private companion object {
        val allowedSourceKinds = setOf("code", "diff", "commit", "pull_request", "conversation", "supporting_context")
        const val BUNDLE_RAW_PURGE_REASON = "local_owner_bundle_raw_purge"
        const val REPOSITORY_RAW_PURGE_REASON = "local_owner_repository_raw_purge"
        const val ALL_RAW_PURGE_REASON = "local_owner_all_raw_purge"
        const val EMPTY_FILE_PATHS_JSON = "[]"
        const val EMPTY_PROVENANCE_JSON = "{}"
        const val MAX_LOCAL_SESSION_TITLE_CHARS = 180
        const val MAX_ATTRIBUTION_REASONS = 20
        const val MAX_BRANCH_CHARS = 120
        const val MAX_EVIDENCE_LIST_PAGE_SIZE = 100
        const val MAX_REPOSITORY_DISPLAY_LABEL_LENGTH = 240
        const val REPOSITORY_CONSENT_APPROVED = "approved"
        val allowedLocalAutoAttributions =
            setOf(
                LocalAiSessionPolicy.AUTO_ATTRIBUTION_AI_ASSISTED,
                LocalAiSessionPolicy.AUTO_ATTRIBUTION_MANUAL_OR_UNKNOWN,
                LocalAiSessionPolicy.AUTO_ATTRIBUTION_GUI_CORRELATED,
            )
        val allowedLocalUserAttributions = LocalAiSessionPolicy.userAttributions
        val allowedAttributionReasons =
            setOf(
                "tool_session",
                "changed_files",
                "human_review",
                "curation_approved",
                "user_deleted",
                "gui_activity_window",
                "repo_changed",
                "single_ai_tool",
                "competing_ai_tools",
            )
        val REPOSITORY_CONSENT_STATUSES = setOf("approved", "revoked", "always_ignored", "missing")
        val REPO_IDENTITY_HASH_PATTERN = Regex("[A-Za-z0-9._:-]{3,128}")
        val ROLE_ORDER = listOf("learner", "contributor", "reviewer", "admin")
        val safeTokenPattern = Regex("^[A-Za-z0-9][A-Za-z0-9_.:#@+-]{0,119}$")
        val commitShaPattern = Regex("^[a-fA-F0-9]{7,64}$")
        val safeBranchPattern = Regex("^[A-Za-z0-9][A-Za-z0-9_./#@+-]{0,119}$")
        val unsafeLocalPathPattern = Regex("""(^|[^A-Za-z0-9._-])(?:/|~|[A-Za-z]:[\\/]|\\\\)[^\s]+""")
    }
}

data class ManualIngestResult(
    val bundle: SourceBundleEntity,
    val item: EvidenceItemEntity,
)

data class LocalAiSessionIngestResult(
    val bundle: SourceBundleEntity,
    val items: List<EvidenceItemEntity>,
    val ignoredArtifacts: List<IgnoredLocalSessionArtifact>,
    val duplicate: Boolean,
)

data class EvidenceDetail(
    val bundle: SourceBundleEntity,
    val items: List<EvidenceItemEntity>,
)

data class EvidenceList(
    val bundles: List<SourceBundleEntity>,
    val page: Int,
    val pageSize: Int,
    val total: Long,
)
