package com.aicodelearning.learning

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
class PracticeService(
    private val problemRepository: ProblemRepository,
    private val patternCardRepository: PatternCardRepository,
    private val problemFileRepository: ProblemFileRepository,
    private val problemHintRepository: ProblemHintRepository,
    private val problemProvenanceLinkRepository: ProblemProvenanceLinkRepository,
    private val submissionRepository: SubmissionRepository,
    private val submissionFileRepository: SubmissionFileRepository,
    private val authorizationService: AuthorizationService,
) {
    @Transactional(readOnly = true)
    fun detail(
        currentUser: CurrentUser,
        problemId: String,
    ): PracticeProblemResponse {
        val problem = problemRepository.findById(problemId).orElseThrow { NotFoundException("Problem not found") }
        val card = patternCardRepository.findById(problem.patternCardId).orElseThrow { NotFoundException("Pattern card not found") }
        authorizePracticeRead(currentUser, card)

        val files = problemFileRepository.findByProblemIdOrderBySortOrderAscPathAsc(problem.id)
        val hints = problemHintRepository.findByProblemIdOrderByRevealOrderAsc(problem.id)
        val provenance = problemProvenanceLinkRepository.findByProblemIdOrderBySortOrderAsc(problem.id)

        return PracticeProblemResponse(
            id = problem.id,
            patternCardId = card.id,
            title = card.title,
            prompt = problem.prompt,
            difficulty = problem.difficulty,
            assetRevision = PracticeAssetRevision.compute(problem, files, hints, provenance),
            files =
                files
                    .filter { it.fileRole in visibleFileRoles }
                    .map {
                        PracticeFileResponse(
                            path = it.path,
                            language = it.language,
                            role = it.fileRole,
                            content = it.content,
                            readOnly = it.readOnly,
                            sortOrder = it.sortOrder,
                        )
                    },
            hints =
                hints.map {
                    PracticeHintResponse(
                        id = it.id,
                        revealOrder = it.revealOrder,
                        label = it.label,
                        content = if (it.revealPolicy == PracticeContract.HINT_REVEAL_MANUAL) it.content else null,
                        revealed = false,
                        revealPolicy = it.revealPolicy,
                    )
                },
            provenance =
                provenance.map {
                    PracticeProvenanceResponse(
                        sourceType = it.sourceType,
                        sourceLabel = it.sourceLabel,
                        redactedExcerpt = it.redactedExcerpt,
                        evidenceItemId = null,
                    )
                },
            attempt = null,
            latestRun = null,
        )
    }

    @Transactional(readOnly = true)
    fun currentUserAttempts(
        currentUser: CurrentUser,
        problemId: String,
    ): List<PracticeAttemptResponse> {
        val problem = problemRepository.findById(problemId).orElseThrow { NotFoundException("Problem not found") }
        val card = patternCardRepository.findById(problem.patternCardId).orElseThrow { NotFoundException("Pattern card not found") }
        authorizePracticeRead(currentUser, card)

        val canonicalFiles = problemFileRepository.findByProblemIdOrderBySortOrderAscPathAsc(problem.id)
        val currentAssetRevision = currentAssetRevision(problem, canonicalFiles)
        val attempts = submissionRepository.findByUserIdAndProblemIdInOrderByUpdatedAtDesc(currentUser.id, listOf(problem.id))
        val filesBySubmission =
            if (attempts.isEmpty()) {
                emptyMap()
            } else {
                submissionFileRepository
                    .findBySubmissionIdInOrderBySubmissionIdAscPathAsc(attempts.map { it.id })
                    .groupBy { it.submissionId }
            }
        val defaultLanguage = canonicalFiles.firstOrNull { it.fileRole == PracticeContract.FILE_ROLE_STARTER }?.language ?: PracticeContract.LANGUAGE_TYPESCRIPT

        return attempts.map {
            it.toPracticeAttemptResponse(filesBySubmission.getValueOrEmpty(it.id), currentAssetRevision, defaultLanguage)
        }
    }

    @Transactional
    fun syncLocalAttempt(
        currentUser: CurrentUser,
        problemId: String,
        request: PracticeAttemptSyncRequest,
    ): PracticeAttemptResponse {
        PracticeContract.validateAttemptSyncRequest(request)
        if (request.intent != PracticeContract.ATTEMPT_STATUS_DRAFT) {
            throw BadRequestException("local sync only accepts draft attempts")
        }

        val problem = problemRepository.findById(problemId).orElseThrow { NotFoundException("Problem not found") }
        val card = patternCardRepository.findById(problem.patternCardId).orElseThrow { NotFoundException("Pattern card not found") }
        authorizePracticeRead(currentUser, card)

        val canonicalFiles = problemFileRepository.findByProblemIdOrderBySortOrderAscPathAsc(problem.id)
        val currentAssetRevision = currentAssetRevision(problem, canonicalFiles)
        if (request.assetRevision != currentAssetRevision) {
            throw ConflictException("Practice problem has changed. Refresh before syncing this attempt.")
        }

        val now = Instant.now()
        val existing =
            submissionRepository.findByUserIdAndProblemIdAndClientAttemptId(
                currentUser.id,
                problem.id,
                request.clientAttemptId,
            )
        if (existing?.attemptStatus == PracticeContract.ATTEMPT_STATUS_SUBMITTED) {
            throw ConflictException("Submitted attempts cannot be updated through local sync")
        }

        val attempt =
            existing
                ?: SubmissionEntity(
                    id = prefixedId("submission"),
                    problemId = problem.id,
                    userId = currentUser.id,
                    textAnswer = "",
                    resultStatus = PracticeContract.RESULT_STATUS_SUBMITTED,
                    createdAt = now,
                    clientAttemptId = request.clientAttemptId,
                )

        attempt.assetRevision = request.assetRevision
        attempt.language = request.language
        attempt.attemptStatus = request.intent
        attempt.metadataJson = """{"localUpdatedAt":"${request.localUpdatedAt}"}"""
        attempt.updatedAt = now
        val savedAttempt = submissionRepository.save(attempt)

        submissionFileRepository.deleteBySubmissionId(savedAttempt.id)
        val savedFiles =
            submissionFileRepository.saveAll(
                request.files.map { file ->
                    SubmissionFileEntity(
                        id = prefixedId("submission_file"),
                        submissionId = savedAttempt.id,
                        path = PracticeContract.normalizeFilePath(file.path),
                        content = file.content,
                        createdAt = now,
                    )
                },
            )

        val defaultLanguage = canonicalFiles.firstOrNull { it.fileRole == PracticeContract.FILE_ROLE_STARTER }?.language ?: request.language
        return savedAttempt.toPracticeAttemptResponse(savedFiles.sortedBy { it.path }, currentAssetRevision, defaultLanguage)
    }

    private fun authorizePracticeRead(
        currentUser: CurrentUser,
        card: PatternCardEntity,
    ) {
        if (PracticeAccessPolicy.isPublishedOrganizationPractice(card.publicationStatus, card.visibility)) {
            authorizationService.requireRole(currentUser, card.organizationId, "learner", card.teamId, card.projectId)
            return
        }

        if (PracticeAccessPolicy.canReadDraftPractice(card.createdByUserId, currentUser.id, hasReviewerRole = false)) {
            return
        }

        authorizationService.requireRole(currentUser, card.organizationId, "reviewer", card.teamId, card.projectId)
    }

    private fun currentAssetRevision(
        problem: ProblemEntity,
        files: List<ProblemFileEntity> = problemFileRepository.findByProblemIdOrderBySortOrderAscPathAsc(problem.id),
    ): String =
        PracticeAssetRevision.compute(
            problem = problem,
            files = files,
            hints = problemHintRepository.findByProblemIdOrderByRevealOrderAsc(problem.id),
            provenance = problemProvenanceLinkRepository.findByProblemIdOrderBySortOrderAsc(problem.id),
        )

    private fun SubmissionEntity.toPracticeAttemptResponse(
        files: List<SubmissionFileEntity>,
        defaultAssetRevision: String,
        defaultLanguage: String,
    ): PracticeAttemptResponse =
        PracticeAttemptResponse(
            id = id,
            problemId = problemId,
            clientAttemptId = clientAttemptId ?: id,
            assetRevision = assetRevision ?: defaultAssetRevision,
            language = language ?: defaultLanguage,
            status = attemptStatus,
            files = files.map { PracticeAttemptFileResponse(path = it.path, content = it.content) },
            score = score,
            resultStatus = resultStatus,
            updatedAt = updatedAt,
            submittedAt = submittedAt,
        )

    private companion object {
        val visibleFileRoles = setOf(PracticeContract.FILE_ROLE_STARTER, PracticeContract.FILE_ROLE_SUPPORT)
    }
}

private fun <T> Map<String, List<T>>.getValueOrEmpty(key: String): List<T> = this[key] ?: emptyList()
