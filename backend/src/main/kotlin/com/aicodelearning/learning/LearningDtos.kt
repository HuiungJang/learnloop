package com.aicodelearning.learning

import java.time.Instant

data class GenerationRunResponse(
    val id: String,
    val organizationId: String,
    val providerConfigId: String,
    val status: String,
    val visibility: String,
    val failureCode: String?,
    val createdAt: Instant,
    val completedAt: Instant?,
)

data class PatternCardResponse(
    val id: String,
    val organizationId: String,
    val teamId: String?,
    val projectId: String?,
    val title: String,
    val summary: String,
    val visibility: String,
    val publicationStatus: String,
    val tags: List<PatternTagResponse>,
    val problems: List<ProblemResponse>,
    val createdAt: Instant,
    val publishedAt: Instant?,
)

data class PatternTagResponse(
    val tagType: String,
    val name: String,
)

data class ProblemResponse(
    val id: String,
    val type: String,
    val prompt: String,
    val referenceAnswer: String?,
    val difficulty: String,
)

data class ReviewTaskResponse(
    val id: String,
    val patternCardId: String,
    val organizationId: String,
    val authorUserId: String,
    val status: String,
    val createdAt: Instant,
    val decidedAt: Instant?,
)

data class SubmissionResponse(
    val id: String,
    val problemId: String,
    val userId: String,
    val resultStatus: String,
    val createdAt: Instant,
)

fun GenerationRunEntity.toResponse(): GenerationRunResponse =
    GenerationRunResponse(
        id = id,
        organizationId = organizationId,
        providerConfigId = providerConfigId,
        status = status,
        visibility = visibility,
        failureCode = failureCode,
        createdAt = createdAt,
        completedAt = completedAt,
    )

fun ReviewTaskEntity.toResponse(): ReviewTaskResponse =
    ReviewTaskResponse(
        id = id,
        patternCardId = patternCardId,
        organizationId = organizationId,
        authorUserId = authorUserId,
        status = status,
        createdAt = createdAt,
        decidedAt = decidedAt,
    )

fun SubmissionEntity.toResponse(): SubmissionResponse =
    SubmissionResponse(
        id = id,
        problemId = problemId,
        userId = userId,
        resultStatus = resultStatus,
        createdAt = createdAt,
    )
