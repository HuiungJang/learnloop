package com.aicodelearning.learning

import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.auth.sha256Hex
import com.aicodelearning.organization.AuthorizationService
import com.aicodelearning.platform.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PracticeService(
    private val problemRepository: ProblemRepository,
    private val patternCardRepository: PatternCardRepository,
    private val problemFileRepository: ProblemFileRepository,
    private val problemHintRepository: ProblemHintRepository,
    private val problemProvenanceLinkRepository: ProblemProvenanceLinkRepository,
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
            assetRevision = assetRevision(problem, files, hints, provenance),
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
                    val revealed = it.revealPolicy == PracticeContract.HINT_REVEAL_MANUAL
                    PracticeHintResponse(
                        id = it.id,
                        revealOrder = it.revealOrder,
                        label = it.label,
                        content = if (revealed) it.content else null,
                        revealed = revealed,
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

    private fun assetRevision(
        problem: ProblemEntity,
        files: List<ProblemFileEntity>,
        hints: List<ProblemHintEntity>,
        provenance: List<ProblemProvenanceLinkEntity>,
    ): String =
        "rev-" +
            sha256Hex(
                buildString {
                    append(problem.id).append('\n')
                    append(problem.prompt).append('\n')
                    append(problem.difficulty).append('\n')
                    files.forEach { append(it.path).append('|').append(it.fileRole).append('|').append(it.content).append('\n') }
                    hints.forEach { append(it.id).append('|').append(it.revealOrder).append('|').append(it.content).append('\n') }
                    provenance.forEach { append(it.id).append('|').append(it.redactedExcerpt).append('\n') }
                },
            ).take(16)

    private companion object {
        val visibleFileRoles = setOf(PracticeContract.FILE_ROLE_STARTER, PracticeContract.FILE_ROLE_SUPPORT)
    }
}
