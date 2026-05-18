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

data class PracticeProblemResponse(
    val id: String,
    val patternCardId: String,
    val title: String,
    val prompt: String,
    val difficulty: String,
    val assetRevision: String,
    val files: List<PracticeFileResponse>,
    val hints: List<PracticeHintResponse>,
    val provenance: List<PracticeProvenanceResponse>,
    val attempt: PracticeAttemptResponse?,
    val latestRun: PracticeRunResultResponse?,
)

data class PracticeFileResponse(
    val path: String,
    val language: String,
    val role: String,
    val content: String,
    val readOnly: Boolean,
    val sortOrder: Int,
)

data class PracticeHintResponse(
    val id: String,
    val revealOrder: Int,
    val label: String,
    val content: String?,
    val revealed: Boolean,
)

data class PracticeProvenanceResponse(
    val sourceType: String,
    val sourceLabel: String,
    val redactedExcerpt: String,
    val evidenceItemId: String?,
)

data class PracticeAttemptSyncRequest(
    val clientAttemptId: String,
    val assetRevision: String,
    val language: String,
    val intent: String,
    val files: List<PracticeAttemptFileRequest>,
    val localUpdatedAt: Instant,
)

data class PracticeAttemptFileRequest(
    val path: String,
    val content: String,
)

data class PracticeAttemptResponse(
    val id: String,
    val problemId: String,
    val clientAttemptId: String,
    val assetRevision: String,
    val language: String,
    val status: String,
    val files: List<PracticeAttemptFileResponse>,
    val score: Int?,
    val resultStatus: String?,
    val updatedAt: Instant,
    val submittedAt: Instant?,
)

data class PracticeAttemptFileResponse(
    val path: String,
    val content: String,
)

data class PracticeRunResultResponse(
    val id: String,
    val status: String,
    val runnerKind: String,
    val durationMs: Long?,
    val tests: List<PracticeRunTestResponse>,
    val stdoutExcerpt: String?,
    val stderrExcerpt: String?,
    val failedDiff: String?,
    val failureReason: String?,
    val createdAt: Instant,
)

data class PracticeRunTestResponse(
    val name: String,
    val status: String,
    val message: String?,
    val durationMs: Long?,
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
