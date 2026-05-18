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

    @Column(name = "source_link_ids_json", nullable = false)
    var sourceLinkIdsJson: String = "[]",

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
@Table(name = "problem_files")
class ProblemFileEntity(
    @Id
    var id: String = "",

    @Column(name = "problem_id", nullable = false)
    var problemId: String = "",

    @Column(nullable = false)
    var path: String = "",

    @Column(nullable = false)
    var language: String = "",

    @Column(name = "file_role", nullable = false)
    var fileRole: String = "",

    @Column(nullable = false)
    var content: String = "",

    @Column(name = "read_only", nullable = false)
    var readOnly: Boolean = false,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "problem_hints")
class ProblemHintEntity(
    @Id
    var id: String = "",

    @Column(name = "problem_id", nullable = false)
    var problemId: String = "",

    @Column(name = "reveal_order", nullable = false)
    var revealOrder: Int = 0,

    @Column(nullable = false)
    var label: String = "",

    @Column(nullable = false)
    var content: String = "",

    @Column(name = "reveal_policy", nullable = false)
    var revealPolicy: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "problem_provenance_links")
class ProblemProvenanceLinkEntity(
    @Id
    var id: String = "",

    @Column(name = "problem_id", nullable = false)
    var problemId: String = "",

    @Column(name = "evidence_item_id")
    var evidenceItemId: String? = null,

    @Column(name = "source_link_id")
    var sourceLinkId: String? = null,

    @Column(name = "source_type", nullable = false)
    var sourceType: String = "",

    @Column(name = "source_label", nullable = false)
    var sourceLabel: String = "",

    @Column(name = "redacted_excerpt", nullable = false)
    var redactedExcerpt: String = "",

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

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

    @Column(name = "client_attempt_id")
    var clientAttemptId: String? = null,

    @Column(name = "asset_revision")
    var assetRevision: String? = null,

    @Column
    var language: String? = null,

    @Column(name = "attempt_status", nullable = false)
    var attemptStatus: String = "submitted",

    @Column
    var score: Int? = null,

    @Column(name = "metadata_json", nullable = false)
    var metadataJson: String = "{}",

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "submitted_at")
    var submittedAt: Instant? = null,
)

@Entity
@Table(name = "submission_files")
class SubmissionFileEntity(
    @Id
    var id: String = "",

    @Column(name = "submission_id", nullable = false)
    var submissionId: String = "",

    @Column(nullable = false)
    var path: String = "",

    @Column(nullable = false)
    var content: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "sandbox_run_results")
class SandboxRunResultEntity(
    @Id
    var id: String = "",

    @Column(name = "problem_id", nullable = false)
    var problemId: String = "",

    @Column(name = "user_id", nullable = false)
    var userId: String = "",

    @Column(name = "submission_id")
    var submissionId: String? = null,

    @Column(nullable = false)
    var status: String = "",

    @Column(name = "runner_kind", nullable = false)
    var runnerKind: String = "",

    @Column(name = "duration_ms")
    var durationMs: Long? = null,

    @Column(name = "tests_json", nullable = false)
    var testsJson: String = "[]",

    @Column(name = "stdout_excerpt")
    var stdoutExcerpt: String? = null,

    @Column(name = "stderr_excerpt")
    var stderrExcerpt: String? = null,

    @Column(name = "failed_diff")
    var failedDiff: String? = null,

    @Column(name = "failure_reason")
    var failureReason: String? = null,

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
