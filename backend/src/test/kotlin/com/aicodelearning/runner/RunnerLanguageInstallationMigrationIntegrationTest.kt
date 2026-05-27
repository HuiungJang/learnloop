package com.aicodelearning.runner

import com.aicodelearning.auth.KPostgreSQLContainer
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
class RunnerLanguageInstallationMigrationIntegrationTest {
    @Test
    fun `runner language installation migration seeds local desired defaults`() {
        val dataSource =
            DriverManagerDataSource().apply {
                setDriverClassName("org.postgresql.Driver")
                url = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
            }

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val jdbcTemplate = JdbcTemplate(dataSource)
        val rows =
            jdbcTemplate.query(
                """
                SELECT language, desired_enabled, status, install_stage, last_error_code
                FROM runner_language_installations
                ORDER BY language
                """.trimIndent(),
            ) { rs, _ ->
                listOf(
                    rs.getString("language"),
                    rs.getBoolean("desired_enabled"),
                    rs.getString("status"),
                    rs.getString("install_stage"),
                    rs.getString("last_error_code"),
                )
            }

        assertEquals(
            listOf(
                listOf("java", true, "missing", null, null),
                listOf("kotlin", true, "missing", null, null),
                listOf("rust", false, "available", null, null),
                listOf("swift", false, "available", null, null),
                listOf("typescript", true, "missing", null, null),
            ),
            rows,
        )

        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                INSERT INTO runner_language_installations (language, desired_enabled, image_ref, status)
                VALUES ('ruby', false, 'ruby:latest', 'queued')
                """.trimIndent(),
            )
        }
    }

    companion object {
        @Container
        @JvmField
        val postgres = KPostgreSQLContainer("postgres:16-alpine")
    }
}
