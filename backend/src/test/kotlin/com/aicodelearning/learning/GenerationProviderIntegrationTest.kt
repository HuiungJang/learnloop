package com.aicodelearning.learning

import com.aicodelearning.auth.KPostgreSQLContainer
import com.aicodelearning.auth.LoginRequest
import com.aicodelearning.auth.RunnerExecutorTestConfiguration
import com.aicodelearning.auth.SessionResponse
import com.aicodelearning.evidence.EvidenceItemEntity
import com.aicodelearning.evidence.EvidenceItemRepository
import com.aicodelearning.evidence.LocalAiSessionPolicy
import com.aicodelearning.evidence.SourceBundleEntity
import com.aicodelearning.evidence.SourceBundleRepository
import com.aicodelearning.provider.FakeProviderServer
import com.aicodelearning.provider.ProviderEntity
import com.aicodelearning.provider.ProviderRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.Instant

@Testcontainers
@ActiveProfiles("local")
@Import(RunnerExecutorTestConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GenerationProviderIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var sourceBundleRepository: SourceBundleRepository

    @Autowired
    private lateinit var evidenceItemRepository: EvidenceItemRepository

    @Autowired
    private lateinit var generationRunRepository: GenerationRunRepository

    @Autowired
    private lateinit var patternCardRepository: PatternCardRepository

    @Autowired
    private lateinit var patternTagLinkRepository: PatternTagLinkRepository

    @Autowired
    private lateinit var problemRepository: ProblemRepository

    @Autowired
    private lateinit var reviewTaskRepository: ReviewTaskRepository

    @Autowired
    private lateinit var providerRepository: ProviderRepository

    @Test
    fun `non local provider calls HTTP and persists provider output`() {
        FakeProviderServer.start().use { server ->
            val owner = login()
            val providerId = createProvider(owner.token, server.baseUrl)
            val bundleId = createCuratedBundle("provider-success")
            server.enqueue(body = openAiResponseBody(patternOutput(title = "Provider Returned Pattern")))

            val generated = generate(owner.token, providerId, bundleId)

            assertEquals(HttpStatus.CREATED, generated.statusCode)
            assertEquals("Provider Returned Pattern", json(generated)["patternCard"]["title"].asText())
            val reviewTaskId = json(generated)["reviewTask"]["id"].asText()
            val decision =
                postJson(
                    "/api/review/tasks/$reviewTaskId/decision",
                    owner.token,
                    mapOf("decision" to "approve", "comment" to "Local owner approved generated practice."),
                )
            assertEquals(HttpStatus.OK, decision.statusCode)
            assertEquals("approved", json(decision)["reviewTask"]["status"].asText())
            assertEquals(1, server.awaitRequestCount(1).size)
            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/v1/responses", request.path)
            assertEquals("Bearer fake-provider-secret-1234", request.header("authorization"))
            assertTrue(request.body.contains("\"model\":\"fake-model\""))
            assertTrue(request.body.contains("\"json_schema\""))
        }
    }

    @Test
    fun `invalid provider output persists failed run and creates no assets`() {
        FakeProviderServer.start().use { server ->
            val owner = login()
            val providerId = createProvider(owner.token, server.baseUrl)
            val bundleId = createCuratedBundle("provider-invalid-json")
            val cardsBefore = patternCardRepository.count()
            val tagLinksBefore = patternTagLinkRepository.count()
            val problemsBefore = problemRepository.count()
            val reviewsBefore = reviewTaskRepository.count()
            server.enqueue(body = openAiResponseBody("not json"))

            val failed = generate(owner.token, providerId, bundleId)

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failed.statusCode)
            assertEquals(1, server.awaitRequestCount(1).size)
            val fields = json(failed)["error"]["fields"]
            assertEquals("provider_invalid_json", fields["failureCode"].asText())
            val run = generationRunRepository.findById(fields["generationRunId"].asText()).orElseThrow()
            assertEquals("failed", run.status)
            assertEquals("provider_invalid_json", run.failureCode)
            assertEquals(cardsBefore, patternCardRepository.count())
            assertEquals(tagLinksBefore, patternTagLinkRepository.count())
            assertEquals(problemsBefore, problemRepository.count())
            assertEquals(reviewsBefore, reviewTaskRepository.count())

            val trace = getJson("/api/conversion-traces?organizationId=org-demo", owner.token)
            assertEquals(HttpStatus.OK, trace.statusCode)
            val traceItem = json(trace)["traces"].first { it["generationRunId"].asText() == run.id }
            assertEquals("failed", traceItem["status"].asText())
            assertEquals("provider_invalid_json", traceItem["failureCode"].asText())
            assertEquals(bundleId, traceItem["source"]["sourceBundleId"].asText())
            assertTrue(traceItem["pattern"].isNull)
            assertTrue(traceItem["exercise"].isNull)
        }
    }

    @Test
    fun `failed generation is idempotent and does not call provider twice`() {
        FakeProviderServer.start().use { server ->
            val owner = login()
            val providerId = createProvider(owner.token, server.baseUrl)
            val bundleId = createCuratedBundle("provider-failed-idempotency")
            val idempotencyKey = "idem-provider-failure-${System.nanoTime()}"
            server.enqueue(body = openAiResponseBody("not json"))

            val first = generate(owner.token, providerId, bundleId, idempotencyKey)
            val second = generate(owner.token, providerId, bundleId, idempotencyKey)

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, first.statusCode)
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, second.statusCode)
            assertEquals(1, server.awaitRequestCount(1).size)
            assertEquals(
                json(first)["error"]["fields"]["generationRunId"].asText(),
                json(second)["error"]["fields"]["generationRunId"].asText(),
            )
        }
    }

    @Test
    fun `legacy hash only non local provider fails safely without HTTP fallback`() {
        FakeProviderServer.start().use { server ->
            val owner = login()
            val providerId = "provider_legacy_${System.nanoTime()}"
            providerRepository.save(
                ProviderEntity(
                    id = providerId,
                    organizationId = "org-demo",
                    ownerUserId = "u-local-owner",
                    createdByUserId = "u-local-owner",
                    provider = "openai",
                    model = "legacy-model",
                    baseUrl = server.baseUrl,
                    scope = "personal",
                    authType = "api_key",
                    retentionMode = "standard",
                    credentialRef = "sha256:legacy",
                    credentialFingerprint = "legacyfingerprint",
                    secretPreview = "***1234",
                    status = "active",
                    orgApproved = false,
                    createdAt = Instant.now(),
                ),
            )
            val bundleId = createCuratedBundle("provider-legacy-hash-only")

            val failed = generate(owner.token, providerId, bundleId)

            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, failed.statusCode)
            assertEquals("provider_configuration_invalid", json(failed)["error"]["fields"]["failureCode"].asText())
            assertEquals(0, server.requests.size)
        }
    }

    @Test
    fun `provider HTTP failure returns safe error and creates no assets`() {
        FakeProviderServer.start().use { server ->
            val owner = login()
            val providerId = createProvider(owner.token, server.baseUrl)
            val bundleId = createCuratedBundle("provider-http-failure")
            val secretResponse = "provider-body-should-not-leak"
            server.enqueue(status = 401, body = """{"error":"$secretResponse"}""")

            val failed = generate(owner.token, providerId, bundleId)

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failed.statusCode)
            assertEquals(1, server.awaitRequestCount(1).size)
            val body = failed.body.orEmpty()
            assertTrue(body.contains("Provider generation failed"))
            assertTrue(body.contains("provider_http_error"))
            assertFalse(body.contains(secretResponse))
            assertFalse(body.contains("fake-provider-secret-1234"))
            val audit = getJson("/api/audit?organizationId=org-demo", owner.token)
            assertEquals(HttpStatus.OK, audit.statusCode)
            assertFalse(audit.body.orEmpty().contains(secretResponse))
            assertFalse(audit.body.orEmpty().contains("fake-provider-secret-1234"))
        }
    }

    @Test
    fun `local mock generation makes zero fake provider requests`() {
        FakeProviderServer.start().use { server ->
            val owner = login()
            val bundleId = createCuratedBundle("local-mock-zero-http")

            val generated = generate(owner.token, "provider-local-mock", bundleId)

            assertEquals(HttpStatus.CREATED, generated.statusCode)
            assertTrue(json(generated)["patternCard"]["title"].asText().contains("Practice Pattern"))
            assertEquals(0, server.requests.size)
        }
    }

    private fun login(): SessionResponse {
        val response = restTemplate.postForEntity("/api/session", LoginRequest("owner@local.learnloop", "demo-password"), SessionResponse::class.java)
        assertEquals(HttpStatus.CREATED, response.statusCode)
        return requireNotNull(response.body)
    }

    private fun createProvider(
        token: String,
        baseUrl: String,
    ): String {
        val response =
            postJson(
                "/api/providers",
                token,
                mapOf(
                    "organizationId" to "org-demo",
                    "provider" to "openai",
                    "model" to "fake-model",
                    "baseUrl" to baseUrl,
                    "scope" to "personal",
                    "credential" to "fake-provider-secret-1234",
                    "retentionMode" to "standard",
                ),
            )
        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertFalse(response.body.orEmpty().contains("fake-provider-secret-1234"))
        return json(response)["provider"]["id"].asText()
    }

    private fun createCuratedBundle(suffix: String): String {
        val now = Instant.now()
        val bundleId = "bundle_provider_${suffix}_${System.nanoTime()}"
        sourceBundleRepository.save(
            SourceBundleEntity(
                id = bundleId,
                organizationId = "org-demo",
                teamId = "team-platform",
                projectId = "project-learning",
                createdByUserId = "u-local-owner",
                title = "Provider test $suffix",
                sourceKind = LocalAiSessionPolicy.SOURCE_KIND,
                status = LocalAiSessionPolicy.STATUS_GENERATION_ELIGIBLE,
                repositoryUrl = "local://repo/provider-test",
                filePathsJson = """["src/client.ts"]""",
                provenanceJson = "{}",
                contentHash = sha256Like("bundle-$suffix"),
                secretFindingsJson = "[]",
                createdAt = now,
                userAttribution = LocalAiSessionPolicy.USER_ATTRIBUTION_USE_FOR_GENERATION,
                attributionConfidence = BigDecimal("0.90"),
                attributionReasonsJson = """["test"]""",
            ),
        )
        evidenceItemRepository.saveAll(
            listOf(
                EvidenceItemEntity(
                    id = "${bundleId}_prompt",
                    bundleId = bundleId,
                    itemType = LocalAiSessionPolicy.ITEM_TYPE_PROMPT,
                    contentText = "Build a React Query client with retry timeout behavior.",
                    contentHash = sha256Like("prompt-$suffix"),
                    createdAt = now,
                ),
                EvidenceItemEntity(
                    id = "${bundleId}_response",
                    bundleId = bundleId,
                    itemType = LocalAiSessionPolicy.ITEM_TYPE_AI_RESPONSE,
                    contentText = "Use an API client boundary and test authorization timeout errors.",
                    contentHash = sha256Like("response-$suffix"),
                    createdAt = now,
                ),
            ),
        )
        return bundleId
    }

    private fun generate(
        token: String,
        providerId: String,
        bundleId: String,
        idempotencyKey: String? = null,
    ) = postJson(
        "/api/generation/run",
        token,
        mapOf(
            "organizationId" to "org-demo",
            "providerConfigId" to providerId,
            "sourceBundleIds" to listOf(bundleId),
            "visibility" to "private",
        ) + listOfNotNull(idempotencyKey?.let { "idempotencyKey" to it }).toMap(),
    )

    private fun patternOutput(title: String): String =
        objectMapper.writeValueAsString(
            mapOf(
                "patterns" to
                    listOf(
                        mapOf(
                            "title" to title,
                            "summary" to "Provider generated summary.",
                            "confidence" to 0.91,
                            "tags" to listOf(mapOf("type" to "framework", "name" to "Provider React")),
                            "evidenceRefs" to listOf("bundle"),
                            "languageAgnosticExplanation" to "Keep the boundary explicit.",
                            "implementationGuidance" to listOf("Validate provider output before persistence."),
                            "commonFailureModes" to listOf("Invalid provider JSON."),
                            "problems" to
                                listOf(
                                    mapOf("type" to "qa", "difficulty" to "beginner", "prompt" to "When is this pattern useful?", "referenceAnswer" to "When provider output must be validated before use."),
                                    mapOf("type" to "short_implementation", "difficulty" to "intermediate", "prompt" to "Implement a validated adapter.", "referenceAnswer" to "Parse into a strict DTO before persistence."),
                                    mapOf("type" to "debugging", "difficulty" to "intermediate", "prompt" to "What should fail safely?", "referenceAnswer" to "Invalid JSON and HTTP failures should create no assets."),
                                ),
                            "reviewRisks" to listOf("correctness"),
                        ),
                    ),
            ),
        )

    private fun openAiResponseBody(outputText: String): String =
        objectMapper.writeValueAsString(mapOf("output_text" to outputText))

    private fun postJson(
        path: String,
        token: String,
        body: Any,
    ) = restTemplate.exchange(path, HttpMethod.POST, HttpEntity(body, bearerHeaders(token)), String::class.java)

    private fun getJson(
        path: String,
        token: String,
    ) = restTemplate.exchange(path, HttpMethod.GET, HttpEntity<Void>(bearerHeaders(token)), String::class.java)

    private fun bearerHeaders(token: String): HttpHeaders =
        HttpHeaders().apply {
            setBearerAuth(token)
        }

    private fun json(response: org.springframework.http.ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    private fun sha256Like(seed: String): String = seed.hashCode().toUInt().toString(16).padStart(64, '0').take(64)

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
