package com.aicodelearning.auth

import com.aicodelearning.organization.MembershipEntity
import com.aicodelearning.organization.MembershipRepository
import com.aicodelearning.organization.MembershipSummary
import com.aicodelearning.organization.OrganizationEntity
import com.aicodelearning.organization.OrganizationRepository
import com.aicodelearning.organization.ProjectEntity
import com.aicodelearning.organization.ProjectRepository
import com.aicodelearning.organization.TeamEntity
import com.aicodelearning.organization.TeamRepository
import com.aicodelearning.organization.UserEntity
import com.aicodelearning.organization.UserRepository
import com.aicodelearning.platform.ConflictException
import com.aicodelearning.platform.prefixedId
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class SessionService(
    private val userRepository: UserRepository,
    private val organizationRepository: OrganizationRepository,
    private val teamRepository: TeamRepository,
    private val projectRepository: ProjectRepository,
    private val membershipRepository: MembershipRepository,
    private val sessionTokenRepository: SessionTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    @param:Value("\${app.session.ttl:PT8H}")
    private val sessionTtl: Duration,
) {
    @Transactional
    fun register(
        email: String,
        displayName: String,
        password: String,
    ): CreatedSession {
        val normalizedEmail = email.trim().lowercase()
        if (userRepository.findByEmailIgnoreCase(normalizedEmail) != null) {
            throw ConflictException("Email is already registered")
        }

        val now = Instant.now()
        ensureDefaultWorkspace(now)
        val user =
            try {
                userRepository.save(
                    UserEntity(
                        id = prefixedId("user"),
                        email = normalizedEmail,
                        displayName = displayName.trim(),
                        passwordHash = passwordEncoder.encode(password),
                        createdAt = now,
                    ),
                )
            } catch (_: DataIntegrityViolationException) {
                throw ConflictException("Email is already registered")
            }

        membershipRepository.save(
            MembershipEntity(
                id = prefixedId("membership"),
                userId = user.id,
                organizationId = "org-demo",
                teamId = "team-platform",
                projectId = "project-learning",
                role = "contributor",
                createdAt = now,
            ),
        )

        return requireNotNull(createSession(user.email, password))
    }

    @Transactional
    fun createSession(
        email: String,
        password: String,
    ): CreatedSession? {
        val user = userRepository.findByEmailIgnoreCase(email.trim().lowercase()) ?: return null
        if (user.deactivatedAt != null || !passwordEncoder.matches(password, user.passwordHash)) {
            return null
        }

        val token = createSessionToken()
        val now = Instant.now()
        val session =
            SessionTokenEntity(
                id = "session_${UUID.randomUUID()}",
                userId = user.id,
                tokenHash = sha256Hex(token),
                createdAt = now,
                expiresAt = now.plus(sessionTtl),
            )

        sessionTokenRepository.save(session)

        return CreatedSession(
            token = token,
            user = user.toCurrentUser(),
            expiresAt = session.expiresAt,
        )
    }

    @Transactional
    fun authenticate(token: String): CurrentUser? {
        val session = sessionTokenRepository.findByTokenHash(sha256Hex(token)) ?: return null
        val now = Instant.now()
        if (session.revokedAt != null || !session.expiresAt.isAfter(now)) {
            return null
        }

        val user = userRepository.findById(session.userId).orElse(null) ?: return null
        if (user.deactivatedAt != null) {
            return null
        }

        session.lastUsedAt = now
        return user.toCurrentUser()
    }

    private fun ensureDefaultWorkspace(now: Instant) {
        if (!organizationRepository.existsById("org-demo")) {
            organizationRepository.save(
                OrganizationEntity(id = "org-demo", name = "Demo Organization", slug = "demo", createdAt = now),
            )
        }
        if (!teamRepository.existsById("team-platform")) {
            teamRepository.save(
                TeamEntity(id = "team-platform", organizationId = "org-demo", name = "Platform", createdAt = now),
            )
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
    }

    private fun UserEntity.toCurrentUser(): CurrentUser =
        CurrentUser(
            id = id,
            email = email,
            displayName = displayName,
            memberships =
                membershipRepository
                    .findByUserId(id)
                    .map {
                        MembershipSummary(
                            organizationId = it.organizationId,
                            teamId = it.teamId,
                            projectId = it.projectId,
                            role = it.role,
                        )
                    },
        )
}

data class CreatedSession(
    val token: String,
    val user: CurrentUser,
    val expiresAt: Instant,
)
