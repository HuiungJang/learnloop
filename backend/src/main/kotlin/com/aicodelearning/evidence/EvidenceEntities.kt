package com.aicodelearning.evidence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "source_bundles")
class SourceBundleEntity(
    @Id
    var id: String = "",

    @Column(name = "organization_id", nullable = false)
    var organizationId: String = "",

    @Column(name = "team_id")
    var teamId: String? = null,

    @Column(name = "project_id")
    var projectId: String? = null,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: String = "",

    @Column(nullable = false)
    var title: String = "",

    @Column(name = "source_kind", nullable = false)
    var sourceKind: String = "",

    @Column(nullable = false)
    var status: String = "",

    @Column(name = "repository_url")
    var repositoryUrl: String? = null,

    @Column(name = "pull_request_url")
    var pullRequestUrl: String? = null,

    @Column(name = "commit_sha")
    var commitSha: String? = null,

    @Column(name = "branch_name")
    var branchName: String? = null,

    @Column(name = "file_paths_json", nullable = false)
    var filePathsJson: String = "[]",

    @Column(name = "provenance_json", nullable = false)
    var provenanceJson: String = "{}",

    @Column(name = "content_hash", nullable = false)
    var contentHash: String = "",

    @Column(name = "secret_findings_json", nullable = false)
    var secretFindingsJson: String = "[]",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,

    @Column(name = "deleted_by_user_id")
    var deletedByUserId: String? = null,

    @Column(name = "deletion_reason")
    var deletionReason: String? = null,
)

@Entity
@Table(name = "evidence_items")
class EvidenceItemEntity(
    @Id
    var id: String = "",

    @Column(name = "bundle_id", nullable = false)
    var bundleId: String = "",

    @Column(name = "item_type", nullable = false)
    var itemType: String = "",

    @Column(name = "content_text")
    var contentText: String? = null,

    @Column(name = "content_hash", nullable = false)
    var contentHash: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "raw_purged_at")
    var rawPurgedAt: Instant? = null,

    @Column(name = "raw_purge_reason")
    var rawPurgeReason: String? = null,
)
