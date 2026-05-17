package com.aicodelearning.learning

import com.aicodelearning.audit.AuditService
import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.organization.AuthorizationService
import com.aicodelearning.platform.BadRequestException
import com.aicodelearning.platform.NotFoundException
import com.aicodelearning.platform.prefixedId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class SubmissionService(
    private val problemRepository: ProblemRepository,
    private val submissionRepository: SubmissionRepository,
    private val patternCardRepository: PatternCardRepository,
    private val patternTagRepository: PatternTagRepository,
    private val patternTagLinkRepository: PatternTagLinkRepository,
    private val proficiencyScoreRepository: ProficiencyScoreRepository,
    private val authorizationService: AuthorizationService,
    private val auditService: AuditService,
    private val patternReadService: PatternReadService,
) {
    @Transactional
    fun submit(
        currentUser: CurrentUser,
        problemId: String,
        request: SubmissionRequest,
    ): SubmissionResult {
        val problem = problemRepository.findById(problemId).orElseThrow { NotFoundException("Problem not found") }
        val card = patternCardRepository.findById(problem.patternCardId).orElseThrow()
        if (card.publicationStatus != "published") {
            throw BadRequestException("Problem is not published")
        }
        authorizationService.requireRole(currentUser, card.organizationId, "learner", card.teamId, card.projectId)

        val submission =
            submissionRepository.save(
                SubmissionEntity(
                    id = prefixedId("submission"),
                    problemId = problem.id,
                    userId = currentUser.id,
                    textAnswer = request.textAnswer,
                    resultStatus = request.resultStatus.ifBlank { "submitted" },
                    createdAt = Instant.now(),
                ),
            )
        updateProficiency(currentUser, card)
        auditService.append(currentUser, card.organizationId, "submission.created", "submission", submission.id)
        return SubmissionResult(submission = submission, patternCard = patternReadService.detail(currentUser, card.id, forceAnswers = true))
    }

    private fun updateProficiency(
        currentUser: CurrentUser,
        card: PatternCardEntity,
    ) {
        val now = Instant.now()
        patternTagLinkRepository.findByPatternCardId(card.id).forEach { link ->
            val tag = patternTagRepository.findById(link.tagId).orElse(null) ?: return@forEach
            val score =
                proficiencyScoreRepository.findByUserIdAndOrganizationIdAndTagName(currentUser.id, card.organizationId, tag.name)
                    ?: ProficiencyScoreEntity(
                        id = prefixedId("score"),
                        userId = currentUser.id,
                        organizationId = card.organizationId,
                        tagName = tag.name,
                    )
            score.score += 1
            score.updatedAt = now
            proficiencyScoreRepository.save(score)
        }
    }
}

data class SubmissionRequest(
    val textAnswer: String = "",
    val resultStatus: String = "submitted",
)

data class SubmissionResult(
    val submission: SubmissionEntity,
    val patternCard: PatternCardResponse,
)
