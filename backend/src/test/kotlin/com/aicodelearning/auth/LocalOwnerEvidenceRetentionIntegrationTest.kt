package com.aicodelearning.auth

import com.aicodelearning.evidence.EvidenceItemRepository
import com.aicodelearning.evidence.SourceBundleRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@ActiveProfiles("local")
@Import(RunnerExecutorTestConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalOwnerEvidenceRetentionIntegrationTest {
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

    @Test
    fun `local owner retention settings support default update disabled immediate and invalid values`() {
        val owner = login()
        clearRetentionSettings(owner)

        val defaultSettings = getJson("/api/evidence/retention-settings?organizationId=org-demo", owner.token)
        assertEquals(HttpStatus.OK, defaultSettings.statusCode)
        assertEquals("default", json(defaultSettings)["retentionMode"].asText())
        assertEquals(30, json(defaultSettings)["retentionDays"].asInt())
        assertEquals(true, json(defaultSettings)["automaticCleanupEnabled"].asBoolean())
        assertEquals(false, json(defaultSettings)["immediatePurge"].asBoolean())
        assertTrue(json(defaultSettings)["updatedAt"].isNull)

        val updated =
            patchJson(
                "/api/evidence/retention-settings",
                owner.token,
                mapOf("organizationId" to "org-demo", "retentionMode" to "default", "retentionDays" to 90),
            )
        assertEquals(HttpStatus.OK, updated.statusCode)
        assertEquals("default", json(updated)["retentionMode"].asText())
        assertEquals(90, json(updated)["retentionDays"].asInt())
        assertFalse(json(updated)["updatedAt"].isNull)

        val disabled =
            patchJson(
                "/api/evidence/retention-settings",
                owner.token,
                mapOf("organizationId" to "org-demo", "retentionMode" to "disabled", "retentionDays" to 90),
            )
        assertEquals(HttpStatus.OK, disabled.statusCode)
        assertEquals("disabled", json(disabled)["retentionMode"].asText())
        assertTrue(json(disabled)["retentionDays"].isNull)
        assertEquals(false, json(disabled)["automaticCleanupEnabled"].asBoolean())

        val immediate =
            patchJson(
                "/api/evidence/retention-settings",
                owner.token,
                mapOf("organizationId" to "org-demo", "retentionMode" to "immediate", "retentionDays" to 365),
            )
        assertEquals(HttpStatus.OK, immediate.statusCode)
        assertEquals("immediate", json(immediate)["retentionMode"].asText())
        assertEquals(0, json(immediate)["retentionDays"].asInt())
        assertEquals(true, json(immediate)["immediatePurge"].asBoolean())

        val invalidMode =
            patchJson(
                "/api/evidence/retention-settings",
                owner.token,
                mapOf("organizationId" to "org-demo", "retentionMode" to "forever"),
            )
        assertEquals(HttpStatus.BAD_REQUEST, invalidMode.statusCode)

        val invalidDays =
            patchJson(
                "/api/evidence/retention-settings",
                owner.token,
                mapOf("organizationId" to "org-demo", "retentionMode" to "default", "retentionDays" to 0),
            )
        assertEquals(HttpStatus.BAD_REQUEST, invalidDays.statusCode)
    }

    @Test
    fun `retention dry run reports counts categories bytes and quarantined behavior without raw details`() {
        val owner = login()
        val marker = "retention-dry-run-${System.nanoTime()}"
        val codeContent = "class RetentionDryRunFixture { val marker = \"$marker-code\" }"
        val diffContent = "diff --git a/src/$marker.kt b/src/$marker.kt\n+$marker-diff"
        val secret = "sk-testtesttesttesttesttesttesttest"
        patchJson(
            "/api/evidence/retention-settings",
            owner.token,
            mapOf("organizationId" to "org-demo", "retentionMode" to "default", "retentionDays" to 30),
        )

        val code =
            createEvidence(
                owner.token,
                "Retention dry run code",
                "code",
                codeContent,
                filePaths = listOf("/Users/local/$marker.kt"),
                provenance = mapOf("stdout" to "$marker-output"),
            )
        val diff = createEvidence(owner.token, "Retention dry run diff", "diff", diffContent)
        val quarantined =
            createEvidence(
                owner.token,
                "Retention dry run quarantined",
                "supporting_context",
                "Do not retain $secret",
            )
        listOf(code, diff, quarantined).forEach(::ageEvidenceForRetention)

        val preview = getJson("/api/evidence/retention-dry-run?organizationId=org-demo", owner.token)

        assertEquals(HttpStatus.OK, preview.statusCode)
        val body = json(preview)
        assertEquals("default", body["retentionMode"].asText())
        assertEquals(30, body["retentionDays"].asInt())
        assertEquals(true, body["automaticCleanupEnabled"].asBoolean())
        assertFalse(body["cutoffAt"].isNull)
        assertTrue(body["eligibleBundles"].asInt() >= 3)
        assertTrue(body["eligibleItems"].asInt() >= 3)
        assertTrue(body["rawMetadataBundles"].asInt() >= 1)
        val expectedContentBytes = (codeContent.toByteArray(Charsets.UTF_8).size + diffContent.toByteArray(Charsets.UTF_8).size).toLong()
        val expectedCodeBytes = codeContent.toByteArray(Charsets.UTF_8).size.toLong()
        assertTrue(body["estimatedReclaimedBytes"].asLong() >= expectedContentBytes)
        assertTrue(
            body["artifactCategories"].any {
                it["itemType"].asText() == "code" &&
                    it["itemCount"].asInt() >= 1 &&
                    it["estimatedBytes"].asLong() >= expectedCodeBytes
            },
        )
        assertTrue(body["artifactCategories"].any { it["itemType"].asText() == "diff" && it["itemCount"].asInt() >= 1 })
        assertTrue(body["quarantinedBundles"].asInt() >= 1)
        assertTrue(body["quarantinedItems"].asInt() >= 1)
        assertEquals("included_without_secret_details", body["quarantinedBehavior"].asText())
        val response = preview.body.orEmpty()
        assertFalse(response.contains(codeContent))
        assertFalse(response.contains(diffContent))
        assertFalse(response.contains(secret))
        assertFalse(response.contains("/Users/local"))
        assertFalse(response.contains("$marker-output"))

        val disabled =
            patchJson(
                "/api/evidence/retention-settings",
                owner.token,
                mapOf("organizationId" to "org-demo", "retentionMode" to "disabled"),
            )
        assertEquals(HttpStatus.OK, disabled.statusCode)
        val disabledPreview = getJson("/api/evidence/retention-dry-run?organizationId=org-demo", owner.token)
        assertEquals(HttpStatus.OK, disabledPreview.statusCode)
        val disabledBody = json(disabledPreview)
        assertEquals(false, disabledBody["automaticCleanupEnabled"].asBoolean())
        assertTrue(disabledBody["cutoffAt"].isNull)
        assertEquals(0, disabledBody["eligibleItems"].asInt())
        assertEquals("not_scanned_while_cleanup_disabled", disabledBody["quarantinedBehavior"].asText())
    }

    @Test
    fun `retention cleanup purges expired raw content in bounded batches and preserves metadata cards and fresh ingestion`() {
        val owner = login()
        val sentinel = "retention-cleanup-${System.nanoTime()}"
        patchJson(
            "/api/evidence/retention-settings",
            owner.token,
            mapOf("organizationId" to "org-demo", "retentionMode" to "default", "retentionDays" to 30),
        )
        val fixture =
            createConfirmedAccessibleSourceLinkFixture(
                owner.token,
                codeContent = "class RetentionCleanupPattern { val marker = \"$sentinel\" }",
                codeFilePaths = listOf("src/$sentinel.kt"),
                codeProvenance = mapOf("rawPath" to "/tmp/$sentinel", "stdout" to sentinel),
            )
        val generated =
            postJson(
                "/api/generation/run",
                owner.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "providerConfigId" to "provider-local-mock",
                    "sourceLinkIds" to listOf(fixture.linkId),
                    "visibility" to "organization",
                ),
            )
        assertEquals(HttpStatus.CREATED, generated.statusCode)
        val cardId = json(generated)["patternCard"]["id"].asText()
        val secondExpired = createEvidence(owner.token, "Retention cleanup second", "code", "const secondRetentionCleanup = \"$sentinel\"")
        val fresh = createEvidence(owner.token, "Retention cleanup fresh", "code", "const freshRetentionCleanup = \"$sentinel\"")
        listOf(fixture.codeBundleId, secondExpired).forEach(::ageEvidenceForRetention)
        val beforeBundle = sourceBundleRepository.findById(fixture.codeBundleId).orElseThrow()
        val beforeItem = evidenceItemRepository.findByBundleId(fixture.codeBundleId).single()

        val firstRun =
            postJson(
                "/api/evidence/retention-cleanup",
                owner.token,
                mapOf("organizationId" to "org-demo", "batchSize" to 1),
            )

        assertEquals(HttpStatus.OK, firstRun.statusCode)
        assertEquals(1, json(firstRun)["purgedItems"].asInt())
        assertTrue(json(firstRun)["remainingEligibleItems"].asInt() >= 1)
        assertTrue(json(firstRun)["reclaimedBytes"].asLong() >= 0)
        assertTrue(json(firstRun)["activeIngestionSkippedItems"].asInt() >= 1)
        assertEquals(0, json(firstRun)["filesystemArtifactsDeleted"].asInt())

        val finalRun =
            postJson(
                "/api/evidence/retention-cleanup",
                owner.token,
                mapOf("organizationId" to "org-demo", "batchSize" to 500),
            )
        assertEquals(HttpStatus.OK, finalRun.statusCode)
        assertTrue(json(finalRun)["purgedItems"].asInt() >= 1)

        val afterItem = evidenceItemRepository.findByBundleId(fixture.codeBundleId).single()
        assertNull(afterItem.contentText)
        assertEquals(beforeItem.contentHash, afterItem.contentHash)
        assertEquals(beforeItem.metadataJson, afterItem.metadataJson)
        assertNotNull(afterItem.rawPurgedAt)
        assertEquals("retention_cleanup", afterItem.rawPurgeReason)
        val afterBundle = sourceBundleRepository.findById(fixture.codeBundleId).orElseThrow()
        assertEquals(beforeBundle.filePathsJson, afterBundle.filePathsJson)
        assertEquals(beforeBundle.provenanceJson, afterBundle.provenanceJson)
        assertEquals(beforeBundle.contentHash, afterBundle.contentHash)
        assertEquals(beforeBundle.dedupeKey, afterBundle.dedupeKey)
        assertEquals("const freshRetentionCleanup = \"$sentinel\"", evidenceItemRepository.findByBundleId(fresh).single().contentText)

        val card = getJson("/api/pattern-cards/$cardId", owner.token)
        assertEquals(HttpStatus.OK, card.statusCode)
        assertEquals(cardId, json(card)["patternCard"]["id"].asText())
    }

    @Test
    fun `local owner delete tombstones bundle and excludes it from reads and generation`() {
        val owner = login()
        val fixture = createConfirmedAccessibleSourceLinkFixture(owner.token)

        val deleted =
            restTemplate.exchange(
                "/api/evidence/${fixture.codeBundleId}",
                HttpMethod.DELETE,
                HttpEntity<Void>(bearerHeaders(owner.token)),
                String::class.java,
            )

        assertEquals(HttpStatus.NO_CONTENT, deleted.statusCode)
        val tombstone = sourceBundleRepository.findById(fixture.codeBundleId).orElseThrow()
        assertNotNull(tombstone.deletedAt)
        assertEquals("u-local-owner", tombstone.deletedByUserId)
        assertEquals("local_owner_delete", tombstone.deletionReason)

        val detail = getJson("/api/evidence/${fixture.codeBundleId}", owner.token)
        assertEquals(HttpStatus.NOT_FOUND, detail.statusCode)

        val generated =
            postJson(
                "/api/generation/run",
                owner.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "providerConfigId" to "provider-local-mock",
                    "sourceLinkIds" to listOf(fixture.linkId),
                    "visibility" to "organization",
                ),
            )
        assertEquals(HttpStatus.BAD_REQUEST, generated.statusCode)
    }

    @Test
    fun `local owner raw purge is idempotent and clears raw metadata`() {
        val owner = login()
        val sentinel = "phase4-purge-sentinel-${System.nanoTime()}"
        val fixture =
            createConfirmedAccessibleSourceLinkFixture(
                owner.token,
                codeContent = "class ProviderScopedPattern { val marker = \"$sentinel\" }",
                codeFilePaths = listOf("src/$sentinel.kt"),
                codeProvenance = mapOf("rawPath" to "/tmp/$sentinel", "stdout" to sentinel),
            )
        val generated =
            postJson(
                "/api/generation/run",
                owner.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "providerConfigId" to "provider-local-mock",
                    "sourceLinkIds" to listOf(fixture.linkId),
                    "visibility" to "organization",
                ),
            )
        assertEquals(HttpStatus.CREATED, generated.statusCode)
        val cardId = json(generated)["patternCard"]["id"].asText()

        val purged =
            postJson(
                "/api/evidence/${fixture.codeBundleId}/purge-raw",
                owner.token,
                emptyMap<String, String>(),
            )

        assertEquals(HttpStatus.OK, purged.statusCode)
        assertEquals(1, json(purged)["purgedBundles"].asInt())
        assertEquals(1, json(purged)["purgedItems"].asInt())
        assertFalse(databaseContains(sentinel))

        val item = evidenceItemRepository.findByBundleId(fixture.codeBundleId).single()
        assertNull(item.contentText)
        val firstPurgedAt = requireNotNull(item.rawPurgedAt)
        assertEquals("local_owner_bundle_raw_purge", item.rawPurgeReason)

        val detail = getJson("/api/evidence/${fixture.codeBundleId}", owner.token)
        assertEquals(HttpStatus.OK, detail.statusCode)
        assertFalse(detail.body.orEmpty().contains(sentinel))
        assertEquals(true, json(detail)["evidenceItems"][0]["contentText"].isNull)
        assertFalse(json(detail)["bundle"].has("filePathsJson"))
        assertFalse(json(detail)["bundle"].has("provenanceJson"))

        val repeated =
            postJson(
                "/api/evidence/${fixture.codeBundleId}/purge-raw",
                owner.token,
                emptyMap<String, String>(),
            )
        assertEquals(HttpStatus.OK, repeated.statusCode)
        assertEquals(0, json(repeated)["purgedBundles"].asInt())
        assertEquals(0, json(repeated)["purgedItems"].asInt())

        val repeatedItem = evidenceItemRepository.findByBundleId(fixture.codeBundleId).single()
        assertNull(repeatedItem.contentText)
        assertEquals(firstPurgedAt, repeatedItem.rawPurgedAt)
        assertEquals("local_owner_bundle_raw_purge", repeatedItem.rawPurgeReason)

        val reingested =
            postJson(
                "/api/ingest/manual",
                owner.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "teamId" to "team-platform",
                    "projectId" to "project-learning",
                    "title" to "Reingested provider code",
                    "sourceKind" to "code",
                    "content" to "class ProviderScopedPattern { val marker = \"$sentinel\" }",
                ),
            )
        assertEquals(HttpStatus.CREATED, reingested.statusCode)
        val reingestedBundleId = json(reingested)["bundle"]["id"].asText()
        assertNotEquals(fixture.codeBundleId, reingestedBundleId)

        val card = getJson("/api/pattern-cards/$cardId", owner.token)
        assertEquals(HttpStatus.OK, card.statusCode)
        assertEquals(cardId, json(card)["patternCard"]["id"].asText())

        val generatedAgain =
            postJson(
                "/api/generation/run",
                owner.token,
                mapOf(
                    "organizationId" to "org-demo",
                    "providerConfigId" to "provider-local-mock",
                    "sourceLinkIds" to listOf(fixture.linkId),
                    "visibility" to "organization",
                ),
            )
        assertEquals(HttpStatus.BAD_REQUEST, generatedAgain.statusCode)
    }

    @Test
    fun `local owner repository raw purge clears only matching active bundles`() {
        val owner = login()
        val repositoryUrl = "file:///tmp/learnloop-phase4-${System.nanoTime()}"
        val first = createEvidence(owner.token, "Repository purge one", "code", "const first = 1", repositoryUrl)
        val second = createEvidence(owner.token, "Repository purge two", "diff", "const second = 2", repositoryUrl)
        val other = createEvidence(owner.token, "Repository purge other", "code", "const other = 3", "file:///tmp/other-${System.nanoTime()}")
        val deleted =
            restTemplate.exchange(
                "/api/evidence/$second",
                HttpMethod.DELETE,
                HttpEntity<Void>(bearerHeaders(owner.token)),
                String::class.java,
            )
        assertEquals(HttpStatus.NO_CONTENT, deleted.statusCode)

        val purged =
            postJson(
                "/api/evidence/purge-raw",
                owner.token,
                mapOf("organizationId" to "org-demo", "repositoryUrl" to repositoryUrl),
            )

        assertEquals(HttpStatus.OK, purged.statusCode)
        assertEquals(2, json(purged)["purgedBundles"].asInt())
        assertEquals(2, json(purged)["purgedItems"].asInt())
        listOf(first, second).forEach { bundleId ->
            val item = evidenceItemRepository.findByBundleId(bundleId).single()
            assertNull(item.contentText)
            assertEquals("local_owner_repository_raw_purge", item.rawPurgeReason)
        }
        assertEquals("const other = 3", evidenceItemRepository.findByBundleId(other).single().contentText)
    }

    @Test
    fun `local owner purge all clears active raw content and skips already purged rows on repeat`() {
        val owner = login()
        val first = createEvidence(owner.token, "Purge all one", "code", "const purgeAllOne = true")
        val second = createEvidence(owner.token, "Purge all two", "conversation", "Use the purge all path")
        val deleted =
            restTemplate.exchange(
                "/api/evidence/$first",
                HttpMethod.DELETE,
                HttpEntity<Void>(bearerHeaders(owner.token)),
                String::class.java,
            )
        assertEquals(HttpStatus.NO_CONTENT, deleted.statusCode)

        val purged =
            postJson(
                "/api/evidence/purge-raw",
                owner.token,
                mapOf("organizationId" to "org-demo", "purgeAll" to true),
            )

        assertEquals(HttpStatus.OK, purged.statusCode)
        assertTrue(json(purged)["purgedBundles"].asInt() >= 2)
        assertTrue(json(purged)["purgedItems"].asInt() >= 2)
        val firstItem = evidenceItemRepository.findByBundleId(first).single()
        val secondItem = evidenceItemRepository.findByBundleId(second).single()
        assertNull(firstItem.contentText)
        assertNull(secondItem.contentText)
        val firstPurgedAt = requireNotNull(firstItem.rawPurgedAt)
        assertEquals("local_owner_all_raw_purge", firstItem.rawPurgeReason)
        assertEquals("local_owner_all_raw_purge", secondItem.rawPurgeReason)

        val repeated =
            postJson(
                "/api/evidence/purge-raw",
                owner.token,
                mapOf("organizationId" to "org-demo", "purgeAll" to true),
            )

        assertEquals(HttpStatus.OK, repeated.statusCode)
        assertEquals(0, json(repeated)["purgedBundles"].asInt())
        assertEquals(0, json(repeated)["purgedItems"].asInt())
        val repeatedFirstItem = evidenceItemRepository.findByBundleId(first).single()
        assertEquals(firstPurgedAt, repeatedFirstItem.rawPurgedAt)
        assertEquals("local_owner_all_raw_purge", repeatedFirstItem.rawPurgeReason)
    }

    private fun login(): SessionResponse {
        val response = restTemplate.postForEntity("/api/session", LoginRequest("owner@local.learnloop", "demo-password"), SessionResponse::class.java)
        assertEquals(HttpStatus.CREATED, response.statusCode)
        return requireNotNull(response.body)
    }

    private fun createEvidence(
        token: String,
        title: String,
        sourceKind: String,
        content: String,
        repositoryUrl: String? = null,
        filePaths: List<String> = emptyList(),
        provenance: Map<String, String> = emptyMap(),
    ): String {
        val created =
            postJson(
                "/api/ingest/manual",
                token,
                mapOf(
                    "organizationId" to "org-demo",
                    "teamId" to "team-platform",
                    "projectId" to "project-learning",
                    "title" to title,
                    "sourceKind" to sourceKind,
                    "repositoryUrl" to repositoryUrl,
                    "filePaths" to filePaths,
                    "provenance" to provenance,
                    "content" to content,
                ),
            )
        assertEquals(HttpStatus.CREATED, created.statusCode)
        return json(created)["bundle"]["id"].asText()
    }

    private fun createConfirmedAccessibleSourceLinkFixture(
        token: String,
        codeContent: String = "class ProviderScopedPattern",
        codeFilePaths: List<String> = emptyList(),
        codeProvenance: Map<String, String> = emptyMap(),
    ): ConfirmedSourceLinkFixture {
        val code = createEvidence(token, "Accessible provider code", "code", codeContent, filePaths = codeFilePaths, provenance = codeProvenance)
        val conversation = createEvidence(token, "Accessible provider conversation", "conversation", "Use a provider for this generated pattern.")
        val suggested =
            postJson(
                "/api/source-links/suggest",
                token,
                mapOf(
                    "conversationBundleId" to conversation,
                    "codeBundleId" to code,
                ),
            )
        val linkId = json(suggested)["links"][0]["id"].asText()
        val confirmed = postJson("/api/source-links/$linkId/confirm", token, emptyMap<String, String>())
        assertEquals("confirmed", json(confirmed)["status"].asText())
        return ConfirmedSourceLinkFixture(
            linkId = linkId,
            codeBundleId = code,
            conversationBundleId = conversation,
        )
    }

    private fun ageEvidenceForRetention(bundleId: String) {
        jdbcTemplate.update("UPDATE source_bundles SET created_at = created_at - INTERVAL '45 days' WHERE id = ?", bundleId)
        jdbcTemplate.update("UPDATE evidence_items SET created_at = created_at - INTERVAL '45 days' WHERE bundle_id = ?", bundleId)
    }

    private fun clearRetentionSettings(owner: SessionResponse) {
        jdbcTemplate.update("DELETE FROM local_evidence_retention_settings WHERE organization_id = ? AND owner_user_id = ?", "org-demo", owner.user.id)
    }

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

    private fun getJson(
        path: String,
        token: String,
    ) = restTemplate.exchange(path, HttpMethod.GET, HttpEntity<Void>(bearerHeaders(token)), String::class.java)

    private fun json(response: org.springframework.http.ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    private fun bearerHeaders(token: String): HttpHeaders =
        HttpHeaders().apply {
            setBearerAuth(token)
        }

    private fun databaseContains(needle: String): Boolean {
        val columns =
            jdbcTemplate.queryForList(
                """
                SELECT table_name, column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND data_type IN ('text', 'character varying', 'character')
                """.trimIndent(),
            )
        return columns.any { column ->
            val tableName = column.getValue("table_name").toString()
            val columnName = column.getValue("column_name").toString()
            val count =
                jdbcTemplate.queryForObject(
                    """SELECT COUNT(*) FROM "$tableName" WHERE "$columnName" LIKE ?""",
                    Int::class.java,
                    "%$needle%",
                ) ?: 0
            count > 0
        }
    }

    private data class ConfirmedSourceLinkFixture(
        val linkId: String,
        val codeBundleId: String,
        val conversationBundleId: String,
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
