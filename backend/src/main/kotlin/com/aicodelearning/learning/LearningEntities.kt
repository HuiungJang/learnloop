package com.aicodelearning.learning

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

@Entity
@Table(name = "generation_runs")
class GenerationRunEntity(
    @Id
    var id: String = "",

    @Column(name = "organization_id", nullable = false)
    var organizationId: String = "",

    @Column(name = "provider_config_id", nullable = false)
    var providerConfigId: String = "",

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: String = "",

    @Column(nullable = false)
    var status: String = "",

    @Column(nullable = false)
    var visibility: String = "",

    @Column(name = "idempotency_key")
    var idempotencyKey: String? = null,

    @Column(name = "failure_code")
    var failureCode: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "completed_at")
    var completedAt: Instant? = null,
)

@Entity
@Table(name = "pattern_cards")
class PatternCardEntity(
    @Id
    var id: String = "",

    @Column(name = "organization_id", nullable = false)
    var organizationId: String = "",

    @Column(name = "team_id")
    var teamId: String? = null,

    @Column(name = "project_id")
    var projectId: String? = null,

    @Column(name = "generation_run_id")
    var generationRunId: String? = null,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: String = "",

    @Column(nullable = false)
    var title: String = "",

    @Column(nullable = false)
    var summary: String = "",

    @Column(nullable = false)
    var visibility: String = "",

    @Column(name = "publication_status", nullable = false)
    var publicationStatus: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "published_at")
    var publishedAt: Instant? = null,
)

@Entity
@Table(name = "pattern_tags")
class PatternTagEntity(
    @Id
    var id: String = "",

    @Column(name = "tag_type", nullable = false)
    var tagType: String = "",

    @Column(nullable = false)
    var name: String = "",

    @Column(name = "normalized_name", nullable = false, unique = true)
    var normalizedName: String = "",
)

@Entity
@Table(name = "pattern_tag_links")
@IdClass(PatternTagLinkId::class)
class PatternTagLinkEntity(
    @Id
    @Column(name = "pattern_card_id", nullable = false)
    var patternCardId: String = "",

    @Id
    @Column(name = "tag_id", nullable = false)
    var tagId: String = "",
)

data class PatternTagLinkId(
    var patternCardId: String = "",
    var tagId: String = "",
) : Serializable

@Entity
@Table(name = "problems")
class ProblemEntity(
    @Id
    var id: String = "",

    @Column(name = "pattern_card_id", nullable = false)
    var patternCardId: String = "",

    @Column(name = "problem_type", nullable = false)
    var problemType: String = "",

    @Column(nullable = false)
    var prompt: String = "",

    @Column(name = "reference_answer", nullable = false)
    var referenceAnswer: String = "",

    @Column(nullable = false)
    var difficulty: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "review_tasks")
class ReviewTaskEntity(
    @Id
    var id: String = "",

    @Column(name = "pattern_card_id", nullable = false)
    var patternCardId: String = "",

    @Column(name = "organization_id", nullable = false)
    var organizationId: String = "",

    @Column(name = "author_user_id", nullable = false)
    var authorUserId: String = "",

    @Column(nullable = false)
    var status: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "decided_at")
    var decidedAt: Instant? = null,
)

@Entity
@Table(name = "review_decisions")
class ReviewDecisionEntity(
    @Id
    var id: String = "",

    @Column(name = "review_task_id", nullable = false)
    var reviewTaskId: String = "",

    @Column(name = "reviewer_user_id", nullable = false)
    var reviewerUserId: String = "",

    @Column(nullable = false)
    var decision: String = "",

    @Column
    var comment: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "submissions")
class SubmissionEntity(
    @Id
    var id: String = "",

    @Column(name = "problem_id", nullable = false)
    var problemId: String = "",

    @Column(name = "user_id", nullable = false)
    var userId: String = "",

    @Column(name = "text_answer", nullable = false)
    var textAnswer: String = "",

    @Column(name = "result_status", nullable = false)
    var resultStatus: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "proficiency_scores")
class ProficiencyScoreEntity(
    @Id
    var id: String = "",

    @Column(name = "user_id", nullable = false)
    var userId: String = "",

    @Column(name = "organization_id", nullable = false)
    var organizationId: String = "",

    @Column(name = "tag_name", nullable = false)
    var tagName: String = "",

    @Column(nullable = false)
    var score: Int = 0,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
