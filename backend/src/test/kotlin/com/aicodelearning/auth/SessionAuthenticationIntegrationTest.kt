package com.aicodelearning.auth

import com.aicodelearning.evidence.EvidenceItemEntity
import com.aicodelearning.evidence.EvidenceItemRepository
import com.aicodelearning.evidence.SourceBundleEntity
import com.aicodelearning.evidence.SourceBundleRepository
import com.aicodelearning.learning.PatternCardEntity
import com.aicodelearning.learning.PatternCardRepository
import com.aicodelearning.learning.ReviewTaskEntity
import com.aicodelearning.learning.ReviewTaskRepository
import com.aicodelearning.organization.AuthorizationService
import com.aicodelearning.organization.ProjectEntity
import com.aicodelearning.organization.ProjectRepository
import com.aicodelearning.organization.TeamEntity
import com.aicodelearning.organization.TeamRepository
import com.aicodelearning.platform.ForbiddenException
import com.aicodelearning.source.SourceLinkEntity
import com.aicodelearning.source.SourceLinkRepository
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
import java.math.BigDecimal
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

    @Autowired
    private lateinit var teamRepository: TeamRepository

    @Autowired
    private lateinit var projectRepository: ProjectRepository

    @Autowired
    private lateinit var sourceBundleRepository: SourceBundleRepository

    @Autowired
    private lateinit var evidenceItemRepository: EvidenceItemRepository

    @Autowired
    private lateinit var sourceLinkRepository: SourceLinkRepository

    @Autowired
    private lateinit var patternCardRepository: PatternCardRepository

    @Autowired
    private lateinit var reviewTaskRepository: ReviewTaskRepository

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
    fun `registration creates contributor session without echoing password`() {
        val email = "new-user-$port-${System.nanoTime()}@example.com"
        val password = "local-secret-1234"

        val response =
            restTemplate.postForEntity(
                "/api/register",
                RegisterRequest(email = email, displayName = "New User", password = password),
                String::class.java,
            )

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertFalse(response.body.orEmpty().contains(password))
        val body = json(response)
        assertEquals(email, body["user"]["email"].asText())
        assertEquals(true, body["token"].asText().isNotBlank())
        assertEquals("contributor", body["user"]["memberships"][0]["role"].asText())
    }

    @Test
    fun `registration rejects duplicate email`() {
        val email = "duplicate-$port-${System.nanoTime()}@example.com"
        val request = RegisterRequest(email = email, displayName = "Duplicate User", password = "local-secret-1234")

        val created = restTemplate.postForEntity("/api/register", request, String::class.java)
        assertEquals(HttpStatus.CREATED, created.statusCode)

        val duplicate = restTemplate.postForEntity("/api/register", request, String::class.java)
        assertEquals(HttpStatus.CONFLICT, duplicate.statusCode)
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

    @Test
    fun `core learning workflow publishes card and accepts learner submission`() {
        val contributor = login("contributor@example.com")
        val code =
            postJson(
                "/api/ingest/manual",
                contributor.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "teamId" to "team-platform",
                    "projectId" to "project-learning",
                    "title" to "Timeout client code",
                    "sourceKind" to "code",
                    "content" to "async function fetchOrder(client) { return client.get('/orders', { timeout: 3000 }); }",
                ),
            )
        val conversation =
            postJson(
                "/api/ingest/codex-obsidian",
                contributor.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "teamId" to "team-platform",
                    "projectId" to "project-learning",
                    "exportData" to
                        mapOf(
                            "schemaVersion" to 1,
                            "title" to "Timeout guidance",
                            "conversations" to
                                listOf(
                                    mapOf(
                                        "messages" to
                                            listOf(
                                                mapOf("role" to "user", "content" to "Add timeout handling to API client code."),
                                                mapOf("role" to "assistant", "content" to "Use explicit timeout behavior."),
                                            ),
                                    ),
                                ),
                        ),
                ),
            )
        val links =
            postJson(
                "/api/source-links/suggest",
                contributor.token,
                mapOf(
                    "conversationBundleId" to json(conversation)["bundle"]["id"].asText(),
                    "codeBundleId" to json(code)["bundle"]["id"].asText(),
                ),
            )
        val linkId = json(links)["links"][0]["id"].asText()
        val confirmed = postJson("/api/source-links/$linkId/confirm", contributor.token, emptyMap())
        assertEquals("confirmed", json(confirmed)["status"].asText())

        val generated =
            postJson(
                "/api/generation/run",
                contributor.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "providerConfigId" to "provider-local-mock",
                    "sourceLinkIds" to listOf(linkId),
                    "visibility" to "organization",
                ),
            )
        assertEquals(HttpStatus.CREATED, generated.statusCode)
        val reviewTaskId = json(generated)["reviewTask"]["id"].asText()
        val cardId = json(generated)["patternCard"]["id"].asText()

        val reviewer = login("reviewer@example.com")
        val queue = getJson("/api/review/tasks?organizationId=org-demo", reviewer.token)
        assertTrue(queue.body.orEmpty().contains(reviewTaskId))
        val decision =
            postJson(
                "/api/review/tasks/$reviewTaskId/decision",
                reviewer.token,
                mapOf("decision" to "approve", "comment" to "Looks safe."),
            )
        assertEquals("approved", json(decision)["reviewTask"]["status"].asText())

        val learner = login("learner@example.com")
        val library = getJson("/api/library?organizationId=org-demo", learner.token)
        assertTrue(json(library)["cards"].any { it["id"].asText() == cardId })

        val detail = getJson("/api/pattern-cards/$cardId", learner.token)
        val problemId = json(detail)["patternCard"]["problems"][0]["id"].asText()
        assertEquals(true, json(detail)["patternCard"]["problems"][0]["referenceAnswer"].isNull)

        val submission =
            postJson(
                "/api/problems/$problemId/submissions",
                learner.token,
                mapOf("textAnswer" to "Use timeout handling at API boundaries.", "resultStatus" to "self_marked_complete"),
            )
        assertEquals(HttpStatus.CREATED, submission.statusCode)
        assertFalse(json(submission)["patternCard"]["problems"][0]["referenceAnswer"].isNull)

        val progress = getJson("/api/progress?organizationId=org-demo", learner.token)
        assertTrue(json(progress)["proficiency"].size() > 0)
    }

    @Test
    fun `source link suggestion rejects inaccessible code bundle scope`() {
        ensureOtherScope()
        val contributor = login("contributor@example.com")
        val conversation =
            postJson(
                "/api/ingest/manual",
                contributor.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "teamId" to "team-platform",
                    "projectId" to "project-learning",
                    "title" to "Accessible conversation",
                    "sourceKind" to "conversation",
                    "content" to "Discuss a scoped implementation pattern.",
                ),
            )
        val inaccessibleCodeBundleId = saveScopedBundle("Inaccessible code", "code", "class InternalPattern")

        val suggested =
            postJson(
                "/api/source-links/suggest",
                contributor.token,
                mapOf(
                    "conversationBundleId" to json(conversation)["bundle"]["id"].asText(),
                    "codeBundleId" to inaccessibleCodeBundleId,
                ),
            )

        assertEquals(HttpStatus.FORBIDDEN, suggested.statusCode)
    }

    @Test
    fun `generation rejects confirmed source links outside contributor scope`() {
        ensureOtherScope()
        val contributor = login("contributor@example.com")
        val conversation =
            postJson(
                "/api/ingest/manual",
                contributor.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "teamId" to "team-platform",
                    "projectId" to "project-learning",
                    "title" to "Accessible conversation",
                    "sourceKind" to "conversation",
                    "content" to "Generate from linked evidence.",
                ),
            )
        val inaccessibleCodeBundleId = saveScopedBundle("Inaccessible generation code", "code", "fun privateLogic() = true")
        val link =
            sourceLinkRepository.save(
                SourceLinkEntity(
                    id = "link_foreign_${System.nanoTime()}",
                    organizationId = "org-demo",
                    conversationBundleId = json(conversation)["bundle"]["id"].asText(),
                    codeBundleId = inaccessibleCodeBundleId,
                    status = "confirmed",
                    confidence = BigDecimal("0.90000"),
                    createdByUserId = "u-admin",
                    decidedByUserId = "u-admin",
                    createdAt = Instant.now(),
                    decidedAt = Instant.now(),
                ),
            )

        val generated =
            postJson(
                "/api/generation/run",
                contributor.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "providerConfigId" to "provider-local-mock",
                    "sourceLinkIds" to listOf(link.id),
                    "visibility" to "organization",
                ),
            )

        assertEquals(HttpStatus.FORBIDDEN, generated.statusCode)
    }

    @Test
    fun `generation rejects personal provider owned by another user`() {
        val contributor = login("contributor@example.com")
        val learner = login("learner@example.com")
        val linkId = createConfirmedAccessibleSourceLink(contributor.token)
        val provider =
            postJson(
                "/api/providers",
                learner.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "provider" to "openai",
                    "model" to "private-model",
                    "scope" to "personal",
                    "credential" to "learner-owned-secret-1234",
                ),
            )
        assertEquals(HttpStatus.CREATED, provider.statusCode)

        val generated =
            postJson(
                "/api/generation/run",
                contributor.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "providerConfigId" to json(provider)["provider"]["id"].asText(),
                    "sourceLinkIds" to listOf(linkId),
                    "visibility" to "private",
                ),
            )

        assertEquals(HttpStatus.FORBIDDEN, generated.statusCode)
    }

    @Test
    fun `generation idempotency key cannot replay another users draft`() {
        val contributor = login("contributor@example.com")
        val learner = login("learner@example.com")
        val linkId = createConfirmedAccessibleSourceLink(contributor.token)
        val idempotencyKey = "idem-${System.nanoTime()}"
        val generated =
            postJson(
                "/api/generation/run",
                contributor.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "providerConfigId" to "provider-local-mock",
                    "sourceLinkIds" to listOf(linkId),
                    "visibility" to "organization",
                    "idempotencyKey" to idempotencyKey,
                ),
            )
        assertEquals(HttpStatus.CREATED, generated.statusCode)

        val replay =
            postJson(
                "/api/generation/run",
                learner.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "providerConfigId" to "provider-local-mock",
                    "sourceLinkIds" to listOf(linkId),
                    "visibility" to "organization",
                    "idempotencyKey" to idempotencyKey,
                ),
            )

        assertEquals(HttpStatus.FORBIDDEN, replay.statusCode)
    }

    @Test
    fun `review queue hides tasks outside reviewer scope`() {
        ensureOtherScope()
        val reviewer = login("reviewer@example.com")
        val taskId = saveScopedReviewTask("Foreign draft queue")

        val queue = getJson("/api/review/tasks?organizationId=org-demo", reviewer.token)

        assertEquals(HttpStatus.OK, queue.statusCode)
        assertFalse(queue.body.orEmpty().contains(taskId))
    }

    @Test
    fun `review decision rejects tasks outside reviewer scope`() {
        ensureOtherScope()
        val reviewer = login("reviewer@example.com")
        val taskId = saveScopedReviewTask("Foreign draft decision")

        val decision =
            postJson(
                "/api/review/tasks/$taskId/decision",
                reviewer.token,
                mapOf("decision" to "approve", "comment" to "Should not be allowed."),
            )

        assertEquals(HttpStatus.FORBIDDEN, decision.statusCode)
    }

    @Test
    fun `library hides published cards outside learner scope`() {
        ensureOtherScope()
        val learner = login("learner@example.com")
        val cardId = saveScopedCard("Foreign published card", publicationStatus = "published")

        val library = getJson("/api/library?organizationId=org-demo", learner.token)

        assertEquals(HttpStatus.OK, library.statusCode)
        assertFalse(library.body.orEmpty().contains(cardId))
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

    private fun ensureOtherScope() {
        if (!teamRepository.existsById("team-security")) {
            teamRepository.save(TeamEntity(id = "team-security", organizationId = "org-demo", name = "Security", createdAt = Instant.now()))
        }
        if (!projectRepository.existsById("project-security")) {
            projectRepository.save(
                ProjectEntity(
                    id = "project-security",
                    organizationId = "org-demo",
                    teamId = "team-security",
                    name = "Security Project",
                    createdAt = Instant.now(),
                ),
            )
        }
    }

    private fun saveScopedBundle(
        title: String,
        sourceKind: String,
        content: String,
    ): String {
        val bundleId = "bundle_foreign_${System.nanoTime()}"
        sourceBundleRepository.save(
            SourceBundleEntity(
                id = bundleId,
                organizationId = "org-demo",
                teamId = "team-security",
                projectId = "project-security",
                createdByUserId = "u-admin",
                title = title,
                sourceKind = sourceKind,
                status = "ready",
                contentHash = sha256Hex(content),
                createdAt = Instant.now(),
            ),
        )
        evidenceItemRepository.save(
            EvidenceItemEntity(
                id = "evidence_$bundleId",
                bundleId = bundleId,
                itemType = sourceKind,
                contentText = content,
                contentHash = sha256Hex(content),
                createdAt = Instant.now(),
            ),
        )
        return bundleId
    }

    private fun saveScopedReviewTask(title: String): String {
        val cardId = saveScopedCard(title, publicationStatus = "draft")
        val taskId = "review_foreign_${System.nanoTime()}"
        reviewTaskRepository.save(
            ReviewTaskEntity(
                id = taskId,
                patternCardId = cardId,
                organizationId = "org-demo",
                authorUserId = "u-admin",
                status = "open",
                createdAt = Instant.now(),
            ),
        )
        return taskId
    }

    private fun saveScopedCard(
        title: String,
        publicationStatus: String,
    ): String {
        val cardId = "card_foreign_${System.nanoTime()}"
        patternCardRepository.save(
            PatternCardEntity(
                id = cardId,
                organizationId = "org-demo",
                teamId = "team-security",
                projectId = "project-security",
                generationRunId = null,
                createdByUserId = "u-admin",
                title = title,
                summary = "A scoped card that must not be visible to platform scoped users.",
                visibility = "organization",
                publicationStatus = publicationStatus,
                createdAt = Instant.now(),
                publishedAt = if (publicationStatus == "published") Instant.now() else null,
            ),
        )
        return cardId
    }

    private fun createConfirmedAccessibleSourceLink(contributorToken: String): String {
        val code =
            postJson(
                "/api/ingest/manual",
                contributorToken,
                mapOf(
                    "organizationId" to "org-demo",
                    "teamId" to "team-platform",
                    "projectId" to "project-learning",
                    "title" to "Accessible provider code",
                    "sourceKind" to "code",
                    "content" to "class ProviderScopedPattern",
                ),
            )
        val conversation =
            postJson(
                "/api/ingest/manual",
                contributorToken,
                mapOf(
                    "organizationId" to "org-demo",
                    "teamId" to "team-platform",
                    "projectId" to "project-learning",
                    "title" to "Accessible provider conversation",
                    "sourceKind" to "conversation",
                    "content" to "Use a provider for this generated pattern.",
                ),
            )
        val suggested =
            postJson(
                "/api/source-links/suggest",
                contributorToken,
                mapOf(
                    "conversationBundleId" to json(conversation)["bundle"]["id"].asText(),
                    "codeBundleId" to json(code)["bundle"]["id"].asText(),
                ),
            )
        val linkId = json(suggested)["links"][0]["id"].asText()
        val confirmed = postJson("/api/source-links/$linkId/confirm", contributorToken, emptyMap())
        assertEquals("confirmed", json(confirmed)["status"].asText())
        return linkId
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

class KPostgreSQLContainer(imageName: String) : PostgreSQLContainer<KPostgreSQLContainer>(imageName)
