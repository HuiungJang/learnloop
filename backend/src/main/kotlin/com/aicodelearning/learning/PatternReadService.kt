package com.aicodelearning.learning

import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.organization.AuthorizationService
import com.aicodelearning.platform.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PatternReadService(
    private val patternCardRepository: PatternCardRepository,
    private val patternTagRepository: PatternTagRepository,
    private val patternTagLinkRepository: PatternTagLinkRepository,
    private val problemRepository: ProblemRepository,
    private val submissionRepository: SubmissionRepository,
    private val authorizationService: AuthorizationService,
) {
    @Transactional(readOnly = true)
    fun listPublished(
        currentUser: CurrentUser,
        organizationId: String,
    ): List<PatternCardResponse> {
        authorizationService.requireOrganizationMember(currentUser, organizationId, "learner")
        return patternCardRepository
            .findByOrganizationIdAndPublicationStatusAndVisibility(organizationId, "published", "organization")
            .filter { authorizationService.hasRole(currentUser.id, it.organizationId, "learner", it.teamId, it.projectId) }
            .map { toResponse(currentUser, it, includeAnswers = false) }
    }

    @Transactional(readOnly = true)
    fun detail(
        currentUser: CurrentUser,
        cardId: String,
        forceAnswers: Boolean = false,
    ): PatternCardResponse {
        val card = patternCardRepository.findById(cardId).orElseThrow { NotFoundException("Pattern card not found") }
        if (card.publicationStatus == "published" && card.visibility == "organization") {
            authorizationService.requireRole(currentUser, card.organizationId, "learner", card.teamId, card.projectId)
        } else if (card.createdByUserId != currentUser.id) {
            authorizationService.requireRole(currentUser, card.organizationId, "reviewer", card.teamId, card.projectId)
        }

        val problemIds = problemRepository.findByPatternCardId(card.id).map { it.id }
        val hasSubmitted = submissionRepository.findByUserIdAndProblemIdIn(currentUser.id, problemIds).isNotEmpty()
        return toResponse(currentUser, card, includeAnswers = forceAnswers || hasSubmitted || card.createdByUserId == currentUser.id)
    }

    fun toResponse(
        currentUser: CurrentUser,
        card: PatternCardEntity,
        includeAnswers: Boolean,
    ): PatternCardResponse {
        val tags =
            patternTagLinkRepository
                .findByPatternCardId(card.id)
                .mapNotNull { patternTagRepository.findById(it.tagId).orElse(null) }
                .map { PatternTagResponse(tagType = it.tagType, name = it.name) }
        val problems =
            problemRepository
                .findByPatternCardId(card.id)
                .map {
                    ProblemResponse(
                        id = it.id,
                        type = it.problemType,
                        prompt = it.prompt,
                        referenceAnswer = if (includeAnswers) it.referenceAnswer else null,
                        difficulty = it.difficulty,
                    )
                }

        return PatternCardResponse(
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
    }
}
