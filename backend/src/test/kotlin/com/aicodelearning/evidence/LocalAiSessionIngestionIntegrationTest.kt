package com.aicodelearning.evidence

import com.aicodelearning.auth.KPostgreSQLContainer
import com.aicodelearning.auth.LoginRequest
import com.aicodelearning.auth.RunnerExecutorTestConfiguration
import com.aicodelearning.auth.SessionResponse
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers
@ActiveProfiles("local")
@Import(RunnerExecutorTestConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalAiSessionIngestionIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var sourceBundleRepository: SourceBundleRepository

    @Autowired
    private lateinit var evidenceItemRepository: EvidenceItemRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `local session ingest persists one bundle with multiple evidence items`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))
        val created = postJson("/api/ingest/local-ai-session", owner.token, localSessionRequest(repoRoot, "persist"))

        assertEquals(HttpStatus.CREATED, created.statusCode)
        val body = json(created)
        assertEquals(false, body["duplicate"].asBoolean())
        assertEquals("local_ai_session", body["bundle"]["sourceKind"].asText())
        assertEquals("generation_eligible", body["bundle"]["status"].asText())
        assertEquals(6, body["evidenceItems"].size())
        assertTrue(body["evidenceItems"].all { it["contentText"].isNull })

        val bundleId = body["bundle"]["id"].asText()
        val bundle = sourceBundleRepository.findById(bundleId).orElseThrow()
        assertEquals("local_ai_session", bundle.sourceKind)
        assertEquals("generation_eligible", bundle.status)
        assertTrue(bundle.repositoryUrl.orEmpty().startsWith("local://repo/"))
        assertFalse(bundle.repositoryUrl.orEmpty().contains(repoRoot.toString()))
        assertNotNull(bundle.dedupeKey)
        assertEquals(listOf("tool_session", "changed_files"), objectMapper.readValue(bundle.attributionReasonsJson, List::class.java))

        val items = evidenceItemRepository.findByBundleId(bundleId)
        assertEquals(setOf("prompt", "ai_response", "file_before", "file_after", "diff", "tool_event"), items.map { it.itemType }.toSet())
        assertEquals("Refactor this service", items.first { it.itemType == "prompt" }.contentText)
        assertEquals("src/service.ts", items.first { it.itemType == "file_after" }.repoRelativePath)
        assertNull(items.first { it.itemType == "tool_event" }.contentText)
        assertEquals("command_exit", objectMapper.readTree(items.first { it.itemType == "tool_event" }.metadataJson)["event"].asText())
    }

    @Test
    fun `sequential duplicate local session upload reuses existing bundle`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))
        val request = localSessionRequest(repoRoot, "sequential-duplicate")

        val first = postJson("/api/ingest/local-ai-session", owner.token, request)
        val second = postJson("/api/ingest/local-ai-session", owner.token, request)

        assertEquals(HttpStatus.CREATED, first.statusCode)
        assertEquals(HttpStatus.CREATED, second.statusCode)
        assertEquals(json(first)["bundle"]["id"].asText(), json(second)["bundle"]["id"].asText())
        assertEquals(false, json(first)["duplicate"].asBoolean())
        assertEquals(true, json(second)["duplicate"].asBoolean())
        assertEquals(1, countBundlesByDedupe(json(first)["bundle"]["id"].asText()))
    }

    @Test
    fun `duplicate local session upload canonicalizes artifact order`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))
        val request = localSessionRequest(repoRoot, "order-duplicate")
        val artifacts = artifactsFrom(request)
        val reordered = request + ("artifacts" to artifacts.reversed())

        val first = postJson("/api/ingest/local-ai-session", owner.token, request)
        val second = postJson("/api/ingest/local-ai-session", owner.token, reordered)

        assertEquals(HttpStatus.CREATED, first.statusCode)
        assertEquals(HttpStatus.CREATED, second.statusCode)
        assertEquals(json(first)["bundle"]["id"].asText(), json(second)["bundle"]["id"].asText())
        assertEquals(true, json(second)["duplicate"].asBoolean())
        assertEquals(1, countBundlesByDedupe(json(first)["bundle"]["id"].asText()))
    }

    @Test
    fun `duplicate lookup keeps secret-tainted retries quarantined`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))
        val request = localSessionRequest(repoRoot, "secret-tainted-retry")
        val artifacts = artifactsFrom(request)
        val secretRetry =
            request +
                (
                    "artifacts" to
                        artifacts +
                        artifact(
                            "file_before",
                            path = "node_modules/AWS_SECRET_ACCESS_KEY=abcdefghijklmnop/index.js",
                            content = "ignored",
                        )
                )

        val clean = postJson("/api/ingest/local-ai-session", owner.token, request)
        val tainted = postJson("/api/ingest/local-ai-session", owner.token, secretRetry)

        assertEquals(HttpStatus.CREATED, clean.statusCode)
        assertEquals(HttpStatus.CREATED, tainted.statusCode)
        assertEquals("generation_eligible", json(clean)["bundle"]["status"].asText())
        assertEquals("quarantined_secret", json(tainted)["bundle"]["status"].asText())
        assertEquals(false, json(tainted)["duplicate"].asBoolean())
        assertNotEquals(json(clean)["bundle"]["id"].asText(), json(tainted)["bundle"]["id"].asText())
    }

    @Test
    fun `secret-tainted duplicate canonicalizes artifact order`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))
        val request = localSessionRequest(repoRoot, "secret-order-duplicate")
        val artifacts = artifactsFrom(request)
        val taintedArtifacts =
            artifacts +
                artifact(
                    "file_before",
                    path = "node_modules/pkg/index.js",
                    content = "SLACK_BOT_TOKEN=xoxb-abcdefghijklmnop",
                )
        val tainted = request + ("artifacts" to taintedArtifacts)
        val reordered = request + ("artifacts" to taintedArtifacts.reversed())

        val first = postJson("/api/ingest/local-ai-session", owner.token, tainted)
        val second = postJson("/api/ingest/local-ai-session", owner.token, reordered)

        assertEquals(HttpStatus.CREATED, first.statusCode)
        assertEquals(HttpStatus.CREATED, second.statusCode)
        assertEquals("quarantined_secret", json(first)["bundle"]["status"].asText())
        assertEquals(json(first)["bundle"]["id"].asText(), json(second)["bundle"]["id"].asText())
        assertEquals(true, json(second)["duplicate"].asBoolean())
    }

    @Test
    fun `concurrent duplicate local session uploads produce one durable bundle`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))
        val request = localSessionRequest(repoRoot, "concurrent-duplicate")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        try {
            val futures =
                (1..2).map {
                    pool.submit<org.springframework.http.ResponseEntity<String>> {
                        start.await(5, TimeUnit.SECONDS)
                        postJson("/api/ingest/local-ai-session", owner.token, request)
                    }
                }
            start.countDown()
            val responses = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertTrue(responses.all { it.statusCode == HttpStatus.CREATED })
            val bundleIds = responses.map { json(it)["bundle"]["id"].asText() }.toSet()
            assertEquals(1, bundleIds.size)
            assertEquals(1, countBundlesByDedupe(bundleIds.single()))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `secret containing local session is quarantined without returning or storing raw secret content`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))
        val secret = "sk-testtesttesttesttesttesttesttest"

        val created =
            postJson(
                "/api/ingest/local-ai-session",
                owner.token,
                localSessionRequest(repoRoot, "secret", promptContent = "Use token $secret"),
            )

        assertEquals(HttpStatus.CREATED, created.statusCode)
        assertFalse(created.body.orEmpty().contains(secret))
        val body = json(created)
        assertEquals("quarantined_secret", body["bundle"]["status"].asText())
        assertEquals(false, body["duplicate"].asBoolean())

        val bundleId = body["bundle"]["id"].asText()
        val bundle = sourceBundleRepository.findById(bundleId).orElseThrow()
        assertEquals("quarantined_secret", bundle.status)
        assertTrue(bundle.secretFindingsJson.contains("openai_key"))
        assertFalse(bundle.secretFindingsJson.contains(secret))
        val prompt = evidenceItemRepository.findByBundleId(bundleId).first { it.itemType == "prompt" }
        assertNull(prompt.contentText)
    }

    @Test
    fun `secret-like request metadata is not persisted in bundle metadata`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))
        val secret = "sk-testtesttesttesttesttesttesttest"
        val request =
            localSessionRequest(repoRoot, "secret-metadata") +
                mapOf(
                    "toolProvider" to secret,
                    "timestampBucket" to "api_key:abcdefghijklmnop",
                    "attributionReasons" to listOf("tool_session", "token:abcdefghijklmnop"),
                )

        val created = postJson("/api/ingest/local-ai-session", owner.token, request)

        assertEquals(HttpStatus.CREATED, created.statusCode)
        assertFalse(created.body.orEmpty().contains(secret))
        val bundle = sourceBundleRepository.findById(json(created)["bundle"]["id"].asText()).orElseThrow()
        val provenance = objectMapper.readTree(bundle.provenanceJson)
        assertNotNull(provenance["repoIdentityHash"])
        assertNotNull(provenance["toolSessionIdHash"])
        assertNotNull(provenance["toolEventIdHash"])
        assertFalse(provenance.has("toolProvider"))
        assertFalse(provenance.has("timestampBucket"))
        assertEquals(listOf("tool_session"), objectMapper.readValue(bundle.attributionReasonsJson, List::class.java))
        assertFalse(bundle.provenanceJson.contains(secret))
        assertFalse(bundle.attributionReasonsJson.contains("token"))
    }

    @Test
    fun `raw purge clears local session dedupe so recollection creates a readable bundle`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))
        val request = localSessionRequest(repoRoot, "purge-recollect")
        val first = postJson("/api/ingest/local-ai-session", owner.token, request)
        val firstBundleId = json(first)["bundle"]["id"].asText()

        val purged = postJson("/api/evidence/$firstBundleId/purge-raw", owner.token, emptyMap<String, String>())
        assertEquals(HttpStatus.OK, purged.statusCode)
        assertNull(sourceBundleRepository.findById(firstBundleId).orElseThrow().dedupeKey)

        val second = postJson("/api/ingest/local-ai-session", owner.token, request)
        val secondBundleId = json(second)["bundle"]["id"].asText()

        assertEquals(HttpStatus.CREATED, second.statusCode)
        assertEquals(false, json(second)["duplicate"].asBoolean())
        assertNotEquals(firstBundleId, secondBundleId)
        assertEquals("Refactor this service", evidenceItemRepository.findByBundleId(secondBundleId).first { it.itemType == "prompt" }.contentText)
        assertEquals(1, countBundlesByDedupe(secondBundleId))
    }

    @Test
    fun `attribution override changes generation eligibility while preserving automatic attribution history`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))
        val created = postJson("/api/ingest/local-ai-session", owner.token, localSessionRequest(repoRoot, "attribution-override"))
        val bundleId = json(created)["bundle"]["id"].asText()

        val manual =
            patchJson(
                "/api/evidence/$bundleId/attribution",
                owner.token,
                mapOf(
                    "userAttribution" to "manual",
                    "attributionConfidence" to 0.41,
                    "attributionReasons" to listOf("human_review", "stdout raw output", "token:abcdefghijklmnop"),
                ),
            )

        assertEquals(HttpStatus.OK, manual.statusCode)
        val manualBody = json(manual)
        assertEquals("ai_assisted", manualBody["autoAttribution"].asText())
        assertEquals("manual", manualBody["userAttribution"].asText())
        assertEquals("generation_eligible", manualBody["status"].asText())
        assertEquals("""["tool_session","changed_files"]""", manualBody["attributionReasonsJson"].asText())

        val eligible =
            patchJson(
                "/api/evidence/$bundleId/attribution",
                owner.token,
                mapOf(
                    "userAttribution" to "use_for_generation",
                    "attributionConfidence" to 0.88,
                    "attributionReasons" to listOf("curation_approved", "/tmp/local-path", "stdout raw output"),
                ),
            )

        assertEquals(HttpStatus.OK, eligible.statusCode)
        val eligibleBody = json(eligible)
        assertEquals("ai_assisted", eligibleBody["autoAttribution"].asText())
        assertEquals("use_for_generation", eligibleBody["userAttribution"].asText())
        assertEquals("generation_eligible", eligibleBody["status"].asText())
        assertEquals("""["tool_session","changed_files"]""", eligibleBody["attributionReasonsJson"].asText())

        val bundle = sourceBundleRepository.findById(bundleId).orElseThrow()
        assertEquals("ai_assisted", bundle.autoAttribution)
        assertEquals("use_for_generation", bundle.userAttribution)
        assertEquals("generation_eligible", bundle.status)
        assertEquals(listOf("tool_session", "changed_files"), objectMapper.readValue(bundle.attributionReasonsJson, List::class.java))

        val events = attributionEvents(bundleId)
        assertEquals(3, events.size)
        val autoEvent = events.single { it["event_type"] == "auto_detected" }
        val manualEvent = events.single { it["user_attribution"] == "manual" }
        val useForGenerationEvent = events.single { it["user_attribution"] == "use_for_generation" }
        assertNull(autoEvent["user_attribution"])
        assertEquals("""["human_review"]""", manualEvent["attribution_reasons_json"])
        assertEquals("""["curation_approved"]""", useForGenerationEvent["attribution_reasons_json"])
    }

    @Test
    fun `attribution override audit metadata contains only safe attribution fields`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))
        val created = postJson("/api/ingest/local-ai-session", owner.token, localSessionRequest(repoRoot, "attribution-audit"))
        val bundleId = json(created)["bundle"]["id"].asText()
        val rawSentinel = "raw-audit-sentinel-${System.nanoTime()}"

        val updated =
            patchJson(
                "/api/evidence/$bundleId/attribution",
                owner.token,
                mapOf(
                    "userAttribution" to "delete",
                    "attributionConfidence" to 0.12,
                    "attributionReasons" to listOf("user_deleted", rawSentinel, "stdout raw output", "src/service.ts", "/tmp/$rawSentinel"),
                ),
            )

        assertEquals(HttpStatus.OK, updated.statusCode)
        val metadataJson = latestAttributionAuditMetadata(bundleId)
        val metadata = objectMapper.readTree(metadataJson)
        assertEquals(bundleId, metadata["bundleId"].asText())
        assertEquals("ai_assisted", metadata["autoAttribution"].asText())
        assertEquals("delete", metadata["userAttribution"].asText())
        assertEquals("generation_eligible", metadata["status"].asText())
        assertEquals("user_deleted", metadata["attributionReasons"][0].asText())
        assertFalse(metadataJson.contains(rawSentinel))
        assertFalse(metadataJson.contains("stdout raw output"))
        assertFalse(metadataJson.contains("src/service.ts"))
        assertFalse(metadataJson.contains("/tmp/"))
        val bundle = sourceBundleRepository.findById(bundleId).orElseThrow()
        assertNotNull(bundle.deletedAt)
        assertEquals("delete", bundle.userAttribution)
    }

    @Test
    fun `quarantined local session cannot be marked for generation`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))
        val created =
            postJson(
                "/api/ingest/local-ai-session",
                owner.token,
                localSessionRequest(repoRoot, "quarantined-attribution", promptContent = "Use token sk-testtesttesttesttesttesttesttest"),
            )
        val bundleId = json(created)["bundle"]["id"].asText()

        val updated =
            patchJson(
                "/api/evidence/$bundleId/attribution",
                owner.token,
                mapOf(
                    "userAttribution" to "use_for_generation",
                    "attributionConfidence" to 0.99,
                    "attributionReasons" to listOf("curation_approved"),
                ),
            )

        assertEquals(HttpStatus.BAD_REQUEST, updated.statusCode)
        val bundle = sourceBundleRepository.findById(bundleId).orElseThrow()
        assertEquals("quarantined_secret", bundle.status)
        assertNull(bundle.userAttribution)
        assertEquals(1, attributionEvents(bundleId).size)
    }

    @Test
    fun `attribution override rejects non local session evidence`() {
        val owner = login()
        val created =
            postJson(
                "/api/ingest/manual",
                owner.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "teamId" to "team-platform",
                    "projectId" to "project-learning",
                    "title" to "Manual code",
                    "sourceKind" to "code",
                    "content" to "class ManualCode",
                ),
            )
        val bundleId = json(created)["bundle"]["id"].asText()

        val updated =
            patchJson(
                "/api/evidence/$bundleId/attribution",
                owner.token,
                mapOf(
                    "userAttribution" to "use_for_generation",
                    "attributionConfidence" to 0.9,
                    "attributionReasons" to listOf("curation_approved"),
                ),
            )

        assertEquals(HttpStatus.BAD_REQUEST, updated.statusCode)
        val bundle = sourceBundleRepository.findById(bundleId).orElseThrow()
        assertNull(bundle.userAttribution)
        assertEquals("ready", bundle.status)
    }

    @Test
    fun `local session source links and generation require use for generation curation`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))
        val localSession =
            postJson(
                "/api/ingest/local-ai-session",
                owner.token,
                localSessionGenerationRequest(repoRoot, "generation-curation"),
            )
        val localBundleId = json(localSession)["bundle"]["id"].asText()
        val conversation =
            postJson(
                "/api/ingest/manual",
                owner.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "teamId" to "team-platform",
                    "projectId" to "project-learning",
                    "title" to "Local generation conversation",
                    "sourceKind" to "conversation",
                    "content" to "Use a provider for this generated pattern.",
                ),
            )
        val conversationBundleId = json(conversation)["bundle"]["id"].asText()

        val uncuratedSuggested =
            postJson(
                "/api/source-links/suggest",
                owner.token,
                mapOf(
                    "conversationBundleId" to conversationBundleId,
                    "codeBundleId" to localBundleId,
                ),
            )
        assertEquals(HttpStatus.BAD_REQUEST, uncuratedSuggested.statusCode)

        patchJson(
            "/api/evidence/$localBundleId/attribution",
            owner.token,
            mapOf(
                "userAttribution" to "use_for_generation",
                "attributionConfidence" to 0.88,
                "attributionReasons" to listOf("curation_approved"),
            ),
        )
        val suggested =
            postJson(
                "/api/source-links/suggest",
                owner.token,
                mapOf(
                    "conversationBundleId" to conversationBundleId,
                    "codeBundleId" to localBundleId,
                ),
            )
        assertEquals(HttpStatus.OK, suggested.statusCode)
        val linkId = json(suggested)["links"][0]["id"].asText()
        val confirmed = postJson("/api/source-links/$linkId/confirm", owner.token, emptyMap<String, String>())
        assertEquals("confirmed", json(confirmed)["status"].asText())

        patchJson(
            "/api/evidence/$localBundleId/attribution",
            owner.token,
            mapOf(
                "userAttribution" to "manual",
                "attributionConfidence" to 0.41,
                "attributionReasons" to listOf("human_review"),
            ),
        )
        val blocked =
            postJson(
                "/api/generation/run",
                owner.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "providerConfigId" to "provider-local-mock",
                    "sourceLinkIds" to listOf(linkId),
                    "visibility" to "private",
                ),
            )
        assertEquals(HttpStatus.BAD_REQUEST, blocked.statusCode)

        patchJson(
            "/api/evidence/$localBundleId/attribution",
            owner.token,
            mapOf(
                "userAttribution" to "use_for_generation",
                "attributionConfidence" to 0.9,
                "attributionReasons" to listOf("curation_approved"),
            ),
        )
        val generated =
            postJson(
                "/api/generation/run",
                owner.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "providerConfigId" to "provider-local-mock",
                    "sourceLinkIds" to listOf(linkId),
                    "visibility" to "private",
                ),
            )
        assertEquals(HttpStatus.CREATED, generated.statusCode)
    }

    private fun login(): SessionResponse {
        val response = restTemplate.postForEntity("/api/session", LoginRequest("owner@local.learnloop", "demo-password"), SessionResponse::class.java)
        assertEquals(HttpStatus.CREATED, response.statusCode)
        return requireNotNull(response.body)
    }

    private fun localSessionRequest(
        repoRoot: Path,
        suffix: String,
        promptContent: String = "Refactor this service",
    ): Map<String, Any?> =
        mapOf(
            "organizationId" to "org-demo",
            "teamId" to "team-platform",
            "projectId" to "project-learning",
            "title" to "Codex session $suffix",
            "sourceKind" to "local_ai_session",
            "repoIdentityHash" to "repo-identity-$suffix",
            "repositoryDisplayLabel" to "repo-$suffix",
            "repositoryUrl" to repoRoot.toUri().toString(),
            "commitSha" to "0123456789abcdef",
            "branchName" to "feature/local-session-$suffix",
            "toolProvider" to "codex-cli",
            "toolSessionId" to "session-$suffix",
            "toolEventId" to "event-$suffix",
            "timestampBucket" to "2026-05-19T08:00Z",
            "idempotencyKey" to "repo-identity-$suffix:event-$suffix",
            "autoAttribution" to "ai_assisted",
            "attributionConfidence" to 0.92,
            "attributionReasons" to listOf("tool_session", "changed_files", "raw output ignored"),
            "artifacts" to
                listOf(
                    artifact("prompt", content = promptContent),
                    artifact("ai_response", content = "I changed the service"),
                    artifact("file_before", path = "src/service.ts", content = "export const oldValue = 1"),
                    artifact("file_after", path = "src/service.ts", content = "export const newValue = 2"),
                    artifact("diff", path = "src/service.ts", content = "-oldValue\n+newValue"),
                    artifact("tool_event", metadata = mapOf("event" to "command_exit", "exitCode" to "0", "tool" to "codex-cli"), content = "stdout raw output"),
                ),
        )

    private fun localSessionGenerationRequest(
        repoRoot: Path,
        suffix: String,
    ): Map<String, Any?> =
        localSessionRequest(repoRoot, suffix) +
            (
                "artifacts" to
                    listOf(
                        artifact("prompt", content = "Use a provider for this generated pattern."),
                        artifact("file_after", path = "src/service.ts", content = "class ProviderScopedPattern"),
                        artifact("tool_event", metadata = mapOf("event" to "command_exit", "exitCode" to "0"), content = "stdout raw output"),
                    )
            )

    private fun artifact(
        itemType: String,
        path: String? = null,
        metadata: Map<String, String> = emptyMap(),
        content: String?,
    ): Map<String, Any?> =
        mapOf(
            "itemType" to itemType,
            "repoRelativePath" to path,
            "sizeBytes" to (content?.toByteArray(Charsets.UTF_8)?.size ?: 0),
            "metadata" to metadata,
            "contentHash" to "a".repeat(64),
            "contentTruncated" to false,
            "content" to content,
        )

    private fun countBundlesByDedupe(bundleId: String): Int {
        val bundle = sourceBundleRepository.findById(bundleId).orElseThrow()
        return jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM source_bundles
            WHERE organization_id = ? AND source_kind = ? AND dedupe_key = ? AND deleted_at IS NULL
            """.trimIndent(),
            Int::class.java,
            bundle.organizationId,
            bundle.sourceKind,
            bundle.dedupeKey,
        ) ?: 0
    }

    @Suppress("UNCHECKED_CAST")
    private fun artifactsFrom(request: Map<String, Any?>): List<Map<String, Any?>> =
        request.getValue("artifacts") as List<Map<String, Any?>>

    private fun postJson(
        path: String,
        token: String,
        body: Any,
    ) = restTemplate.exchange(path, HttpMethod.POST, HttpEntity(body, bearerHeaders(token)), String::class.java)

    private fun patchJson(
        path: String,
        token: String,
        body: Any,
    ) = restTemplate.exchange(path, HttpMethod.PATCH, HttpEntity(body, bearerHeaders(token)), String::class.java)

    private fun json(response: org.springframework.http.ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    private fun bearerHeaders(token: String): HttpHeaders =
        HttpHeaders().apply {
            setBearerAuth(token)
        }

    private fun attributionEvents(bundleId: String): List<Map<String, Any>> =
        jdbcTemplate.queryForList(
            """
            SELECT event_type, user_attribution, attribution_reasons_json
            FROM source_bundle_attribution_events
            WHERE bundle_id = ?
            """.trimIndent(),
            bundleId,
        )

    private fun latestAttributionAuditMetadata(bundleId: String): String =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT metadata_json
                FROM audit_logs
                WHERE target_id = ? AND event_type = 'evidence.attribution_overridden'
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """.trimIndent(),
                String::class.java,
                bundleId,
            ),
        )

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
