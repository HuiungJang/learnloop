package com.aicodelearning.learning

import com.aicodelearning.audit.AuditService
import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.organization.AuthorizationService
import com.aicodelearning.platform.BadRequestException
import com.aicodelearning.platform.ForbiddenException
import com.aicodelearning.platform.LocalOwnerAccess
import com.aicodelearning.platform.NotFoundException
import com.aicodelearning.platform.prefixedId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ReviewService(
    private val reviewTaskRepository: ReviewTaskRepository,
    private val reviewDecisionRepository: ReviewDecisionRepository,
    private val patternCardRepository: PatternCardRepository,
    private val authorizationService: AuthorizationService,
    private val auditService: AuditService,
    private val localOwnerAccess: LocalOwnerAccess,
) {
    @Transactional(readOnly = true)
    fun queue(
        currentUser: CurrentUser,
        organizationId: String,
    ): List<ReviewTaskResponse> {
        authorizationService.requireOrganizationMember(currentUser, organizationId, "reviewer")
        return reviewTaskRepository
            .findByOrganizationIdAndStatus(organizationId, "open")
            .filter { task ->
                val card = patternCardRepository.findById(task.patternCardId).orElse(null)
                card != null && authorizationService.hasRole(currentUser.id, card.organizationId, "reviewer", card.teamId, card.projectId)
            }.map { it.toResponse() }
    }

    @Transactional
    fun decide(
        currentUser: CurrentUser,
        taskId: String,
        request: ReviewDecisionRequest,
    ): ReviewDecisionResponse {
        val task = reviewTaskRepository.findById(taskId).orElseThrow { NotFoundException("Review task not found") }
        val card = patternCardRepository.findById(task.patternCardId).orElseThrow { NotFoundException("Pattern card not found") }
        authorizationService.requireRole(currentUser, card.organizationId, "reviewer", card.teamId, card.projectId)
        val isLocalOwner = runCatching { localOwnerAccess.requireLocalOwner(currentUser) }.isSuccess
        if (task.authorUserId == currentUser.id && !isLocalOwner) {
            throw ForbiddenException("Authors cannot review their own generated cards")
        }
        if (task.status != "open") {
            throw BadRequestException("Review task is already decided")
        }

        val now = Instant.now()
        task.status =
            when (request.decision) {
                "approve" -> "approved"
                "request_changes" -> "changes_requested"
                "reject" -> "rejected"
                else -> throw BadRequestException("Unsupported review decision")
            }
        task.decidedAt = now
        val decision =
            reviewDecisionRepository.save(
                ReviewDecisionEntity(
                    id = prefixedId("decision"),
                    reviewTaskId = task.id,
                    reviewerUserId = currentUser.id,
                    decision = request.decision,
                    comment = request.comment,
                    createdAt = now,
                ),
            )

        if (request.decision == "approve") {
            card.publicationStatus = "published"
            card.publishedAt = now
        }

        auditService.append(currentUser, task.organizationId, "review.${request.decision}", "review_task", task.id)
        return ReviewDecisionResponse(reviewTask = task.toResponse(), decision = decision.decision)
    }
}

data class ReviewDecisionRequest(
    val decision: String = "",
    val comment: String? = null,
)

data class ReviewDecisionResponse(
    val reviewTask: ReviewTaskResponse,
    val decision: String,
)
