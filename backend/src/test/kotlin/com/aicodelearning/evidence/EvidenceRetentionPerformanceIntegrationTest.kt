package com.aicodelearning.evidence

import com.aicodelearning.auth.KPostgreSQLContainer
import com.aicodelearning.auth.LoginRequest
import com.aicodelearning.auth.RunnerExecutorTestConfiguration
import com.aicodelearning.auth.SessionResponse
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
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
import kotlin.system.measureTimeMillis

@Testcontainers
@ActiveProfiles("local")
@Import(RunnerExecutorTestConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EvidenceRetentionPerformanceIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `retention cleanup removes ten thousand expired artifacts in bounded batches while reads continue`() {
        val owner = login()
        val marker = "retention-perf-${System.nanoTime()}"
        val batchSize = 500
        seedExpiredArtifacts(marker)
        val settings =
            patchJson(
                "/api/evidence/retention-settings",
                owner.token,
                mapOf("organizationId" to "org-demo", "retentionMode" to "default", "retentionDays" to 30),
            )
        assertEquals(HttpStatus.OK, settings.statusCode)

        var totalPurged = 0
        var totalReclaimedBytes = 0L
        var batches = 0
        var lastPurgedItems: Int
        var remainingEligibleItems: Int
        val runtimeMs =
            measureTimeMillis {
                do {
                    val cleanup =
                        postJson(
                            "/api/evidence/retention-cleanup",
                            owner.token,
                            mapOf("organizationId" to "org-demo", "batchSize" to batchSize),
                        )
                    assertEquals(HttpStatus.OK, cleanup.statusCode)
                    val body = json(cleanup)
                    lastPurgedItems = body["purgedItems"].asInt()
                    remainingEligibleItems = body["remainingEligibleItems"].asInt()
                    totalPurged += lastPurgedItems
                    totalReclaimedBytes += body["reclaimedBytes"].asLong()
                    batches += 1
                    assertEquals(batchSize, body["batchSize"].asInt())
                    assertTrue(lastPurgedItems <= batchSize)
                    assertEquals(0, body["filesystemArtifactsDeleted"].asInt())

                    val readDuringCleanup = getJson("/api/evidence?organizationId=org-demo&page=0&pageSize=5", owner.token)
                    assertEquals(HttpStatus.OK, readDuringCleanup.statusCode)
                } while (lastPurgedItems > 0 && remainingEligibleItems > 0 && batches < 100)
            }

        assertEquals(10_000, totalPurged)
        assertTrue(batches > 1)
        assertEquals(0, rawContentCount(marker))
        assertEquals(0, rawContentCount("$marker-quarantine"))
        val progress = getJson("/api/evidence/retention-settings?organizationId=org-demo", owner.token)
        assertEquals(HttpStatus.OK, progress.statusCode)
        assertEquals(0, json(progress)["lastCleanupRemainingItems"].asInt())
        assertTrue(totalReclaimedBytes > 0)
        println("retention_cleanup_perf artifacts=10000 batchSize=$batchSize batches=$batches runtimeMs=$runtimeMs reclaimedBytes=$totalReclaimedBytes")
    }

    private fun seedExpiredArtifacts(marker: String) {
        val bundleSql =
            """
            INSERT INTO source_bundles (
                id, organization_id, team_id, project_id, created_by_user_id, title, source_kind, status,
                file_paths_json, provenance_json, content_hash, secret_findings_json, created_at
            )
            VALUES (?, 'org-demo', 'team-platform', 'project-learning', 'u-local-owner', ?, ?, ?, '[]', '{}', ?, '[]', now() - INTERVAL '45 days')
            """.trimIndent()
        val itemSql =
            """
            INSERT INTO evidence_items (
                id, bundle_id, item_type, content_text, content_hash, created_at, size_bytes, metadata_json
            )
            VALUES (?, ?, ?, ?, ?, now() - INTERVAL '45 days', ?, '{}')
            """.trimIndent()

        val bundleArgs =
            (0 until 100).map { index ->
                val quarantined = index % 10 == 0
                arrayOf<Any>(
                    "bundle_perf_${marker}_$index",
                    "Performance bundle $index",
                    if (quarantined) LocalAiSessionPolicy.SOURCE_KIND else "code",
                    if (quarantined) "quarantined_secret" else "ready",
                    "0".repeat(64),
                )
            }
        val itemArgs =
            (0 until 10_000).map { index ->
                val bundleIndex = index / 100
                val quarantined = bundleIndex % 10 == 0
                val content = if (quarantined) "$marker-quarantine-$index" else "$marker-raw-$index"
                arrayOf<Any>(
                    "evidence_perf_${marker}_$index",
                    "bundle_perf_${marker}_$bundleIndex",
                    if (quarantined) LocalAiSessionPolicy.ITEM_TYPE_PROMPT else "code",
                    content,
                    "1".repeat(64),
                    content.length.toLong(),
                )
            }
        jdbcTemplate.batchUpdate(bundleSql, bundleArgs)
        jdbcTemplate.batchUpdate(itemSql, itemArgs)
    }

    private fun rawContentCount(marker: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM evidence_items WHERE content_text LIKE ?",
            Int::class.java,
            "%$marker%",
        ) ?: 0

    private fun login(): SessionResponse {
        val response = restTemplate.postForEntity("/api/session", LoginRequest("owner@local.learnloop", "demo-password"), SessionResponse::class.java)
        assertEquals(HttpStatus.CREATED, response.statusCode)
        return requireNotNull(response.body)
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
