package com.aicodelearning.organization

import org.springframework.data.jpa.repository.JpaRepository

interface OrganizationRepository : JpaRepository<OrganizationEntity, String>

interface TeamRepository : JpaRepository<TeamEntity, String>

interface ProjectRepository : JpaRepository<ProjectEntity, String>

interface UserRepository : JpaRepository<UserEntity, String> {
    fun findByEmailIgnoreCase(email: String): UserEntity?
}

interface MembershipRepository : JpaRepository<MembershipEntity, String> {
    fun findByUserId(userId: String): List<MembershipEntity>

    fun findByUserIdAndOrganizationId(
        userId: String,
        organizationId: String,
    ): List<MembershipEntity>
}
