package com.aicodelearning.auth

import com.aicodelearning.organization.MembershipRepository
import com.aicodelearning.organization.MembershipSummary
import com.aicodelearning.organization.UserEntity
import com.aicodelearning.organization.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class SessionService(
    private val userRepository: UserRepository,
    private val membershipRepository: MembershipRepository,
    private val sessionTokenRepository: SessionTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    @param:Value("\${app.session.ttl:PT8H}")
    private val sessionTtl: Duration,
) {
    @Transactional
    fun createSession(
        email: String,
        password: String,
    ): CreatedSession? {
        val user = userRepository.findByEmailIgnoreCase(email) ?: return null
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
