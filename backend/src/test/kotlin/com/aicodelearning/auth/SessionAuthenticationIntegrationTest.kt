package com.aicodelearning.auth

import com.aicodelearning.organization.AuthorizationService
import com.aicodelearning.platform.ForbiddenException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Autowired
    private lateinit var objectMapper: ObjectMapper

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

    @Test
    fun `provider registration redacts credentials and appends audit`() {
        val learner = login("learner@example.com")
        val secret = "personal-provider-secret-1234"

        val created =
            postJson(
                "/api/providers",
                learner.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "provider" to "openai",
                    "model" to "example-model",
                    "scope" to "personal",
                    "credential" to secret,
                    "retentionMode" to "standard",
                ),
            )

        assertEquals(HttpStatus.CREATED, created.statusCode)
        assertFalse(created.body.orEmpty().contains(secret))
        assertTrue(json(created)["provider"]["credentialRef"].asText().startsWith("vault://"))

        val admin = login("admin@example.com")
        val audit = getJson("/api/audit?organizationId=org-demo", admin.token)
        assertEquals(HttpStatus.OK, audit.statusCode)
        assertTrue(audit.body.orEmpty().contains("provider.created"))
        assertFalse(audit.body.orEmpty().contains(secret))
    }

    @Test
    fun `organization provider requires admin role`() {
        val contributor = login("contributor@example.com")

        val response =
            postJson(
                "/api/providers",
                contributor.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "provider" to "anthropic",
                    "model" to "example-model",
                    "scope" to "organization",
                    "credential" to "organization-secret-1234",
                ),
            )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `manual evidence blocks secrets without echoing raw value`() {
        val contributor = login("contributor@example.com")
        val secret = "sk-testtesttesttesttesttesttesttest"
        val created =
            postJson(
                "/api/ingest/manual",
                contributor.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "teamId" to "team-platform",
                    "projectId" to "project-learning",
                    "title" to "Unsafe evidence",
                    "sourceKind" to "code",
                    "content" to "const key = \"$secret\";",
                ),
            )

        assertEquals(HttpStatus.CREATED, created.statusCode)
        assertFalse(created.body.orEmpty().contains(secret))
        assertEquals("blocked_sensitive", json(created)["bundle"]["status"].asText())

        val bundleId = json(created)["bundle"]["id"].asText()
        val learner = login("learner@example.com")
        val learnerRead = getJson("/api/evidence/$bundleId", learner.token)
        assertEquals(HttpStatus.FORBIDDEN, learnerRead.statusCode)

        val contributorRead = getJson("/api/evidence/$bundleId", contributor.token)
        assertEquals(HttpStatus.OK, contributorRead.statusCode)
        assertEquals(true, json(contributorRead)["evidenceItems"][0]["contentText"].isNull)
    }

    @Test
    fun `manual evidence stores clean pull request metadata`() {
        val contributor = login("contributor@example.com")
        val created =
            postJson(
                "/api/ingest/manual",
                contributor.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "teamId" to "team-platform",
                    "projectId" to "project-learning",
                    "title" to "Repository diff",
                    "sourceKind" to "pull_request",
                    "repositoryUrl" to "https://github.com/example/repo",
                    "pullRequestUrl" to "https://github.com/example/repo/pull/1",
                    "commitSha" to "0123456789abcdef",
                    "branchName" to "feature/example",
                    "filePaths" to listOf("src/App.tsx"),
                    "provenance" to mapOf("source" to "manual"),
                    "content" to "function example() { return true; }",
                ),
            )

        assertEquals(HttpStatus.CREATED, created.statusCode)
        assertEquals("ready", json(created)["bundle"]["status"].asText())

        val bundleId = json(created)["bundle"]["id"].asText()
        val detail = getJson("/api/evidence/$bundleId", contributor.token)

        assertEquals(HttpStatus.OK, detail.statusCode)
        assertTrue(json(detail)["bundle"]["filePathsJson"].asText().contains("src/App.tsx"))
        assertEquals("function example() { return true; }", json(detail)["evidenceItems"][0]["contentText"].asText())

        val duplicate =
            postJson(
                "/api/ingest/manual",
                contributor.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "teamId" to "team-platform",
                    "projectId" to "project-learning",
                    "title" to "Repository diff duplicate",
                    "sourceKind" to "pull_request",
                    "content" to "function example() { return true; }",
                ),
            )
        assertEquals(bundleId, json(duplicate)["bundle"]["id"].asText())
    }

    @Test
    fun `oversized evidence returns validation error`() {
        val contributor = login("contributor@example.com")
        val response =
            postJson(
                "/api/ingest/manual",
                contributor.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "title" to "Too large",
                    "content" to "a".repeat(20_001),
                ),
            )

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
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

    private fun postJson(
        path: String,
        token: String,
        body: Map<String, Any?>,
    ) = restTemplate.exchange(path, HttpMethod.POST, HttpEntity(body, bearerHeaders(token)), String::class.java)

    private fun getJson(
        path: String,
        token: String,
    ) = restTemplate.exchange(path, HttpMethod.GET, HttpEntity<Void>(bearerHeaders(token)), String::class.java)

    private fun json(response: org.springframework.http.ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

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
