package com.aicodelearning.learning

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
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

interface ProblemFileRepository : JpaRepository<ProblemFileEntity, String> {
    fun findByProblemIdOrderBySortOrderAscPathAsc(problemId: String): List<ProblemFileEntity>

    fun findByProblemIdInOrderByProblemIdAscSortOrderAscPathAsc(problemIds: Collection<String>): List<ProblemFileEntity>
}

interface ProblemHintRepository : JpaRepository<ProblemHintEntity, String> {
    fun findByProblemIdOrderByRevealOrderAsc(problemId: String): List<ProblemHintEntity>

    fun findByProblemIdInOrderByProblemIdAscRevealOrderAsc(problemIds: Collection<String>): List<ProblemHintEntity>
}

interface ProblemProvenanceLinkRepository : JpaRepository<ProblemProvenanceLinkEntity, String> {
    fun findByProblemIdOrderBySortOrderAsc(problemId: String): List<ProblemProvenanceLinkEntity>

    fun findByProblemIdInOrderByProblemIdAscSortOrderAsc(problemIds: Collection<String>): List<ProblemProvenanceLinkEntity>
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

    fun findByUserIdAndProblemIdInOrderByUpdatedAtDesc(
        userId: String,
        problemIds: Collection<String>,
    ): List<SubmissionEntity>

    fun findByUserIdAndProblemIdAndClientAttemptId(
        userId: String,
        problemId: String,
        clientAttemptId: String,
    ): SubmissionEntity?

    fun findFirstByUserIdAndProblemIdOrderByUpdatedAtDesc(
        userId: String,
        problemId: String,
    ): SubmissionEntity?

    fun existsByUserIdAndProblemIdIn(
        userId: String,
        problemIds: Collection<String>,
    ): Boolean
}

interface SubmissionFileRepository : JpaRepository<SubmissionFileEntity, String> {
    fun findBySubmissionIdOrderByPathAsc(submissionId: String): List<SubmissionFileEntity>

    fun findBySubmissionIdInOrderBySubmissionIdAscPathAsc(submissionIds: Collection<String>): List<SubmissionFileEntity>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from SubmissionFileEntity file where file.submissionId = :submissionId")
    fun deleteBySubmissionId(
        @Param("submissionId") submissionId: String,
    ): Int
}

interface SandboxRunResultRepository : JpaRepository<SandboxRunResultEntity, String> {
    fun findFirstByUserIdAndProblemIdOrderByCreatedAtDesc(
        userId: String,
        problemId: String,
    ): SandboxRunResultEntity?

    fun findBySubmissionIdOrderByCreatedAtDesc(submissionId: String): List<SandboxRunResultEntity>
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
