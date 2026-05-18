package com.aicodelearning.learning

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

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

    fun findByOrganizationIdAndPublicationStatusAndVisibilityOrderByPublishedAtDescCreatedAtDesc(
        organizationId: String,
        publicationStatus: String,
        visibility: String,
        pageable: Pageable,
    ): List<PatternCardEntity>
}

interface PatternTagRepository : JpaRepository<PatternTagEntity, String> {
    fun findByNormalizedName(normalizedName: String): PatternTagEntity?

    @Query(
        """
        select link.patternCardId as patternCardId, tag.tagType as tagType, tag.name as name
        from PatternTagLinkEntity link
        join PatternTagEntity tag on tag.id = link.tagId
        where link.patternCardId in :patternCardIds
        """,
    )
    fun findTagsByPatternCardIdIn(
        @Param("patternCardIds") patternCardIds: Collection<String>,
    ): List<PatternTagProjection>
}

interface PatternTagProjection {
    val patternCardId: String
    val tagType: String
    val name: String
}

interface PatternTagLinkRepository : JpaRepository<PatternTagLinkEntity, PatternTagLinkId> {
    fun findByPatternCardId(patternCardId: String): List<PatternTagLinkEntity>

    fun findByPatternCardIdIn(patternCardIds: Collection<String>): List<PatternTagLinkEntity>
}

interface ProblemRepository : JpaRepository<ProblemEntity, String> {
    fun findByPatternCardId(patternCardId: String): List<ProblemEntity>

    fun findByPatternCardIdIn(patternCardIds: Collection<String>): List<ProblemEntity>
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

    fun existsByUserIdAndProblemIdIn(
        userId: String,
        problemIds: Collection<String>,
    ): Boolean
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
