package com.aicodelearning.platform

import com.aicodelearning.organization.MembershipEntity
import com.aicodelearning.organization.MembershipRepository
import com.aicodelearning.organization.OrganizationEntity
import com.aicodelearning.organization.OrganizationRepository
import com.aicodelearning.organization.ProjectEntity
import com.aicodelearning.organization.ProjectRepository
import com.aicodelearning.organization.TeamEntity
import com.aicodelearning.organization.TeamRepository
import com.aicodelearning.organization.UserEntity
import com.aicodelearning.organization.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
@Profile("local")
class DemoDataInitializer(
    private val organizationRepository: OrganizationRepository,
    private val teamRepository: TeamRepository,
    private val projectRepository: ProjectRepository,
    private val userRepository: UserRepository,
    private val membershipRepository: MembershipRepository,
    private val passwordEncoder: PasswordEncoder,
    @param:Value("\${app.demo-password:demo-password}")
    private val demoPassword: String,
) : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments) {
        val now = Instant.now()
        if (!organizationRepository.existsById("org-demo")) {
            organizationRepository.save(OrganizationEntity(id = "org-demo", name = "Demo Organization", slug = "demo", createdAt = now))
        }
        if (!teamRepository.existsById("team-platform")) {
            teamRepository.save(TeamEntity(id = "team-platform", organizationId = "org-demo", name = "Platform", createdAt = now))
        }
        if (!projectRepository.existsById("project-learning")) {
            projectRepository.save(
                ProjectEntity(
                    id = "project-learning",
                    organizationId = "org-demo",
                    teamId = "team-platform",
                    name = "Learning Platform",
                    createdAt = now,
                ),
            )
        }

        seedUser("u-admin", "admin@example.com", "Admin", "admin", now)
        seedUser("u-contributor", "contributor@example.com", "Contributor", "contributor", now)
        seedUser("u-reviewer", "reviewer@example.com", "Reviewer", "reviewer", now)
        seedUser("u-learner", "learner@example.com", "Learner", "learner", now)
    }

    private fun seedUser(
        id: String,
        email: String,
        displayName: String,
        role: String,
        now: Instant,
    ) {
        if (!userRepository.existsById(id)) {
            userRepository.save(
                UserEntity(
                    id = id,
                    email = email,
                    displayName = displayName,
                    passwordHash = passwordEncoder.encode(demoPassword),
                    createdAt = now,
                ),
            )
        }

        val membershipId = "membership_$id"
        if (!membershipRepository.existsById(membershipId)) {
            membershipRepository.save(
                MembershipEntity(
                    id = membershipId,
                    userId = id,
                    organizationId = "org-demo",
                    teamId = if (role == "admin") null else "team-platform",
                    projectId = if (role == "admin") null else "project-learning",
                    role = role,
                    createdAt = now,
                ),
            )
        }
    }
}
