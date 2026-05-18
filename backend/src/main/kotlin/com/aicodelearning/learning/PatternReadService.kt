package com.aicodelearning.learning

import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.organization.AuthorizationService
import com.aicodelearning.platform.NotFoundException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

@Service
class PatternReadService(
    private val patternCardRepository: PatternCardRepository,
    private val patternTagRepository: PatternTagRepository,
    private val problemRepository: ProblemRepository,
    private val submissionRepository: SubmissionRepository,
    private val proficiencyScoreRepository: ProficiencyScoreRepository,
    private val authorizationService: AuthorizationService,
) {
    private val publishedPracticeCache = ConcurrentHashMap<String, PracticeContent>()

    @Transactional(readOnly = true)
    fun listPublished(
        currentUser: CurrentUser,
        organizationId: String,
        limit: Int? = null,
        filters: LibraryFilters = LibraryFilters(),
    ): List<PatternCardResponse> {
        if (!currentUser.hasOrganizationMemberRole("learner", organizationId)) {
            authorizationService.requireOrganizationMember(currentUser, organizationId, "learner")
        }
        val cards =
            findPublishedCards(organizationId, limit, filters)
                .filter { currentUser.hasRole("learner", it.organizationId, it.teamId, it.projectId) }
                .let { if (limit == null) it else it.take(limit) }

        return toResponses(cards, includeAnswers = false, filters = filters)
    }

    @Transactional(readOnly = true)
    fun listRecommended(
        currentUser: CurrentUser,
        organizationId: String,
        limit: Int = DEFAULT_RECOMMENDATION_LIMIT,
    ): List<PatternCardResponse> {
        if (!currentUser.hasOrganizationMemberRole("learner", organizationId)) {
            authorizationService.requireOrganizationMember(currentUser, organizationId, "learner")
        }

        val normalizedLimit = limit.coerceIn(1, MAX_RECOMMENDATION_LIMIT)
        val candidateLimit = (normalizedLimit * RECOMMENDATION_CANDIDATE_MULTIPLIER).coerceAtMost(MAX_RECOMMENDATION_CANDIDATES)
        val cards =
            findPublishedCards(organizationId, candidateLimit, LibraryFilters(pageSize = candidateLimit))
                .filter { currentUser.hasRole("learner", it.organizationId, it.teamId, it.projectId) }
        if (cards.isEmpty()) {
            return emptyList()
        }

        val tagsByCard = loadTagsByCard(cards.map { it.id })
        val scoreByTagName =
            proficiencyScoreRepository
                .findByUserIdAndOrganizationId(currentUser.id, organizationId)
                .associateBy({ it.tagName.normalizedTagKey() }, { it.score })
        val ranked =
            cards
                .sortedWith(
                    compareByDescending<PatternCardEntity> { card ->
                        recommendationAffinity(tagsByCard.getValueOrEmpty(card.id), scoreByTagName)
                    }.thenByDescending { it.publishedAt ?: it.createdAt },
                )
                .take(normalizedLimit)

        return toResponses(ranked, includeAnswers = false)
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
        filters: LibraryFilters,
    ): List<PatternCardEntity> =
        patternCardRepository.findByOrganizationIdAndPublicationStatusAndVisibilityOrderByPublishedAtDescCreatedAtDesc(
            organizationId,
            "published",
            "organization",
            PageRequest.of(filters.normalizedPage, limit ?: filters.normalizedPageSize),
        )

    private fun toResponses(
        cards: List<PatternCardEntity>,
        includeAnswers: Boolean,
        filters: LibraryFilters = LibraryFilters(),
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
        }.filter { it.matches(filters) }
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
        const val DEFAULT_RECOMMENDATION_LIMIT = 5
        const val MAX_RECOMMENDATION_LIMIT = 10
        const val MAX_RECOMMENDATION_CANDIDATES = 50
        const val RECOMMENDATION_CANDIDATE_MULTIPLIER = 5
        val roleOrder = listOf("learner", "contributor", "reviewer", "admin")
    }
}

data class LibraryFilters(
    val language: String? = null,
    val tag: String? = null,
    val difficulty: String? = null,
    val page: Int = 0,
    val pageSize: Int = 50,
) {
    val normalizedPage: Int = page.coerceAtLeast(0)
    val normalizedPageSize: Int = pageSize.coerceIn(1, 100)
}

private data class PracticeContent(
    val tags: List<PatternTagResponse>,
    val problems: List<ProblemResponse>,
)

private fun PatternCardResponse.matches(filters: LibraryFilters): Boolean {
    val language = filters.language.normalizedFilter()
    val tag = filters.tag.normalizedFilter()
    val difficulty = filters.difficulty.normalizedFilter()

    if (language != null && tags.none { it.tagType.equals("language", ignoreCase = true) && it.name.normalizedContains(language) }) {
        return false
    }
    if (tag != null && tags.none { it.name.normalizedContains(tag) || it.tagType.normalizedContains(tag) }) {
        return false
    }
    if (difficulty != null && problems.none { it.difficulty.normalizedContains(difficulty) }) {
        return false
    }
    return true
}

private fun String?.normalizedFilter(): String? =
    this
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8) }.getOrDefault(it) }
        ?.lowercase()

private fun String.normalizedContains(filter: String): Boolean = lowercase().contains(filter)

private fun String.normalizedTagKey(): String = trim().lowercase()

private fun recommendationAffinity(
    tags: List<PatternTagResponse>,
    scoreByTagName: Map<String, Int>,
): Int = tags.sumOf { scoreByTagName[it.name.normalizedTagKey()] ?: 0 }
