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
import java.time.Instant
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
    fun `local session ingest requires approved repository consent`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))

        val rejected = postJsonRaw("/api/ingest/local-ai-session", owner.token, localSessionRequest(repoRoot, "unapproved"))

        assertEquals(HttpStatus.BAD_REQUEST, rejected.statusCode)
    }

    @Test
    fun `evidence list returns local session summaries without raw artifact content`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))
        val sentinel = "list-raw-sentinel-${System.nanoTime()}"
        val created =
            postJson(
                "/api/ingest/local-ai-session",
                owner.token,
                localSessionRequest(repoRoot, "list-summary", promptContent = sentinel),
            )
        val bundleId = json(created)["bundle"]["id"].asText()

        val listed = getJson("/api/evidence?organizationId=org-demo&page=0&pageSize=10", owner.token)

        assertEquals(HttpStatus.OK, listed.statusCode)
        val body = requireNotNull(listed.body)
        assertTrue(body.contains(bundleId))
        assertFalse(body.contains(sentinel))
        val firstBundle = json(listed)["bundles"].first { it["id"].asText() == bundleId }
        assertEquals("local_ai_session", firstBundle["sourceKind"].asText())
        assertFalse(firstBundle.has("filePathsJson"))
        assertFalse(firstBundle.has("provenanceJson"))
        assertFalse(firstBundle.has("secretFindingsJson"))
    }

    @Test
    fun `evidence list caps large local evidence pages`() {
        val owner = login()
        val now = Instant.now()
        val suffix = System.nanoTime()
        sourceBundleRepository.saveAll(
            (1..1_000).map { index ->
                SourceBundleEntity(
                    id = "bundle_bulk_list_${suffix}_$index",
                    organizationId = "org-demo",
                    teamId = "team-platform",
                    projectId = "project-learning",
                    createdByUserId = owner.user.id,
                    title = "Bulk local session $index",
                    sourceKind = LocalAiSessionPolicy.SOURCE_KIND,
                    status = LocalAiSessionPolicy.STATUS_GENERATION_ELIGIBLE,
                    repositoryUrl = "local-repo-$suffix",
                    commitSha = index.toString(16).padStart(8, '0'),
                    branchName = "bulk-list",
                    contentHash = "bulk-list-$suffix-$index",
                    createdAt = now.plusSeconds(index.toLong()),
                    autoAttribution = "ai_assisted",
                    userAttribution = LocalAiSessionPolicy.USER_ATTRIBUTION_USE_FOR_GENERATION,
                    attributionReasonsJson = """["tool_session"]""",
                    dedupeKey = "bulk-list-$suffix-$index",
                )
            },
        )

        val listed = getJson("/api/evidence?organizationId=org-demo&page=0&pageSize=1000", owner.token)

        assertEquals(HttpStatus.OK, listed.statusCode)
        val body = json(listed)
        assertEquals(100, body["bundles"].size())
        assertTrue(body["total"].asLong() >= 1_000L)
        assertEquals(0, body["page"].asInt())
        assertEquals(100, body["pageSize"].asInt())
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
        approveLocalRepository(owner.token, request)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        try {
            val futures =
                (1..2).map {
                    pool.submit<org.springframework.http.ResponseEntity<String>> {
                        start.await(5, TimeUnit.SECONDS)
                        postJsonRaw("/api/ingest/local-ai-session", owner.token, request)
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

    @Test
    fun `curated local session generates directly without source link and preserves lineage after raw purge`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))
        val localSession =
            postJson(
                "/api/ingest/local-ai-session",
                owner.token,
                localSessionGenerationRequest(repoRoot, "direct-generation"),
            )
        val bundleId = json(localSession)["bundle"]["id"].asText()

        val uncurated = generateFromBundles(owner.token, listOf(bundleId))
        assertEquals(HttpStatus.BAD_REQUEST, uncurated.statusCode)

        patchJson(
            "/api/evidence/$bundleId/attribution",
            owner.token,
            mapOf(
                "userAttribution" to "use_for_generation",
                "attributionConfidence" to 0.91,
                "attributionReasons" to listOf("curation_approved"),
            ),
        )
        val mixedRequest =
            postJson(
                "/api/generation/run",
                owner.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "providerConfigId" to "provider-local-mock",
                    "sourceLinkIds" to listOf("link-does-not-matter"),
                    "sourceBundleIds" to listOf(bundleId),
                    "visibility" to "private",
                ),
            )
        assertEquals(HttpStatus.BAD_REQUEST, mixedRequest.statusCode)

        val generated = generateFromBundles(owner.token, listOf(bundleId))
        assertEquals(HttpStatus.CREATED, generated.statusCode)
        val generatedBody = json(generated)
        val generationRun = generatedBody["generationRun"]
        assertEquals(listOf(bundleId), objectMapper.readValue(generationRun["sourceBundleIdsJson"].asText(), List::class.java))
        val evidenceItemIds = objectMapper.readValue(generationRun["evidenceItemIdsJson"].asText(), List::class.java).map { it.toString() }
        val items = evidenceItemRepository.findByBundleId(bundleId)
        val toolEventId = items.single { it.itemType == "tool_event" }.id
        val generationItemIds = items.filter { it.itemType in LocalAiSessionPolicy.generationItemTypes }.map { it.id }.toSet()
        assertEquals(generationItemIds, evidenceItemIds.toSet())
        assertFalse(evidenceItemIds.contains(toolEventId))
        assertEquals(0, sourceLinkCountForBundle(bundleId))

        val cardId = generatedBody["patternCard"]["id"].asText()
        val purged = postJson("/api/evidence/$bundleId/purge-raw", owner.token, emptyMap<String, String>())
        assertEquals(HttpStatus.OK, purged.statusCode)
        val card = getJson("/api/pattern-cards/$cardId", owner.token)
        assertEquals(HttpStatus.OK, card.statusCode)
        assertEquals(cardId, json(card)["patternCard"]["id"].asText())
    }

    @Test
    fun `direct local session generation rejects multiple bundles`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))
        val firstId = createCuratedLocalSession(owner.token, repoRoot, "direct-multiple-first")
        val secondId = createCuratedLocalSession(owner.token, repoRoot, "direct-multiple-second")

        val generated = generateFromBundles(owner.token, listOf(firstId, secondId))

        assertEquals(HttpStatus.BAD_REQUEST, generated.statusCode)
    }

    @Test
    fun `direct local session generation canonicalizes duplicate bundle ids`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))
        val bundleId = createCuratedLocalSession(owner.token, repoRoot, "direct-duplicate")

        val generated = generateFromBundles(owner.token, listOf(bundleId, bundleId))

        assertEquals(HttpStatus.CREATED, generated.statusCode)
        val generationRun = json(generated)["generationRun"]
        assertEquals(listOf(bundleId), objectMapper.readValue(generationRun["sourceBundleIdsJson"].asText(), List::class.java))
    }

    @Test
    fun `direct local session generation rejects manual evidence`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))
        val manualId = createCuratedLocalSession(owner.token, repoRoot, "direct-manual")

        patchJson(
            "/api/evidence/$manualId/attribution",
            owner.token,
            mapOf(
                "userAttribution" to "manual",
                "attributionConfidence" to 0.41,
                "attributionReasons" to listOf("human_review"),
            ),
        )
        assertDirectGenerationRejected(owner.token, manualId)
    }

    @Test
    fun `direct local session generation rejects raw purged evidence`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))
        val purgedId = createCuratedLocalSession(owner.token, repoRoot, "direct-purged")
        val purged = postJson("/api/evidence/$purgedId/purge-raw", owner.token, emptyMap<String, String>())

        assertEquals(HttpStatus.OK, purged.statusCode)
        assertDirectGenerationRejected(owner.token, purgedId)
    }

    @Test
    fun `direct local session generation rejects deleted evidence`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))
        val deletedId = createCuratedLocalSession(owner.token, repoRoot, "direct-deleted")
        val deleted = restTemplate.exchange("/api/evidence/$deletedId", HttpMethod.DELETE, HttpEntity<Void>(bearerHeaders(owner.token)), String::class.java)

        assertEquals(HttpStatus.NO_CONTENT, deleted.statusCode)
        assertDirectGenerationRejected(owner.token, deletedId)
    }

    @Test
    fun `direct local session generation rejects quarantined evidence`() {
        val owner = login()
        val repoRoot = Files.createDirectories(tempDir.resolve("repo-${System.nanoTime()}"))
        val quarantined =
            postJson(
                "/api/ingest/local-ai-session",
                owner.token,
                localSessionRequest(repoRoot, "direct-quarantined", promptContent = "Use token sk-testtesttesttesttesttesttesttest"),
            )
        assertDirectGenerationRejected(owner.token, json(quarantined)["bundle"]["id"].asText())
    }

    @Test
    fun `direct local session generation rejects non local evidence`() {
        val owner = login()
        val nonLocal =
            postJson(
                "/api/ingest/manual",
                owner.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "teamId" to "team-platform",
                    "projectId" to "project-learning",
                    "title" to "Manual direct generation code",
                    "sourceKind" to "code",
                    "content" to "class ManualDirectGeneration",
                ),
            )
        assertDirectGenerationRejected(owner.token, json(nonLocal)["bundle"]["id"].asText())
    }

    private fun login(): SessionResponse {
        val response = restTemplate.postForEntity("/api/session", LoginRequest("owner@local.learnloop", "demo-password"), SessionResponse::class.java)
        assertEquals(HttpStatus.CREATED, response.statusCode)
        return requireNotNull(response.body)
    }

    private fun createCuratedLocalSession(
        token: String,
        repoRoot: Path,
        suffix: String,
    ): String {
        val created = postJson("/api/ingest/local-ai-session", token, localSessionGenerationRequest(repoRoot, suffix))
        assertEquals(HttpStatus.CREATED, created.statusCode)
        val bundleId = json(created)["bundle"]["id"].asText()
        val curated =
            patchJson(
                "/api/evidence/$bundleId/attribution",
                token,
                mapOf(
                    "userAttribution" to "use_for_generation",
                    "attributionConfidence" to 0.9,
                    "attributionReasons" to listOf("curation_approved"),
                ),
            )
        assertEquals(HttpStatus.OK, curated.statusCode)
        return bundleId
    }

    private fun generateFromBundles(
        token: String,
        bundleIds: List<String>,
    ) = postJson(
        "/api/generation/run",
        token,
        mapOf(
            "organizationId" to "org-demo",
            "providerConfigId" to "provider-local-mock",
            "sourceBundleIds" to bundleIds,
            "visibility" to "private",
        ),
    )

    private fun assertDirectGenerationRejected(
        token: String,
        bundleId: String,
    ) {
        val generated = generateFromBundles(token, listOf(bundleId))
        assertEquals(HttpStatus.BAD_REQUEST, generated.statusCode)
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

    private fun sourceLinkCountForBundle(bundleId: String): Int =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM source_links
            WHERE conversation_bundle_id = ? OR code_bundle_id = ?
            """.trimIndent(),
            Int::class.java,
            bundleId,
            bundleId,
        ) ?: 0

    @Suppress("UNCHECKED_CAST")
    private fun artifactsFrom(request: Map<String, Any?>): List<Map<String, Any?>> =
        request.getValue("artifacts") as List<Map<String, Any?>>

    private fun postJson(
        path: String,
        token: String,
        body: Any,
    ): org.springframework.http.ResponseEntity<String> {
        if (path == "/api/ingest/local-ai-session" && body is Map<*, *>) {
            approveLocalRepository(token, body)
        }
        return postJsonRaw(path, token, body)
    }

    private fun postJsonRaw(
        path: String,
        token: String,
        body: Any,
    ) = restTemplate.exchange(path, HttpMethod.POST, HttpEntity(body, bearerHeaders(token)), String::class.java)

    private fun approveLocalRepository(
        token: String,
        request: Map<*, *>,
    ) {
        val repoIdentityHash = request["repoIdentityHash"] as String
        val displayLabel = request["repositoryDisplayLabel"] as String
        val approved =
            restTemplate.exchange(
                "/api/local-repositories/$repoIdentityHash",
                HttpMethod.PATCH,
                HttpEntity(
                    mapOf(
                        "organizationId" to "org-demo",
                        "displayLabel" to displayLabel,
                        "status" to "approved",
                    ),
                    bearerHeaders(token),
                ),
                String::class.java,
            )
        assertEquals(HttpStatus.OK, approved.statusCode)
    }

    private fun patchJson(
        path: String,
        token: String,
        body: Any,
    ) = restTemplate.exchange(path, HttpMethod.PATCH, HttpEntity(body, bearerHeaders(token)), String::class.java)

    private fun getJson(
        path: String,
        token: String,
    ) = restTemplate.exchange(path, HttpMethod.GET, HttpEntity<Void>(bearerHeaders(token)), String::class.java)

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
