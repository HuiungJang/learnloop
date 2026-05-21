package com.aicodelearning.auth

import com.aicodelearning.organization.MembershipEntity
import com.aicodelearning.organization.MembershipRepository
import com.aicodelearning.organization.UserEntity
import com.aicodelearning.organization.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

@Testcontainers
@ActiveProfiles("local")
@Import(RunnerExecutorTestConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalOwnerModeIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var membershipRepository: MembershipRepository

    @Autowired
    private lateinit var sessionTokenRepository: SessionTokenRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Test
    fun `local mode seeds exactly one owner profile and no demo role users`() {
        val users = userRepository.findAll().sortedBy { it.id }

        assertEquals(listOf("u-local-owner"), users.map { it.id })
        assertEquals("owner@local.learnloop", users.single().email)
        assertEquals("Local Owner", users.single().displayName)
        assertNull(userRepository.findByEmailIgnoreCase("admin@example.com"))
        assertNull(userRepository.findByEmailIgnoreCase("reviewer@example.com"))
        assertNull(userRepository.findByEmailIgnoreCase("learner@example.com"))

        val memberships = membershipRepository.findByUserId("u-local-owner")
        assertEquals(1, memberships.size)
        assertEquals("org-demo", memberships.single().organizationId)
    }

    @Test
    fun `fresh local install can create owner session without role selection`() {
        val response =
            restTemplate.postForEntity(
                "/api/session",
                LoginRequest(email = "owner@local.learnloop", password = "demo-password"),
                SessionResponse::class.java,
            )

        assertEquals(HttpStatus.CREATED, response.statusCode)
        val body = requireNotNull(response.body)
        assertNotNull(body.token)
        assertEquals("u-local-owner", body.user.id)
        assertEquals("owner@local.learnloop", body.user.email)
        assertEquals(1, body.user.memberships.size)
    }

    @Test
    fun `local owner mode rejects self registration`() {
        val response =
            restTemplate.postForEntity(
                "/api/register",
                RegisterRequest(
                    email = "new-user@example.com",
                    displayName = "New User",
                    password = "LocalSecret1234!",
                ),
                String::class.java,
            )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertEquals(1L, userRepository.count())
    }

    @Test
    fun `local owner mode rejects legacy non owner login and session tokens`() {
        val now = Instant.now()
        val legacyToken = "legacy-admin-token"
        try {
            userRepository.save(
                UserEntity(
                    id = "u-admin",
                    email = "admin@example.com",
                    displayName = "Admin",
                    passwordHash = passwordEncoder.encode("demo-password"),
                    createdAt = now,
                ),
            )
            membershipRepository.save(
                MembershipEntity(
                    id = "membership_u-admin",
                    userId = "u-admin",
                    organizationId = "org-demo",
                    teamId = "team-platform",
                    projectId = "project-learning",
                    role = "admin",
                    createdAt = now,
                ),
            )

            val login =
                restTemplate.postForEntity(
                    "/api/session",
                    LoginRequest(email = "admin@example.com", password = "demo-password"),
                    String::class.java,
                )
            assertEquals(HttpStatus.UNAUTHORIZED, login.statusCode)

            sessionTokenRepository.save(
                SessionTokenEntity(
                    id = "session_legacy_admin",
                    userId = "u-admin",
                    tokenHash = sha256Hex(legacyToken),
                    createdAt = now,
                    expiresAt = now.plusSeconds(3600),
                ),
            )
            val oldSession =
                restTemplate.exchange(
                    "/api/me",
                    HttpMethod.GET,
                    HttpEntity<Void>(
                        HttpHeaders().apply {
                            setBearerAuth(legacyToken)
                        },
                    ),
                    String::class.java,
                )
            assertEquals(HttpStatus.UNAUTHORIZED, oldSession.statusCode)
        } finally {
            if (sessionTokenRepository.existsById("session_legacy_admin")) {
                sessionTokenRepository.deleteById("session_legacy_admin")
            }
            if (membershipRepository.existsById("membership_u-admin")) {
                membershipRepository.deleteById("membership_u-admin")
            }
            if (userRepository.existsById("u-admin")) {
                userRepository.deleteById("u-admin")
            }
        }
    }

    companion object {
        @Container
        @JvmField
        val postgres = KPostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun configurePostgres(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
