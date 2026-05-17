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
        if (linkedBundles.size != linkedBundleIds.size || linkedBundles.values.any { it.organizationId != request.organizationId }) {
            throw BadRequestException("Generation requires source bundles in the requested organization")
        }
        linkedBundles.values.forEach { bundle ->
            authorizationService.requireRole(currentUser, bundle.organizationId, "contributor", bundle.teamId, bundle.projectId)
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
                    createdAt = now,
                    completedAt = now,
                ),
            )

        val firstBundle = linkedBundles[links.first().codeBundleId] ?: throw BadRequestException("Generation requires a code bundle")
        val evidenceText =
            linkedBundleIds
                .flatMap { evidenceItemRepository.findByBundleId(it) }
                .mapNotNull { it.contentText }
                .joinToString("\n")

        val inferred = inferPattern(evidenceText)
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
