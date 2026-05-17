package com.aicodelearning.organization

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "organizations")
class OrganizationEntity(
    @Id
    var id: String = "",

    @Column(nullable = false)
    var name: String = "",

    @Column(nullable = false, unique = true)
    var slug: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "teams")
class TeamEntity(
    @Id
    var id: String = "",

    @Column(name = "organization_id", nullable = false)
    var organizationId: String = "",

    @Column(nullable = false)
    var name: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "projects")
class ProjectEntity(
    @Id
    var id: String = "",

    @Column(name = "organization_id", nullable = false)
    var organizationId: String = "",

    @Column(name = "team_id")
    var teamId: String? = null,

    @Column(nullable = false)
    var name: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "users")
class UserEntity(
    @Id
    var id: String = "",

    @Column(nullable = false, unique = true)
    var email: String = "",

    @Column(name = "display_name", nullable = false)
    var displayName: String = "",

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String = "",

    @Column(name = "deactivated_at")
    var deactivatedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "memberships")
class MembershipEntity(
    @Id
    var id: String = "",

    @Column(name = "user_id", nullable = false)
    var userId: String = "",

    @Column(name = "organization_id", nullable = false)
    var organizationId: String = "",

    @Column(name = "team_id")
    var teamId: String? = null,

    @Column(name = "project_id")
    var projectId: String? = null,

    @Column(nullable = false)
    var role: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

data class MembershipSummary(
    val organizationId: String,
    val teamId: String?,
    val projectId: String?,
    val role: String,
)
