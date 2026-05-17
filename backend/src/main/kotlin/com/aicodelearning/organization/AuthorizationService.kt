package com.aicodelearning.organization

import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.platform.ForbiddenException
import org.springframework.stereotype.Service

private val roleOrder = listOf("learner", "contributor", "reviewer", "admin")

@Service
class AuthorizationService(
    private val membershipRepository: MembershipRepository,
) {
    fun requireRole(
        currentUser: CurrentUser,
        organizationId: String,
        role: String,
        teamId: String? = null,
        projectId: String? = null,
    ) {
        if (!hasRole(currentUser.id, organizationId, role, teamId, projectId)) {
            throw ForbiddenException("Not allowed for this organization scope")
        }
    }

    fun requireOrganizationMember(
        currentUser: CurrentUser,
        organizationId: String,
        role: String,
    ) {
        if (!hasOrganizationMemberRole(currentUser.id, organizationId, role)) {
            throw ForbiddenException("Not allowed for this organization scope")
        }
    }

    fun hasOrganizationMemberRole(
        userId: String,
        organizationId: String,
        role: String,
    ): Boolean {
        val required = roleOrder.indexOf(role)
        if (required < 0) {
            return false
        }

        return membershipRepository
            .findByUserIdAndOrganizationId(userId, organizationId)
            .any { membership -> roleOrder.indexOf(membership.role) >= required }
    }

    fun hasRole(
        userId: String,
        organizationId: String,
        role: String,
        teamId: String? = null,
        projectId: String? = null,
    ): Boolean {
        val required = roleOrder.indexOf(role)
        if (required < 0) {
            return false
        }

        return membershipRepository
            .findByUserIdAndOrganizationId(userId, organizationId)
            .any { membership ->
                val actual = roleOrder.indexOf(membership.role)
                if (actual < required) {
                    return@any false
                }
                if (membership.role == "admin") {
                    return@any true
                }
                if (teamId == null && projectId == null) {
                    return@any membership.teamId == null && membership.projectId == null
                }
                if (teamId != null && membership.teamId != null && membership.teamId != teamId) {
                    return@any false
                }
                if (projectId != null && membership.projectId != null && membership.projectId != projectId) {
                    return@any false
                }
                true
            }
    }
}
