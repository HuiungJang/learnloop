package com.aicodelearning.learning

import com.aicodelearning.audit.AuditService
import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.organization.AuthorizationService
import com.aicodelearning.platform.BadRequestException
import com.aicodelearning.platform.ConflictException
import com.aicodelearning.platform.NotFoundException
import com.aicodelearning.platform.prefixedId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class SubmissionService(
    private val problemRepository: ProblemRepository,
    private val problemFileRepository: ProblemFileRepository,
    private val problemHintRepository: ProblemHintRepository,
    private val problemProvenanceLinkRepository: ProblemProvenanceLinkRepository,
    private val submissionRepository: SubmissionRepository,
    private val submissionFileRepository: SubmissionFileRepository,
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

        val existingSubmitted =
            request.clientAttemptId
                ?.let { submissionRepository.findByUserIdAndProblemIdAndClientAttemptId(currentUser.id, problem.id, it) }
                ?.takeIf { it.attemptStatus == PracticeContract.ATTEMPT_STATUS_SUBMITTED }
        if (existingSubmitted != null) {
            return SubmissionResult(submission = existingSubmitted, patternCard = patternReadService.detail(currentUser, card.id, forceAnswers = true))
        }

        if (request.files.isNotEmpty()) {
            validateFileSubmission(problem, request)
        }

        val now = Instant.now()
        val submission =
            request.clientAttemptId
                ?.let { submissionRepository.findByUserIdAndProblemIdAndClientAttemptId(currentUser.id, problem.id, it) }
                ?: SubmissionEntity(
                    id = prefixedId("submission"),
                    problemId = problem.id,
                    userId = currentUser.id,
                    createdAt = now,
                    clientAttemptId = request.clientAttemptId,
                )
        submission.textAnswer = request.textAnswer
        submission.resultStatus = request.resultStatus.ifBlank { PracticeContract.RESULT_STATUS_SUBMITTED }
        submission.assetRevision = request.assetRevision ?: submission.assetRevision
        submission.language = request.language ?: submission.language
        submission.attemptStatus = PracticeContract.ATTEMPT_STATUS_SUBMITTED
        submission.updatedAt = now
        submission.submittedAt = submission.submittedAt ?: now

        val savedSubmission = submissionRepository.save(submission)
        if (request.files.isNotEmpty()) {
            submissionFileRepository.deleteBySubmissionId(savedSubmission.id)
            submissionFileRepository.saveAll(
                request.files.map { file ->
                    SubmissionFileEntity(
                        id = prefixedId("submission_file"),
                        submissionId = savedSubmission.id,
                        path = PracticeContract.normalizeFilePath(file.path),
                        content = file.content,
                        createdAt = now,
                    )
                },
            )
        }
        updateProficiency(currentUser, card)
        auditService.append(currentUser, card.organizationId, "submission.created", "submission", savedSubmission.id)
        return SubmissionResult(submission = savedSubmission, patternCard = patternReadService.detail(currentUser, card.id, forceAnswers = true))
    }

    private fun validateFileSubmission(
        problem: ProblemEntity,
        request: SubmissionRequest,
    ) {
        val clientAttemptId = request.clientAttemptId?.takeIf { it.isNotBlank() } ?: throw BadRequestException("clientAttemptId is required for file submissions")
        val assetRevision = request.assetRevision?.takeIf { it.isNotBlank() } ?: throw BadRequestException("assetRevision is required for file submissions")
        val language = request.language?.takeIf { it.isNotBlank() } ?: throw BadRequestException("language is required for file submissions")
        PracticeContract.validateAttemptSyncRequest(
            PracticeAttemptSyncRequest(
                clientAttemptId = clientAttemptId,
                assetRevision = assetRevision,
                language = language,
                intent = PracticeContract.ATTEMPT_STATUS_SUBMITTED,
                files = request.files,
                localUpdatedAt = Instant.now(),
            ),
        )

        val currentAssetRevision =
            PracticeAssetRevision.compute(
                problem = problem,
                files = problemFileRepository.findByProblemIdOrderBySortOrderAscPathAsc(problem.id),
                hints = problemHintRepository.findByProblemIdOrderByRevealOrderAsc(problem.id),
                provenance = problemProvenanceLinkRepository.findByProblemIdOrderBySortOrderAsc(problem.id),
            )
        if (assetRevision != currentAssetRevision) {
            throw ConflictException("Practice problem has changed. Refresh before submitting this attempt.")
        }
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
    val clientAttemptId: String? = null,
    val assetRevision: String? = null,
    val language: String? = null,
    val files: List<PracticeAttemptFileRequest> = emptyList(),
)

data class SubmissionResult(
    val submission: SubmissionEntity,
    val patternCard: PatternCardResponse,
)
