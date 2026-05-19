package com.aicodelearning.learning

import com.aicodelearning.audit.AuditService
import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.evidence.EvidenceItemRepository
import com.aicodelearning.evidence.SourceBundleRepository
import com.aicodelearning.organization.AuthorizationService
import com.aicodelearning.platform.BadRequestException
import com.aicodelearning.platform.ForbiddenException
import com.aicodelearning.platform.NotFoundException
import com.aicodelearning.platform.prefixedId
import com.aicodelearning.provider.ProviderRepository
import com.aicodelearning.source.SourceLinkRepository
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class GenerationService(
    private val providerRepository: ProviderRepository,
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
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun run(
        currentUser: CurrentUser,
        request: GenerationRequest,
    ): GenerationResponse {
        if (request.visibility !in setOf("private", "organization")) {
            throw BadRequestException("visibility must be private or organization")
        }
        if (request.sourceLinkIds.isEmpty()) {
            throw BadRequestException("At least one source link is required")
        }
        request.idempotencyKey?.let {
            val existing = generationRunRepository.findByOrganizationIdAndIdempotencyKey(request.organizationId, it)
            val existingCard = existing?.let { run -> patternCardRepository.findByGenerationRunId(run.id) }
            val existingTask = existingCard?.let { card -> reviewTaskRepository.findByPatternCardId(card.id) }
            if (existing != null && existingCard != null && existingTask != null) {
                if (existing.createdByUserId != currentUser.id) {
                    throw ForbiddenException("Idempotency key belongs to another generation request")
                }
                authorizationService.requireRole(currentUser, existingCard.organizationId, "contributor", existingCard.teamId, existingCard.projectId)
                return GenerationResponse(existing.toResponse(), patternReadService.toResponse(currentUser, existingCard, true), existingTask.toResponse())
            }
        }

        val provider = providerRepository.findById(request.providerConfigId).orElseThrow { NotFoundException("Provider not found") }
        if (provider.organizationId != request.organizationId || provider.status != "active") {
            throw BadRequestException("Provider is not active for this organization")
        }
        if (provider.scope == "personal" && provider.ownerUserId != currentUser.id) {
            throw ForbiddenException("Personal provider can only be used by its owner")
        }
        if (request.visibility == "organization" && !provider.orgApproved) {
            throw ForbiddenException("Organization publication requires an approved provider")
        }

        val links = sourceLinkRepository.findByIdIn(request.sourceLinkIds)
        if (links.size != request.sourceLinkIds.toSet().size || links.any { it.organizationId != request.organizationId || it.status != "confirmed" }) {
            throw BadRequestException("Generation requires confirmed source links")
        }
        val linkedBundleIds =
            links
                .flatMap { listOf(it.conversationBundleId, it.codeBundleId) }
                .distinct()
        val linkedBundles = sourceBundleRepository.findAllById(linkedBundleIds).associateBy { it.id }
        if (linkedBundles.size != linkedBundleIds.size || linkedBundles.values.any { it.organizationId != request.organizationId || it.deletedAt != null }) {
            throw BadRequestException("Generation requires source bundles in the requested organization")
        }
        linkedBundles.values.forEach { bundle ->
            authorizationService.requireRole(currentUser, bundle.organizationId, "contributor", bundle.teamId, bundle.projectId)
        }
        val linkedEvidenceItems = linkedBundleIds.flatMap { evidenceItemRepository.findByBundleId(it) }
        if (linkedEvidenceItems.isEmpty() || linkedEvidenceItems.any { it.contentText == null }) {
            throw BadRequestException("Generation requires unpurged source evidence")
        }

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
                    sourceLinkIdsJson = objectMapper.writeValueAsString(request.sourceLinkIds.distinct()),
                    sourceBundleIdsJson = objectMapper.writeValueAsString(linkedBundleIds),
                    evidenceItemIdsJson = objectMapper.writeValueAsString(linkedEvidenceItems.map { it.id }),
                    createdAt = now,
                    completedAt = now,
                ),
            )

        val firstBundle = linkedBundles[links.first().codeBundleId] ?: throw BadRequestException("Generation requires a code bundle")
        val evidenceText =
            linkedEvidenceItems
                .map { requireNotNull(it.contentText) }
                .joinToString("\n")

        val recognitionPrompt = patternRecognitionPromptBuilder.build(evidenceText)
        val inferred = inferPattern(recognitionPrompt.evidenceExcerpt)
        val card =
            patternCardRepository.save(
                PatternCardEntity(
                    id = prefixedId("card"),
                    organizationId = request.organizationId,
                    teamId = firstBundle.teamId,
                    projectId = firstBundle.projectId,
                    generationRunId = run.id,
                    createdByUserId = currentUser.id,
                    title = inferred.title,
                    summary = inferred.summary,
                    visibility = request.visibility,
                    publicationStatus = "draft",
                    createdAt = now,
                ),
            )
        inferred.tags.forEach { tag ->
            val normalized = tag.name.lowercase()
            val entity =
                patternTagRepository.findByNormalizedName(normalized)
                    ?: patternTagRepository.save(PatternTagEntity(id = prefixedId("tag"), tagType = tag.type, name = tag.name, normalizedName = normalized))
            patternTagLinkRepository.save(PatternTagLinkEntity(patternCardId = card.id, tagId = entity.id))
        }
        inferred.problems.forEach {
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

        val sourceLinkIdsByRun = runs.associate { it.id to parseSourceLinkIds(it.sourceLinkIdsJson) }
        val sourceLinks =
            sourceLinkRepository
                .findByIdIn(sourceLinkIdsByRun.values.flatten().distinct())
                .associateBy { it.id }
        val bundleIds =
            sourceLinks.values
                .flatMap { listOf(it.conversationBundleId, it.codeBundleId) }
                .distinct()
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
                val code = link?.codeBundleId?.let { bundlesById[it] }
                if (card == null && code != null && !authorizationService.hasRole(currentUser.id, code.organizationId, "contributor", code.teamId, code.projectId)) {
                    return@mapNotNull null
                }

                val pattern = card?.let { patternReadService.toResponse(currentUser, it, includeAnswers = false) }
                val reviewTask = card?.let { reviewTasksByCardId[it.id] }
                val problems = card?.let { problemsByCardId.getValueOrEmpty(it.id) }.orEmpty()

                ConversionTraceResponse(
                    generationRunId = run.id,
                    status = run.status,
                    createdAt = run.createdAt,
                    source =
                        link?.let {
                            ConversionTraceSourceResponse(
                                sourceLinkId = it.id,
                                sourceLinkStatus = it.status,
                                confidence = it.confidence,
                                conversationTitle = conversation?.title,
                                codeTitle = code?.title,
                                codeSourceKind = code?.sourceKind,
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

    private fun parseSourceLinkIds(sourceLinkIdsJson: String): List<String> =
        try {
            objectMapper.readValue(sourceLinkIdsJson, sourceLinkIdsType)
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
        val sourceLinkIdsType = object : TypeReference<List<String>>() {}
    }
}

data class GenerationRequest(
    val organizationId: String = "",
    val providerConfigId: String = "",
    val sourceLinkIds: List<String> = emptyList(),
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
    val createdAt: Instant,
    val source: ConversionTraceSourceResponse?,
    val pattern: ConversionTracePatternResponse?,
    val exercise: ConversionTraceExerciseResponse?,
)

data class ConversionTraceSourceResponse(
    val sourceLinkId: String,
    val sourceLinkStatus: String,
    val confidence: java.math.BigDecimal,
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
