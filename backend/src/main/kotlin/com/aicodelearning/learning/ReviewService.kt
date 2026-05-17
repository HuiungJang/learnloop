package com.aicodelearning.learning

import com.aicodelearning.audit.AuditService
import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.organization.AuthorizationService
import com.aicodelearning.platform.BadRequestException
import com.aicodelearning.platform.ForbiddenException
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
) {
    @Transactional(readOnly = true)
    fun queue(
        currentUser: CurrentUser,
        organizationId: String,
    ): List<ReviewTaskResponse> {
        authorizationService.requireRole(currentUser, organizationId, "reviewer")
        return reviewTaskRepository.findByOrganizationIdAndStatus(organizationId, "open").map { it.toResponse() }
    }

    @Transactional
    fun decide(
        currentUser: CurrentUser,
        taskId: String,
        request: ReviewDecisionRequest,
    ): ReviewDecisionResponse {
        val task = reviewTaskRepository.findById(taskId).orElseThrow { NotFoundException("Review task not found") }
        authorizationService.requireRole(currentUser, task.organizationId, "reviewer")
        if (task.authorUserId == currentUser.id) {
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
            val card = patternCardRepository.findById(task.patternCardId).orElseThrow()
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
