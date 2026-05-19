package com.aicodelearning.platform

import com.aicodelearning.auth.CurrentUser
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class LocalDataService(
    private val jdbcTemplate: JdbcTemplate,
    private val localOwnerAccess: LocalOwnerAccess,
) {
    @Transactional
    fun deleteAll(
        currentUser: CurrentUser,
        confirmation: String,
    ): LocalDataDeleteResponse {
        localOwnerAccess.requireLocalOwner(currentUser)
        if (confirmation != REQUIRED_CONFIRMATION) {
            throw BadRequestException("Confirmation phrase is required")
        }

        val deletedRows = linkedMapOf<String, Int>()
        deleteOrder.forEach { table ->
            deletedRows[table] = jdbcTemplate.update("DELETE FROM $table")
        }
        return LocalDataDeleteResponse(deletedRows = deletedRows, totalDeletedRows = deletedRows.values.sum())
    }

    private companion object {
        const val REQUIRED_CONFIRMATION = "DELETE LOCAL DATA"

        val deleteOrder =
            listOf(
                "submission_files",
                "sandbox_run_results",
                "submissions",
                "proficiency_scores",
                "problem_provenance_links",
                "problem_hints",
                "problem_files",
                "problems",
                "review_decisions",
                "review_tasks",
                "pattern_tag_links",
                "pattern_tags",
                "pattern_cards",
                "generation_runs",
                "source_bundle_attribution_events",
                "source_links",
                "evidence_items",
                "local_repository_consents",
                "source_bundles",
                "ai_providers",
                "audit_logs",
                "session_tokens",
                "memberships",
                "projects",
                "teams",
                "organizations",
                "users",
            )
    }
}

data class LocalDataDeleteResponse(
    val deletedRows: Map<String, Int>,
    val totalDeletedRows: Int,
)
