package com.aicodelearning.platform

import com.aicodelearning.auth.KPostgreSQLContainer
import com.aicodelearning.auth.LoginRequest
import com.aicodelearning.auth.RunnerExecutorTestConfiguration
import com.aicodelearning.auth.SessionResponse
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

@Testcontainers
@ActiveProfiles("local")
@Import(RunnerExecutorTestConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalDataDeleteIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `full app data delete requires owner confirmation and removes database rows`() {
        val owner = login("owner@local.learnloop")
        val missingConfirmation =
            postDeleteAll(
                token = owner.token,
                confirmation = "delete local data",
            )
        assertEquals(HttpStatus.BAD_REQUEST, missingConfirmation.statusCode)

        val evidence =
            restTemplate.exchange(
                "/api/ingest/manual",
                HttpMethod.POST,
                HttpEntity(
                    mapOf(
                        "organizationId" to "org-demo",
                        "teamId" to "team-platform",
                        "projectId" to "project-learning",
                        "title" to "Full delete evidence",
                        "sourceKind" to "code",
                        "content" to "const fullDelete = true",
                    ),
                    bearerHeaders(owner.token),
                ),
                String::class.java,
            )
        assertEquals(HttpStatus.CREATED, evidence.statusCode)

        assertTrue(count("users") > 0)
        assertTrue(count("source_bundles") > 0)

        val deleted =
            postDeleteAll(
                token = owner.token,
                confirmation = "DELETE LOCAL DATA",
            )

        assertEquals(HttpStatus.OK, deleted.statusCode)
        val body = objectMapper.readTree(deleted.body)
        assertTrue(body["totalDeletedRows"].asInt() > 0)
        assertTrue(body["deletedRows"]["users"].asInt() > 0)
        val appTables = publicAppTables()
        assertEquals(appTables, body["deletedRows"].fieldNames().asSequence().toSet())
        appTables.forEach { table ->
            assertEquals(0, count(table), table)
        }

        val oldSession =
            restTemplate.exchange(
                "/api/me",
                HttpMethod.GET,
                HttpEntity<Void>(bearerHeaders(owner.token)),
                String::class.java,
            )
        assertEquals(HttpStatus.UNAUTHORIZED, oldSession.statusCode)

        val loginAfterDelete = restTemplate.postForEntity("/api/session", LoginRequest("owner@local.learnloop", "demo-password"), String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, loginAfterDelete.statusCode)
    }

    private fun login(email: String): SessionResponse {
        val response = restTemplate.postForEntity("/api/session", LoginRequest(email, "demo-password"), SessionResponse::class.java)
        assertEquals(HttpStatus.CREATED, response.statusCode)
        return requireNotNull(response.body)
    }

    private fun postDeleteAll(
        token: String,
        confirmation: String,
    ) = restTemplate.exchange(
        "/api/local-data/delete-all",
        HttpMethod.POST,
        HttpEntity(mapOf("confirmation" to confirmation), bearerHeaders(token)),
        String::class.java,
    )

    private fun bearerHeaders(token: String): HttpHeaders =
        HttpHeaders().apply {
            setBearerAuth(token)
        }

    private fun count(table: String): Int = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java) ?: 0

    private fun publicAppTables(): Set<String> =
        jdbcTemplate.queryForList(
            """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = 'public'
              AND table_type = 'BASE TABLE'
              AND table_name <> 'flyway_schema_history'
            """.trimIndent(),
            String::class.java,
        ).toSet()

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
