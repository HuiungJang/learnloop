package com.aicodelearning.learning

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
class PracticeTaxonomyMigrationIntegrationTest {
    @Test
    fun `practice taxonomy migration normalizes difficulty and allows Swift and Rust`() {
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
            .target("18")
            .load()
            .migrate()

        val jdbcTemplate = JdbcTemplate(dataSource)
        insertBaseRows(jdbcTemplate)
        insertProblem(jdbcTemplate, "problem-easy", "beginner")
        insertProblem(jdbcTemplate, "problem-medium", "intermediate")
        insertProblem(jdbcTemplate, "problem-hard", "advanced")

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        assertEquals("easy", difficulty(jdbcTemplate, "problem-easy"))
        assertEquals("medium", difficulty(jdbcTemplate, "problem-medium"))
        assertEquals("hard", difficulty(jdbcTemplate, "problem-hard"))

        insertProblem(jdbcTemplate, "problem-swift", "easy")
        insertProblemFile(jdbcTemplate, "file-swift", "problem-swift", "Solution.swift", "swift")
        insertSubmission(jdbcTemplate, "submission-swift", "problem-swift", "swift")

        insertProblem(jdbcTemplate, "problem-rust", "medium")
        insertProblemFile(jdbcTemplate, "file-rust", "problem-rust", "src/lib.rs", "rust")
        insertSubmission(jdbcTemplate, "submission-rust", "problem-rust", "rust")

        assertThrows(DataIntegrityViolationException::class.java) {
            insertProblem(jdbcTemplate, "problem-beginner-invalid", "beginner")
        }
        assertThrows(DataIntegrityViolationException::class.java) {
            insertProblemFile(jdbcTemplate, "file-ruby-invalid", "problem-swift", "solution.rb", "ruby")
        }
        assertThrows(DataIntegrityViolationException::class.java) {
            insertSubmission(jdbcTemplate, "submission-ruby-invalid", "problem-swift", "ruby")
        }
    }

    private fun insertBaseRows(jdbcTemplate: JdbcTemplate) {
        jdbcTemplate.update("INSERT INTO organizations (id, name, slug) VALUES ('org-test', 'Test Org', 'test')")
        jdbcTemplate.update(
            """
            INSERT INTO users (id, email, display_name, password_hash)
            VALUES ('u-test', 'test@example.com', 'Test User', 'hash')
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO ai_providers (
                id, organization_id, created_by_user_id, provider, model, scope, auth_type,
                retention_mode, credential_ref, credential_fingerprint, status, org_approved
            )
            VALUES (
                'provider-test', 'org-test', 'u-test', 'local', 'mock', 'organization', 'local',
                'none', 'local://mock', '${"1".repeat(64)}', 'active', true
            )
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO generation_runs (
                id, organization_id, provider_config_id, created_by_user_id, status, visibility
            )
            VALUES ('run-test', 'org-test', 'provider-test', 'u-test', 'completed', 'organization')
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO pattern_cards (
                id, organization_id, generation_run_id, created_by_user_id, title, summary, visibility, publication_status
            )
            VALUES (
                'card-test', 'org-test', 'run-test', 'u-test', 'Practice Card', 'A reusable practice card.',
                'organization', 'published'
            )
            """.trimIndent(),
        )
    }

    private fun insertProblem(
        jdbcTemplate: JdbcTemplate,
        id: String,
        difficulty: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO problems (id, pattern_card_id, problem_type, prompt, reference_answer, difficulty)
            VALUES (?, 'card-test', 'qa', 'When should this pattern be used?', 'Use it for similar technical constraints.', ?)
            """.trimIndent(),
            id,
            difficulty,
        )
    }

    private fun insertProblemFile(
        jdbcTemplate: JdbcTemplate,
        id: String,
        problemId: String,
        path: String,
        language: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO problem_files (id, problem_id, path, language, file_role, content)
            VALUES (?, ?, ?, ?, 'starter', 'solution')
            """.trimIndent(),
            id,
            problemId,
            path,
            language,
        )
    }

    private fun insertSubmission(
        jdbcTemplate: JdbcTemplate,
        id: String,
        problemId: String,
        language: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO submissions (id, problem_id, user_id, text_answer, result_status, language)
            VALUES (?, ?, 'u-test', 'answer', 'submitted', ?)
            """.trimIndent(),
            id,
            problemId,
            language,
        )
    }

    private fun difficulty(
        jdbcTemplate: JdbcTemplate,
        problemId: String,
    ): String =
        jdbcTemplate.queryForObject(
            "SELECT difficulty FROM problems WHERE id = ?",
            String::class.java,
            problemId,
        ) ?: error("missing problem")

    companion object {
        @Container
        @JvmField
        val postgres = KPostgreSQLContainer("postgres:16-alpine")
    }
}
