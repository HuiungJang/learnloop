package com.aicodelearning.auth

import com.aicodelearning.organization.AuthorizationService
import com.aicodelearning.platform.ForbiddenException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionAuthenticationIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var sessionTokenRepository: SessionTokenRepository

    @Autowired
    private lateinit var sessionService: SessionService

    @Autowired
    private lateinit var authorizationService: AuthorizationService

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `valid login returns bearer token and current user`() {
        val session = login("admin@example.com")

        assertNotNull(session.token)
        assertEquals("u-admin", session.user.id)

        val response =
            restTemplate.exchange(
                "/api/me",
                HttpMethod.GET,
                HttpEntity<Void>(bearerHeaders(session.token)),
                UserResponse::class.java,
            )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("admin@example.com", response.body?.email)
    }

    @Test
    fun `missing malformed unknown expired and revoked tokens return unauthorized`() {
        val missing = restTemplate.getForEntity("/api/me", String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, missing.statusCode)

        val malformed =
            restTemplate.exchange("/api/me", HttpMethod.GET, HttpEntity<Void>(headersWithAuthorization("Basic abc")), String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, malformed.statusCode)

        val unknown =
            restTemplate.exchange("/api/me", HttpMethod.GET, HttpEntity<Void>(bearerHeaders("unknown-token")), String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, unknown.statusCode)

        sessionTokenRepository.save(
            SessionTokenEntity(
                id = "session_expired_test",
                userId = "u-admin",
                tokenHash = sha256Hex("expired-token"),
                createdAt = Instant.now().minusSeconds(900),
                expiresAt = Instant.now().minusSeconds(60),
            ),
        )
        val expired =
            restTemplate.exchange("/api/me", HttpMethod.GET, HttpEntity<Void>(bearerHeaders("expired-token")), String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, expired.statusCode)

        val session = login("reviewer@example.com")
        val persisted = sessionTokenRepository.findByTokenHash(sha256Hex(session.token))
        requireNotNull(persisted)
        persisted.revokedAt = Instant.now()
        sessionTokenRepository.save(persisted)

        val revoked =
            restTemplate.exchange("/api/me", HttpMethod.GET, HttpEntity<Void>(bearerHeaders(session.token)), String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, revoked.statusCode)
    }

    @Test
    fun `x user id spoofing is ignored without session token`() {
        val headers =
            HttpHeaders().apply {
                add("x-user-id", "u-admin")
            }

        val response = restTemplate.exchange("/api/me", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `login throttling returns safe rate limit response`() {
        repeat(5) {
            val response = loginRaw("missing-$port@example.com", "wrong-password")
            assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        }

        val throttled = loginRaw("missing-$port@example.com", "wrong-password")

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, throttled.statusCode)
        assertEquals(true, throttled.body?.contains("Rate limit exceeded"))
    }

    @Test
    fun `role policy allows hierarchy and fails closed`() {
        val admin = sessionService.createSession("admin@example.com", "demo-password")?.let { sessionService.authenticate(it.token) }
        val learner = sessionService.createSession("learner@example.com", "demo-password")?.let { sessionService.authenticate(it.token) }

        requireNotNull(admin)
        requireNotNull(learner)

        assertDoesNotThrow {
            authorizationService.requireRole(admin, "org-demo", "reviewer", "team-platform", "project-learning")
        }
        assertThrows(ForbiddenException::class.java) {
            authorizationService.requireRole(learner, "org-demo", "reviewer", "team-platform", "project-learning")
        }
        assertThrows(ForbiddenException::class.java) {
            authorizationService.requireRole(learner, "other-org", "learner")
        }
    }

    @Test
    fun `cors preflight and security headers are present`() {
        val preflightHeaders =
            HttpHeaders().apply {
                origin = "http://localhost:5173"
                accessControlRequestMethod = HttpMethod.GET
            }
        val preflight = restTemplate.exchange("/api/me", HttpMethod.OPTIONS, HttpEntity<Void>(preflightHeaders), String::class.java)

        assertEquals(HttpStatus.OK, preflight.statusCode)
        assertEquals("http://localhost:5173", preflight.headers.accessControlAllowOrigin)

        val health = restTemplate.getForEntity("/api/health", String::class.java)
        assertEquals("nosniff", health.headers.getFirst("X-Content-Type-Options"))
    }

    private fun login(email: String): SessionResponse {
        val response = restTemplate.postForEntity("/api/session", LoginRequest(email = email, password = "demo-password"), SessionResponse::class.java)
        assertEquals(HttpStatus.CREATED, response.statusCode)
        return requireNotNull(response.body)
    }

    private fun loginRaw(
        email: String,
        password: String,
    ) = restTemplate.postForEntity("/api/session", LoginRequest(email = email, password = password), String::class.java)

    private fun headersWithAuthorization(value: String): HttpHeaders =
        HttpHeaders().apply {
            set(HttpHeaders.AUTHORIZATION, value)
        }

    private fun bearerHeaders(token: String): HttpHeaders = headersWithAuthorization("Bearer $token")

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

class KPostgreSQLContainer(imageName: String) : PostgreSQLContainer<KPostgreSQLContainer>(imageName)
