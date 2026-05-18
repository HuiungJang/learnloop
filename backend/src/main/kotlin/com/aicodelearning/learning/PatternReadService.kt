package com.aicodelearning.learning

import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.organization.AuthorizationService
import com.aicodelearning.platform.NotFoundException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.ConcurrentHashMap

@Service
class PatternReadService(
    private val patternCardRepository: PatternCardRepository,
    private val patternTagRepository: PatternTagRepository,
    private val problemRepository: ProblemRepository,
    private val submissionRepository: SubmissionRepository,
    private val authorizationService: AuthorizationService,
) {
    private val publishedPracticeCache = ConcurrentHashMap<String, PracticeContent>()

    @Transactional(readOnly = true)
    fun listPublished(
        currentUser: CurrentUser,
        organizationId: String,
        limit: Int? = null,
    ): List<PatternCardResponse> {
        if (!currentUser.hasOrganizationMemberRole("learner", organizationId)) {
            authorizationService.requireOrganizationMember(currentUser, organizationId, "learner")
        }
        val cards =
            findPublishedCards(organizationId, limit)
                .filter { currentUser.hasRole("learner", it.organizationId, it.teamId, it.projectId) }
                .let { if (limit == null) it else it.take(limit) }

        return toResponses(cards, includeAnswers = false)
    }

    @Transactional(readOnly = true)
    fun detail(
        currentUser: CurrentUser,
        cardId: String,
        forceAnswers: Boolean = false,
    ): PatternCardResponse {
        val card = patternCardRepository.findById(cardId).orElseThrow { NotFoundException("Pattern card not found") }
        if (PracticeAccessPolicy.isPublishedOrganizationPractice(card.publicationStatus, card.visibility)) {
            if (!currentUser.hasRole("learner", card.organizationId, card.teamId, card.projectId)) {
                authorizationService.requireRole(currentUser, card.organizationId, "learner", card.teamId, card.projectId)
            }
        } else if (
            !PracticeAccessPolicy.canReadDraftPractice(
                card.createdByUserId,
                currentUser.id,
                currentUser.hasRole("reviewer", card.organizationId, card.teamId, card.projectId),
            )
        ) {
            authorizationService.requireRole(currentUser, card.organizationId, "reviewer", card.teamId, card.projectId)
        }

        val practice = loadPracticeContent(card)
        val problemIds = practice.problems.map { it.id }
        val hasSubmitted = problemIds.isNotEmpty() && submissionRepository.existsByUserIdAndProblemIdIn(currentUser.id, problemIds)
        val includeAnswers = PracticeAccessPolicy.canViewAnswers(card.createdByUserId, currentUser.id, hasSubmitted, forceAnswers)
        return toResponse(card, practice.tags, maskAnswers(practice.problems, includeAnswers))
    }

    fun toResponse(
        currentUser: CurrentUser,
        card: PatternCardEntity,
        includeAnswers: Boolean,
        preloadedProblems: List<ProblemEntity>? = null,
    ): PatternCardResponse {
        val practice =
            if (preloadedProblems == null) {
                loadUncachedPracticeContent(card.id)
            } else {
                PracticeContent(
                    tags = loadTagsByCard(listOf(card.id)).getValueOrEmpty(card.id),
                    problems = preloadedProblems.map { it.toProblemResponse(includeAnswer = true) },
                )
            }

        return toResponse(card, practice.tags, maskAnswers(practice.problems, includeAnswers))
    }

    private fun findPublishedCards(
        organizationId: String,
        limit: Int?,
    ): List<PatternCardEntity> =
        if (limit == null) {
            patternCardRepository.findByOrganizationIdAndPublicationStatusAndVisibility(organizationId, "published", "organization")
        } else {
            patternCardRepository.findByOrganizationIdAndPublicationStatusAndVisibilityOrderByPublishedAtDescCreatedAtDesc(
                organizationId,
                "published",
                "organization",
                PageRequest.of(0, limit),
            )
        }

    private fun toResponses(
        cards: List<PatternCardEntity>,
        includeAnswers: Boolean,
    ): List<PatternCardResponse> {
        if (cards.isEmpty()) {
            return emptyList()
        }

        val cardIds = cards.map { it.id }
        val tagsByCard = loadTagsByCard(cardIds)
        val problemsByCard =
            problemRepository
                .findByPatternCardIdIn(cardIds)
                .groupBy { it.patternCardId }

        return cards.map { card ->
            val tags = tagsByCard.getValueOrEmpty(card.id)
            val problems =
                problemsByCard
                    .getValueOrEmpty(card.id)
                    .map {
                        ProblemResponse(
                            id = it.id,
                            type = it.problemType,
                            prompt = it.prompt,
                            referenceAnswer = if (includeAnswers) it.referenceAnswer else null,
                            difficulty = it.difficulty,
                        )
                    }

            toResponse(card, tags, problems)
        }
    }

    private fun loadTagsByCard(cardIds: List<String>): Map<String, List<PatternTagResponse>> =
        patternTagRepository
            .findTagsByPatternCardIdIn(cardIds)
            .groupBy { it.patternCardId }
            .mapValues { (_, tags) -> tags.map { PatternTagResponse(tagType = it.tagType, name = it.name) } }

    private fun loadPracticeContent(card: PatternCardEntity): PracticeContent =
        if (card.publicationStatus == "published" && card.visibility == "organization") {
            publishedPracticeCache.computeIfAbsent(card.id) { loadUncachedPracticeContent(card.id) }
        } else {
            loadUncachedPracticeContent(card.id)
        }

    private fun loadUncachedPracticeContent(cardId: String): PracticeContent =
        PracticeContent(
            tags = loadTagsByCard(listOf(cardId)).getValueOrEmpty(cardId),
            problems = problemRepository.findByPatternCardId(cardId).map { it.toProblemResponse(includeAnswer = true) },
        )

    private fun ProblemEntity.toProblemResponse(includeAnswer: Boolean): ProblemResponse =
        ProblemResponse(
            id = id,
            type = problemType,
            prompt = prompt,
            referenceAnswer = if (includeAnswer) referenceAnswer else null,
            difficulty = difficulty,
        )

    private fun maskAnswers(
        problems: List<ProblemResponse>,
        includeAnswers: Boolean,
    ): List<ProblemResponse> = if (includeAnswers) problems else problems.map { it.copy(referenceAnswer = null) }

    private fun toResponse(
        card: PatternCardEntity,
        tags: List<PatternTagResponse>,
        problems: List<ProblemResponse>,
    ): PatternCardResponse =
        PatternCardResponse(
            id = card.id,
            organizationId = card.organizationId,
            teamId = card.teamId,
            projectId = card.projectId,
            title = card.title,
            summary = card.summary,
            visibility = card.visibility,
            publicationStatus = card.publicationStatus,
            tags = tags,
            problems = problems,
            createdAt = card.createdAt,
            publishedAt = card.publishedAt,
        )

    private fun <T> Map<String, List<T>>.getValueOrEmpty(key: String): List<T> = this[key] ?: emptyList()

    private fun CurrentUser.hasRole(
        role: String,
        organizationId: String,
        teamId: String?,
        projectId: String?,
    ): Boolean {
        val required = roleOrder.indexOf(role)
        if (required < 0) {
            return false
        }

        return memberships
            .filter { it.organizationId == organizationId }
            .any { membership ->
                val actual = roleOrder.indexOf(membership.role)
                if (actual < required) {
                    return@any false
                }
                if (membership.role == "admin") {
                    return@any true
                }
                if (teamId == null && projectId == null) {
                    return@any membership.teamId == null && membership.projectId == null
                }
                if (teamId != null && membership.teamId != null && membership.teamId != teamId) {
                    return@any false
                }
                if (projectId != null && membership.projectId != null && membership.projectId != projectId) {
                    return@any false
                }
                true
            }
    }

    private fun CurrentUser.hasOrganizationMemberRole(
        role: String,
        organizationId: String,
    ): Boolean {
        val required = roleOrder.indexOf(role)
        if (required < 0) {
            return false
        }

        return memberships
            .filter { it.organizationId == organizationId }
            .any { roleOrder.indexOf(it.role) >= required }
    }

    private companion object {
        val roleOrder = listOf("learner", "contributor", "reviewer", "admin")
    }
}

private data class PracticeContent(
    val tags: List<PatternTagResponse>,
    val problems: List<ProblemResponse>,
)
