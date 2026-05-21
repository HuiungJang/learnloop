package com.aicodelearning.learning

import com.aicodelearning.audit.AuditService
import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.evidence.EvidenceItemEntity
import com.aicodelearning.evidence.EvidenceItemRepository
import com.aicodelearning.evidence.LocalAiSessionPolicy
import com.aicodelearning.evidence.SourceBundleEntity
import com.aicodelearning.evidence.SourceBundleRepository
import com.aicodelearning.evidence.findExistingForUpdateSortedById
import com.aicodelearning.evidence.passesGenerationCurationGate
import com.aicodelearning.evidence.stableLocalSessionItemOrder
import com.aicodelearning.organization.AuthorizationService
import com.aicodelearning.platform.BadRequestException
import com.aicodelearning.platform.ForbiddenException
import com.aicodelearning.platform.prefixedId
import com.aicodelearning.provider.PatternGenerationClientFactory
import com.aicodelearning.provider.PatternGenerationClientRequest
import com.aicodelearning.provider.PatternGenerationResult
import com.aicodelearning.provider.ProviderConfigResolver
import com.aicodelearning.provider.ProviderFailureCode
import com.aicodelearning.provider.ProviderGenerationApiException
import com.aicodelearning.provider.ProviderGenerationException
import com.aicodelearning.source.SourceLinkRepository
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

@Service
class GenerationService(
    private val sourceLinkRepository: SourceLinkRepository,
    private val sourceBundleRepository: SourceBundleRepository,
    private val evidenceItemRepository: EvidenceItemRepository,
    private val generationRunRepository: GenerationRunRepository,
    private val patternCardRepository: PatternCardRepository,
    private val patternTagRepository: PatternTagRepository,
    private val patternTagLinkRepository: PatternTagLinkRepository,
    private val problemRepository: ProblemRepository,
    private val reviewTaskRepository: ReviewTaskRepository,
    private val authorizationService: AuthorizationService,
    private val auditService: AuditService,
    private val patternReadService: PatternReadService,
    private val patternRecognitionPromptBuilder: PatternRecognitionPromptBuilder,
    private val providerConfigResolver: ProviderConfigResolver,
    private val patternGenerationClientFactory: PatternGenerationClientFactory,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun run(
        currentUser: CurrentUser,
        request: GenerationRequest,
    ): GenerationResponse {
        val existing =
            request.idempotencyKey?.let { idempotencyKey ->
                transactionTemplate.execute {
                    existingGeneration(currentUser, request, idempotencyKey)
                }
            }
        if (existing != null) {
            return existing
        }

        val preflight =
            transactionTemplate.execute {
                prepareGeneration(currentUser, request)
            } ?: throw BadRequestException("Generation could not be prepared")
        val provider =
            try {
                transactionTemplate.execute {
                    providerConfigResolver.resolve(currentUser, request.organizationId, request.providerConfigId, request.visibility)
                } ?: throw ProviderGenerationException(ProviderFailureCode.PROVIDER_CONFIGURATION_INVALID)
            } catch (exception: ProviderGenerationException) {
                val failedRun =
                    transactionTemplate.execute {
                        recordFailedGeneration(currentUser, preflight, exception.failureCode)
                    } ?: throw exception
                throw ProviderGenerationApiException(failedRun.id, exception.failureCode)
            }

        val generated =
            try {
                patternGenerationClientFactory
                    .clientFor(provider.provider)
                    .generate(
                        PatternGenerationClientRequest(
                            providerConfig = provider,
                            promptText = preflight.recognitionPrompt.promptText,
                            evidenceExcerpt = preflight.recognitionPrompt.evidenceExcerpt,
                        ),
                    )
            } catch (exception: ProviderGenerationException) {
                val failedRun =
                    transactionTemplate.execute {
                        recordFailedGeneration(currentUser, preflight, exception.failureCode)
                    } ?: throw exception
                throw ProviderGenerationApiException(failedRun.id, exception.failureCode)
            }

        return transactionTemplate.execute {
            persistGeneratedAssets(currentUser, preflight, generated)
        } ?: throw BadRequestException("Generation could not be persisted")
    }

    private fun existingGeneration(
        currentUser: CurrentUser,
        request: GenerationRequest,
        idempotencyKey: String,
    ): GenerationResponse? {
        val existing = generationRunRepository.findByOrganizationIdAndIdempotencyKey(request.organizationId, idempotencyKey)
        if (existing != null) {
            if (existing.createdByUserId != currentUser.id) {
                throw ForbiddenException("Idempotency key belongs to another generation request")
            }
            if (!existing.samePayloadAs(request)) {
                throw ForbiddenException("Idempotency key belongs to another generation request")
            }
            if (existing.status == "failed") {
                val failureCode =
                    ProviderFailureCode.entries.firstOrNull { it.value == existing.failureCode }
                        ?: ProviderFailureCode.PROVIDER_CONFIGURATION_INVALID
                throw ProviderGenerationApiException(existing.id, failureCode)
            }
        }
        val existingCard = existing?.let { run -> patternCardRepository.findByGenerationRunId(run.id) }
        val existingTask = existingCard?.let { card -> reviewTaskRepository.findByPatternCardId(card.id) }
        if (existing != null && existingCard != null && existingTask != null) {
            authorizationService.requireRole(currentUser, existingCard.organizationId, "contributor", existingCard.teamId, existingCard.projectId)
            return GenerationResponse(existing.toResponse(), patternReadService.toResponse(currentUser, existingCard, true), existingTask.toResponse())
        }
        return null
    }

    private fun GenerationRunEntity.samePayloadAs(request: GenerationRequest): Boolean =
        providerConfigId == request.providerConfigId &&
            visibility == request.visibility &&
            parseStringList(sourceLinkIdsJson) == request.sourceLinkIds.distinct() &&
            parseStringList(sourceBundleIdsJson) == request.sourceBundleIds.distinct()

    private fun prepareGeneration(
        currentUser: CurrentUser,
        request: GenerationRequest,
    ): GenerationPreflight {
        if (request.visibility !in setOf("private", "organization")) {
            throw BadRequestException("visibility must be private or organization")
        }
        if (request.sourceLinkIds.isEmpty() && request.sourceBundleIds.isEmpty()) {
            throw BadRequestException("At least one source link or source bundle is required")
        }
        if (request.sourceLinkIds.isNotEmpty() && request.sourceBundleIds.isNotEmpty()) {
            throw BadRequestException("Choose either source links or source bundles")
        }
        val sources =
            if (request.sourceLinkIds.isNotEmpty()) {
                resolveLinkedGenerationSources(currentUser, request)
            } else {
                resolveLocalSessionGenerationSources(currentUser, request)
            }
        if (sources.evidenceItems.isEmpty()) {
            throw BadRequestException("Generation requires unpurged source evidence")
        }

        val evidenceText =
            sources.evidenceItems
                .map { requireNotNull(it.contentText) }
                .joinToString("\n")

        val recognitionPrompt = patternRecognitionPromptBuilder.build(evidenceText)
        return GenerationPreflight(
            request = request,
            sources = sources,
            recognitionPrompt = recognitionPrompt,
        )
    }

    private fun persistGeneratedAssets(
        currentUser: CurrentUser,
        preflight: GenerationPreflight,
        generated: PatternGenerationResult,
    ): GenerationResponse {
        val request = preflight.request
        val sources = preflight.sources
        val now = Instant.now()
        val run =
            generationRunRepository.save(
                GenerationRunEntity(
                    id = prefixedId("run"),
                    organizationId = request.organizationId,
                    providerConfigId = request.providerConfigId,
                    createdByUserId = currentUser.id,
                    status = "completed",
                    visibility = request.visibility,
                    idempotencyKey = request.idempotencyKey,
                    sourceLinkIdsJson = objectMapper.writeValueAsString(sources.sourceLinkIds),
                    sourceBundleIdsJson = objectMapper.writeValueAsString(sources.sourceBundleIds),
                    evidenceItemIdsJson = objectMapper.writeValueAsString(sources.evidenceItems.map { it.id }),
                    createdAt = now,
                    completedAt = now,
                ),
            )
        val card =
            patternCardRepository.save(
                PatternCardEntity(
                    id = prefixedId("card"),
                    organizationId = request.organizationId,
                    teamId = sources.firstBundle.teamId,
                    projectId = sources.firstBundle.projectId,
                    generationRunId = run.id,
                    createdByUserId = currentUser.id,
                    title = generated.title,
                    summary = generated.summary,
                    visibility = request.visibility,
                    publicationStatus = "draft",
                    createdAt = now,
                ),
            )
        generated.tags.forEach { tag ->
            val normalized = tag.name.lowercase()
            val entity =
                patternTagRepository.findByNormalizedName(normalized)
                    ?: patternTagRepository.save(PatternTagEntity(id = prefixedId("tag"), tagType = tag.type, name = tag.name, normalizedName = normalized))
            patternTagLinkRepository.save(PatternTagLinkEntity(patternCardId = card.id, tagId = entity.id))
        }
        generated.problems.forEach {
            problemRepository.save(
                ProblemEntity(
                    id = prefixedId("problem"),
                    patternCardId = card.id,
                    problemType = it.type,
                    prompt = it.prompt,
                    referenceAnswer = it.referenceAnswer,
                    difficulty = it.difficulty,
                    createdAt = now,
                ),
            )
        }
        val task =
            reviewTaskRepository.save(
                ReviewTaskEntity(
                    id = prefixedId("review"),
                    patternCardId = card.id,
                    organizationId = request.organizationId,
                    authorUserId = currentUser.id,
                    status = "open",
                    createdAt = now,
                ),
            )
        auditService.append(currentUser, request.organizationId, "generation.completed", "generation_run", run.id)
        return GenerationResponse(run.toResponse(), patternReadService.toResponse(currentUser, card, true), task.toResponse())
    }

    private fun recordFailedGeneration(
        currentUser: CurrentUser,
        preflight: GenerationPreflight,
        failureCode: ProviderFailureCode,
    ): GenerationRunEntity {
        val request = preflight.request
        val sources = preflight.sources
        val now = Instant.now()
        val run =
            generationRunRepository.save(
                GenerationRunEntity(
                    id = prefixedId("run"),
                    organizationId = request.organizationId,
                    providerConfigId = request.providerConfigId,
                    createdByUserId = currentUser.id,
                    status = "failed",
                    visibility = request.visibility,
                    idempotencyKey = request.idempotencyKey,
                    sourceLinkIdsJson = objectMapper.writeValueAsString(sources.sourceLinkIds),
                    sourceBundleIdsJson = objectMapper.writeValueAsString(sources.sourceBundleIds),
                    evidenceItemIdsJson = objectMapper.writeValueAsString(sources.evidenceItems.map { it.id }),
                    failureCode = failureCode.value,
                    createdAt = now,
                    completedAt = now,
                ),
            )
        auditService.append(
            actor = currentUser,
            organizationId = request.organizationId,
            eventType = "generation.failed",
            targetType = "generation_run",
            targetId = run.id,
            metadata = mapOf("failureCode" to failureCode.value),
        )
        return run
    }

    private fun resolveLinkedGenerationSources(
        currentUser: CurrentUser,
        request: GenerationRequest,
    ): GenerationSources {
        val requestedSourceLinkIds = request.sourceLinkIds.distinct()
        val linksById = sourceLinkRepository.findByIdIn(requestedSourceLinkIds).associateBy { it.id }
        val links = requestedSourceLinkIds.mapNotNull { linksById[it] }
        if (links.size != requestedSourceLinkIds.size || links.any { it.organizationId != request.organizationId || it.status != "confirmed" }) {
            throw BadRequestException("Generation requires confirmed source links")
        }
        val linkedBundleIds =
            links
                .flatMap { listOf(it.conversationBundleId, it.codeBundleId) }
                .distinct()
        val linkedBundles = requiredLockedSourceBundlesById(linkedBundleIds)
        if (linkedBundles.values.any { it.organizationId != request.organizationId || it.deletedAt != null }) {
            throw BadRequestException("Generation requires source bundles in the requested organization")
        }
        if (linkedBundles.values.any { !it.passesGenerationCurationGate() }) {
            throw BadRequestException("Generation requires local AI session evidence marked for generation")
        }
        linkedBundles.values.forEach { bundle ->
            authorizationService.requireRole(currentUser, bundle.organizationId, "contributor", bundle.teamId, bundle.projectId)
        }
        val linkedEvidenceItems =
            linkedBundleIds.flatMap { bundleId ->
                generationEvidenceItems(linkedBundles.getValue(bundleId), evidenceItemRepository.findByBundleId(bundleId))
            }
        val firstBundle = linkedBundles[links.first().codeBundleId] ?: throw BadRequestException("Generation requires a code bundle")
        return GenerationSources(
            sourceLinkIds = requestedSourceLinkIds,
            sourceBundleIds = linkedBundleIds,
            firstBundle = firstBundle,
            evidenceItems = linkedEvidenceItems,
        )
    }

    private fun resolveLocalSessionGenerationSources(
        currentUser: CurrentUser,
        request: GenerationRequest,
    ): GenerationSources {
        val requestedSourceBundleIds = request.sourceBundleIds.distinct()
        if (requestedSourceBundleIds.size != 1) {
            throw BadRequestException("Generation requires exactly one local AI session bundle")
        }
        val bundlesById = requiredLockedSourceBundlesById(requestedSourceBundleIds)
        if (
            bundlesById.values.any {
                it.organizationId != request.organizationId ||
                    it.deletedAt != null ||
                    it.sourceKind != LocalAiSessionPolicy.SOURCE_KIND
            }
        ) {
            throw BadRequestException("Generation requires curated local AI session evidence")
        }
        if (bundlesById.values.any { !it.passesGenerationCurationGate() }) {
            throw BadRequestException("Generation requires local AI session evidence marked for generation")
        }
        bundlesById.values.forEach { bundle ->
            authorizationService.requireRole(currentUser, bundle.organizationId, "contributor", bundle.teamId, bundle.projectId)
        }
        val evidenceItems =
            requestedSourceBundleIds.flatMap { bundleId ->
                generationEvidenceItems(bundlesById.getValue(bundleId), evidenceItemRepository.findByBundleId(bundleId))
            }
        return GenerationSources(
            sourceLinkIds = emptyList(),
            sourceBundleIds = requestedSourceBundleIds,
            firstBundle = bundlesById.getValue(requestedSourceBundleIds.first()),
            evidenceItems = evidenceItems,
        )
    }

    private fun generationEvidenceItems(
        bundle: SourceBundleEntity,
        items: List<EvidenceItemEntity>,
    ): List<EvidenceItemEntity> {
        if (items.isEmpty()) {
            throw BadRequestException("Generation requires unpurged source evidence")
        }
        if (bundle.sourceKind == LocalAiSessionPolicy.SOURCE_KIND) {
            val generationItems =
                items
                    .filter { it.itemType in LocalAiSessionPolicy.generationItemTypes }
                    .stableLocalSessionItemOrder()
            if (generationItems.isEmpty() || generationItems.any { it.contentText == null }) {
                throw BadRequestException("Generation requires unpurged source evidence")
            }
            return generationItems
        }
        if (items.any { it.contentText == null }) {
            throw BadRequestException("Generation requires unpurged source evidence")
        }
        return items
    }

    private fun requiredLockedSourceBundlesById(bundleIds: List<String>): Map<String, SourceBundleEntity> {
        val requestedIds = bundleIds.distinct()
        val bundlesById =
            sourceBundleRepository.findExistingForUpdateSortedById(requestedIds)
                .associateBy { it.id }
        if (bundlesById.size != requestedIds.size) {
            throw BadRequestException("Generation requires source bundles in the requested organization")
        }
        return bundlesById
    }

    @Transactional(readOnly = true)
    fun traces(
        currentUser: CurrentUser,
        organizationId: String,
        limit: Int = DEFAULT_TRACE_LIMIT,
    ): ConversionTraceListResponse {
        authorizationService.requireOrganizationMember(currentUser, organizationId, "contributor")

        val normalizedLimit = limit.coerceIn(1, MAX_TRACE_LIMIT)
        val runs =
            generationRunRepository.findByOrganizationIdOrderByCreatedAtDesc(
                organizationId,
                PageRequest.of(0, normalizedLimit),
            )
        if (runs.isEmpty()) {
            return ConversionTraceListResponse(emptyList())
        }

        val sourceLinkIdsByRun = runs.associate { it.id to parseStringList(it.sourceLinkIdsJson) }
        val sourceBundleIdsByRun = runs.associate { it.id to parseStringList(it.sourceBundleIdsJson) }
        val sourceLinks =
            sourceLinkRepository
                .findByIdIn(sourceLinkIdsByRun.values.flatten().distinct())
                .associateBy { it.id }
        val bundleIds =
            (
                sourceLinks.values.flatMap { listOf(it.conversationBundleId, it.codeBundleId) } +
                    sourceBundleIdsByRun.values.flatten()
            ).distinct()
        val bundlesById = sourceBundleRepository.findAllById(bundleIds).filter { it.deletedAt == null }.associateBy { it.id }
        val cardsByRunId =
            patternCardRepository
                .findByGenerationRunIdIn(runs.map { it.id })
                .associateBy { it.generationRunId }
        val cardIds = cardsByRunId.values.map { it.id }
        val reviewTasksByCardId = reviewTaskRepository.findByPatternCardIdIn(cardIds).associateBy { it.patternCardId }
        val problemsByCardId = problemRepository.findByPatternCardIdIn(cardIds).groupBy { it.patternCardId }

        val traces =
            runs.mapNotNull { run ->
                val card = cardsByRunId[run.id]
                if (card != null && !canReadTraceCard(currentUser, card)) {
                    return@mapNotNull null
                }

                val links = sourceLinkIdsByRun.getValueOrEmpty(run.id).mapNotNull { sourceLinks[it] }
                val link = links.firstOrNull()
                val conversation = link?.conversationBundleId?.let { bundlesById[it] }
                val directBundle = sourceBundleIdsByRun.getValueOrEmpty(run.id).mapNotNull { bundlesById[it] }.firstOrNull()
                val code = link?.codeBundleId?.let { bundlesById[it] } ?: directBundle
                if (card == null) {
                    if (code == null) return@mapNotNull null
                    if (!authorizationService.hasRole(currentUser.id, code.organizationId, "contributor", code.teamId, code.projectId)) return@mapNotNull null
                }

                val pattern = card?.let { patternReadService.toResponse(currentUser, it, includeAnswers = false) }
                val reviewTask = card?.let { reviewTasksByCardId[it.id] }
                val problems = card?.let { problemsByCardId.getValueOrEmpty(it.id) }.orEmpty()

                ConversionTraceResponse(
                    generationRunId = run.id,
                    status = run.status,
                    failureCode = run.failureCode,
                    createdAt = run.createdAt,
                    source =
                        code?.let {
                            ConversionTraceSourceResponse(
                                sourceLinkId = link?.id,
                                sourceBundleId = it.id,
                                sourceLinkStatus = link?.status,
                                confidence = link?.confidence,
                                conversationTitle = conversation?.title,
                                codeTitle = it.title,
                                codeSourceKind = it.sourceKind,
                            )
                        },
                    pattern =
                        pattern?.let {
                            ConversionTracePatternResponse(
                                patternCardId = it.id,
                                title = it.title,
                                summary = it.summary,
                                tags = it.tags,
                            )
                        },
                    exercise =
                        card?.let {
                            ConversionTraceExerciseResponse(
                                patternCardId = it.id,
                                problemCount = problems.size,
                                difficulties = problems.map { problem -> problem.difficulty }.distinct(),
                                publicationStatus = it.publicationStatus,
                                reviewTaskId = reviewTask?.id,
                                reviewStatus = reviewTask?.status,
                            )
                        },
                )
            }

        return ConversionTraceListResponse(traces)
    }

    private fun inferPattern(text: String): InferredPattern {
        val normalized = text.lowercase()
        val tags =
            buildList {
                if ("react" in normalized || "queryclient" in normalized) add(InferredTag("framework", "React"))
                if ("spring" in normalized || "@service" in normalized) add(InferredTag("framework", "Spring"))
                if ("retry" in normalized || "timeout" in normalized) add(InferredTag("pattern", "Retry/Timeout"))
                if ("oauth" in normalized || "token" in normalized || "authorization" in normalized) add(InferredTag("api", "Auth API"))
                if (isEmpty()) add(InferredTag("pattern", "Implementation Pattern"))
            }
        val primary = tags.first()
        return InferredPattern(
            title = "${primary.name} Practice Pattern",
            summary = "A reusable ${primary.name.lowercase()} pattern extracted from AI-assisted implementation evidence.",
            tags = tags,
            problems =
                listOf(
                    InferredProblem("qa", "When should a developer use the ${primary.name} approach shown in this pattern?", "Use it when the implementation has similar technical constraints while avoiding product-specific details.", "beginner"),
                    InferredProblem("short_implementation", "Implement a similar pattern in a neutral order-processing domain.", "A strong answer keeps the API boundary explicit, handles errors, and keeps domain names generic.", "intermediate"),
                    InferredProblem("debugging", "What failure mode should be tested before reusing this pattern?", "Test the edge case that would break the boundary, such as timeout, invalid token, or missing dependency behavior.", "intermediate"),
                ),
            )
    }

    private fun parseStringList(json: String): List<String> =
        try {
            objectMapper.readValue(json, stringListType)
        } catch (_: Exception) {
            emptyList()
        }

    private fun canReadTraceCard(
        currentUser: CurrentUser,
        card: PatternCardEntity,
    ): Boolean {
        if (!authorizationService.hasRole(currentUser.id, card.organizationId, "contributor", card.teamId, card.projectId)) {
            return false
        }
        if (PracticeAccessPolicy.isPublishedOrganizationPractice(card.publicationStatus, card.visibility)) {
            return true
        }

        val hasReviewerRole = authorizationService.hasRole(currentUser.id, card.organizationId, "reviewer", card.teamId, card.projectId)
        return PracticeAccessPolicy.canReadDraftPractice(card.createdByUserId, currentUser.id, hasReviewerRole)
    }

    private fun <T> Map<String, List<T>>.getValueOrEmpty(key: String): List<T> = this[key] ?: emptyList()

    private companion object {
        const val DEFAULT_TRACE_LIMIT = 25
        const val MAX_TRACE_LIMIT = 50
        val stringListType = object : TypeReference<List<String>>() {}
    }
}

data class GenerationRequest(
    val organizationId: String = "",
    val providerConfigId: String = "",
    val sourceLinkIds: List<String> = emptyList(),
    val sourceBundleIds: List<String> = emptyList(),
    val visibility: String = "organization",
    val idempotencyKey: String? = null,
)

data class GenerationResponse(
    val generationRun: GenerationRunResponse,
    val patternCard: PatternCardResponse,
    val reviewTask: ReviewTaskResponse,
)

data class ConversionTraceListResponse(
    val traces: List<ConversionTraceResponse>,
)

data class ConversionTraceResponse(
    val generationRunId: String,
    val status: String,
    val failureCode: String?,
    val createdAt: Instant,
    val source: ConversionTraceSourceResponse?,
    val pattern: ConversionTracePatternResponse?,
    val exercise: ConversionTraceExerciseResponse?,
)

data class ConversionTraceSourceResponse(
    val sourceLinkId: String?,
    val sourceBundleId: String,
    val sourceLinkStatus: String?,
    val confidence: java.math.BigDecimal?,
    val conversationTitle: String?,
    val codeTitle: String?,
    val codeSourceKind: String?,
)

data class ConversionTracePatternResponse(
    val patternCardId: String,
    val title: String,
    val summary: String,
    val tags: List<PatternTagResponse>,
)

data class ConversionTraceExerciseResponse(
    val patternCardId: String,
    val problemCount: Int,
    val difficulties: List<String>,
    val publicationStatus: String,
    val reviewTaskId: String?,
    val reviewStatus: String?,
)

private data class InferredPattern(
    val title: String,
    val summary: String,
    val tags: List<InferredTag>,
    val problems: List<InferredProblem>,
)

private data class InferredTag(
    val type: String,
    val name: String,
)

private data class InferredProblem(
    val type: String,
    val prompt: String,
    val referenceAnswer: String,
    val difficulty: String,
)

private data class GenerationSources(
    val sourceLinkIds: List<String>,
    val sourceBundleIds: List<String>,
    val firstBundle: SourceBundleEntity,
    val evidenceItems: List<EvidenceItemEntity>,
)

private data class GenerationPreflight(
    val request: GenerationRequest,
    val sources: GenerationSources,
    val recognitionPrompt: PatternRecognitionPrompt,
)
