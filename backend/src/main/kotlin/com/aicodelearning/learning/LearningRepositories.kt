package com.aicodelearning.learning

import org.springframework.data.jpa.repository.JpaRepository

interface GenerationRunRepository : JpaRepository<GenerationRunEntity, String> {
    fun findByOrganizationIdAndIdempotencyKey(
        organizationId: String,
        idempotencyKey: String,
    ): GenerationRunEntity?
}

interface PatternCardRepository : JpaRepository<PatternCardEntity, String> {
    fun findByGenerationRunId(generationRunId: String): PatternCardEntity?

    fun findByOrganizationIdAndPublicationStatusAndVisibility(
        organizationId: String,
        publicationStatus: String,
        visibility: String,
    ): List<PatternCardEntity>
}

interface PatternTagRepository : JpaRepository<PatternTagEntity, String> {
    fun findByNormalizedName(normalizedName: String): PatternTagEntity?
}

interface PatternTagLinkRepository : JpaRepository<PatternTagLinkEntity, PatternTagLinkId> {
    fun findByPatternCardId(patternCardId: String): List<PatternTagLinkEntity>
}

interface ProblemRepository : JpaRepository<ProblemEntity, String> {
    fun findByPatternCardId(patternCardId: String): List<ProblemEntity>
}

interface ReviewTaskRepository : JpaRepository<ReviewTaskEntity, String> {
    fun findByOrganizationIdAndStatus(
        organizationId: String,
        status: String,
    ): List<ReviewTaskEntity>

    fun findByPatternCardId(patternCardId: String): ReviewTaskEntity?
}

interface ReviewDecisionRepository : JpaRepository<ReviewDecisionEntity, String>

interface SubmissionRepository : JpaRepository<SubmissionEntity, String> {
    fun findByUserIdAndProblemIdIn(
        userId: String,
        problemIds: Collection<String>,
    ): List<SubmissionEntity>
}

interface ProficiencyScoreRepository : JpaRepository<ProficiencyScoreEntity, String> {
    fun findByUserIdAndOrganizationId(
        userId: String,
        organizationId: String,
    ): List<ProficiencyScoreEntity>

    fun findByUserIdAndOrganizationIdAndTagName(
        userId: String,
        organizationId: String,
        tagName: String,
    ): ProficiencyScoreEntity?
}
