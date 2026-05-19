package com.aicodelearning.evidence

import com.aicodelearning.auth.KPostgreSQLContainer
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
class EvidenceSchemaMigrationIntegrationTest {
    @Test
    fun `local session schema migration preserves old evidence rows and enforces new constraints`() {
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
            .target("10")
            .load()
            .migrate()

        val jdbcTemplate = JdbcTemplate(dataSource)
        insertOldEvidenceRow(jdbcTemplate)

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val oldBundle = jdbcTemplate.queryForMap("SELECT * FROM source_bundles WHERE id = 'bundle_old_code'")
        assertEquals("code", oldBundle.getValue("source_kind"))
        assertEquals("manual_or_unknown", oldBundle.getValue("auto_attribution"))
        assertNull(oldBundle["user_attribution"])
        assertNull(oldBundle["attribution_confidence"])
        assertEquals("[]", oldBundle.getValue("attribution_reasons_json"))

        val oldItem = jdbcTemplate.queryForMap("SELECT * FROM evidence_items WHERE id = 'evidence_old_code'")
        assertEquals("code", oldItem.getValue("item_type"))
        assertNull(oldItem["repo_relative_path"])
        assertNull(oldItem["size_bytes"])
        assertEquals("{}", oldItem.getValue("metadata_json"))
        assertEquals(false, oldItem.getValue("content_truncated"))
        assertNull(oldItem["raw_purged_at"])

        val legacyItem = jdbcTemplate.queryForMap("SELECT * FROM evidence_items WHERE id = 'evidence_legacy_custom'")
        assertEquals("legacy_custom", legacyItem.getValue("item_type"))

        val oldRun = jdbcTemplate.queryForMap("SELECT * FROM generation_runs WHERE id = 'run_old_generation'")
        assertEquals("[]", oldRun.getValue("source_bundle_ids_json"))
        assertEquals("[]", oldRun.getValue("evidence_item_ids_json"))

        insertLocalSessionBundle(jdbcTemplate)
        assertEquals(1, count(jdbcTemplate, "source_bundle_attribution_events"))
        jdbcTemplate.update(
            """
            UPDATE evidence_items
            SET content_text = NULL, raw_purged_at = now(), raw_purge_reason = 'migration_test_raw_purge'
            WHERE id = 'evidence_legacy_custom'
            """.trimIndent(),
        )
        assertNull(jdbcTemplate.queryForMap("SELECT content_text FROM evidence_items WHERE id = 'evidence_legacy_custom'")["content_text"])

        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                INSERT INTO source_bundles (
                    id, organization_id, created_by_user_id, title, source_kind, status,
                    file_paths_json, provenance_json, content_hash, secret_findings_json
                )
                VALUES (
                    'bundle_invalid_kind', 'org-test', 'u-test', 'Invalid kind', 'hosted_session', 'ready',
                    '[]', '{}', '${"c".repeat(64)}', '[]'
                )
                """.trimIndent(),
            )
        }

        jdbcTemplate.update(
            """
            INSERT INTO evidence_items (id, bundle_id, item_type, content_hash)
            VALUES ('evidence_contract_validated_by_service', 'bundle_local_session', 'terminal_scrollback', '${"d".repeat(64)}')
            """.trimIndent(),
        )
        assertEquals("terminal_scrollback", jdbcTemplate.queryForMap("SELECT item_type FROM evidence_items WHERE id = 'evidence_contract_validated_by_service'").getValue("item_type"))
    }

    private fun insertOldEvidenceRow(jdbcTemplate: JdbcTemplate) {
        jdbcTemplate.update("INSERT INTO organizations (id, name, slug) VALUES ('org-test', 'Test Org', 'test')")
        jdbcTemplate.update(
            """
            INSERT INTO users (id, email, display_name, password_hash)
            VALUES ('u-test', 'test@example.com', 'Test User', 'hash')
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO source_bundles (
                id, organization_id, created_by_user_id, title, source_kind, status,
                file_paths_json, provenance_json, content_hash, secret_findings_json
            )
            VALUES (
                'bundle_old_code', 'org-test', 'u-test', 'Old code evidence', 'code', 'ready',
                '[]', '{}', '${"a".repeat(64)}', '[]'
            )
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO evidence_items (id, bundle_id, item_type, content_text, content_hash)
            VALUES ('evidence_old_code', 'bundle_old_code', 'code', 'class OldCode', '${"a".repeat(64)}')
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO evidence_items (id, bundle_id, item_type, content_text, content_hash)
            VALUES ('evidence_legacy_custom', 'bundle_old_code', 'legacy_custom', 'legacy payload', '${"f".repeat(64)}')
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
            VALUES ('run_old_generation', 'org-test', 'provider-test', 'u-test', 'completed', 'organization')
            """.trimIndent(),
        )
    }

    private fun insertLocalSessionBundle(jdbcTemplate: JdbcTemplate) {
        jdbcTemplate.update(
            """
            INSERT INTO source_bundles (
                id, organization_id, created_by_user_id, title, source_kind, status,
                file_paths_json, provenance_json, content_hash, secret_findings_json,
                auto_attribution, attribution_confidence, attribution_reasons_json
            )
            VALUES (
                'bundle_local_session', 'org-test', 'u-test', 'Local session', 'local_ai_session', 'ready',
                '["src/App.tsx"]', '{"tool":"codex"}', '${"b".repeat(64)}', '[]',
                'ai_assisted', 0.93000, '["tool_session"]'
            )
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO evidence_items (
                id, bundle_id, item_type, content_text, content_hash,
                repo_relative_path, size_bytes, metadata_json, content_truncated
            )
            VALUES (
                'evidence_local_prompt', 'bundle_local_session', 'prompt', 'Refactor this code', '${"e".repeat(64)}',
                'prompts/session.txt', 18, '{"role":"user"}', false
            )
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO source_bundle_attribution_events (
                id, bundle_id, organization_id, actor_user_id, event_type,
                auto_attribution, attribution_confidence, attribution_reasons_json
            )
            VALUES (
                'attr_event_local_session', 'bundle_local_session', 'org-test', 'u-test', 'auto_detected',
                'ai_assisted', 0.93000, '["tool_session"]'
            )
            """.trimIndent(),
        )
    }

    private fun count(
        jdbcTemplate: JdbcTemplate,
        table: String,
    ): Int = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java) ?: 0

    companion object {
        @Container
        @JvmField
        val postgres = KPostgreSQLContainer("postgres:16-alpine")
    }
}
