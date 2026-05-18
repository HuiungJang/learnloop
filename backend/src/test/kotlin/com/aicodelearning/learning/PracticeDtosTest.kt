package com.aicodelearning.learning

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PracticeDtosTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `practice response does not expose answer or hidden evidence fields`() {
        val response =
            PracticeProblemResponse(
                id = "problem-1",
                patternCardId = "card-1",
                title = "Invalidate query keys",
                prompt = "Update the cache invalidation helper.",
                difficulty = "easy",
                assetRevision = "rev-1",
                files =
                    listOf(
                        PracticeFileResponse(
                            path = "src/cache.ts",
                            language = "typescript",
                            role = "starter",
                            content = "export function invalidate() {}",
                            readOnly = false,
                            sortOrder = 0,
                        ),
                    ),
                hints =
                    listOf(
                        PracticeHintResponse(
                            id = "hint-1",
                            revealOrder = 1,
                            label = "Query key",
                            content = null,
                            revealed = false,
                            revealPolicy = PracticeContract.HINT_REVEAL_AFTER_RUN,
                        ),
                    ),
                provenance =
                    listOf(
                        PracticeProvenanceResponse(
                            sourceType = "diff",
                            sourceLabel = "PR diff",
                            redactedExcerpt = "queryClient.invalidateQueries(...)",
                            evidenceItemId = "evidence-1",
                        ),
                    ),
                attempt = null,
                latestRun = null,
            )

        val json = objectMapper.writeValueAsString(response)

        assertTrue(json.contains("assetRevision"))
        assertTrue(json.contains("redactedExcerpt"))
        assertFalse(json.contains("referenceAnswer"))
        assertFalse(json.contains("solution"))
        assertFalse(json.contains("hiddenTest"))
        assertFalse(json.contains("rawEvidence"))
    }
}
