package com.aicodelearning.learning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PracticeAccessPolicyTest {
    @Test
    fun `published organization practice is learner readable`() {
        assertTrue(PracticeAccessPolicy.isPublishedOrganizationPractice("published", "organization"))
        assertFalse(PracticeAccessPolicy.isPublishedOrganizationPractice("draft", "organization"))
        assertFalse(PracticeAccessPolicy.isPublishedOrganizationPractice("published", "private"))
    }

    @Test
    fun `draft practice is readable by author or reviewer`() {
        assertTrue(PracticeAccessPolicy.canReadDraftPractice("author-1", "author-1", hasReviewerRole = false))
        assertTrue(PracticeAccessPolicy.canReadDraftPractice("author-1", "reviewer-1", hasReviewerRole = true))
        assertFalse(PracticeAccessPolicy.canReadDraftPractice("author-1", "learner-1", hasReviewerRole = false))
    }

    @Test
    fun `answers are visible only after eligibility`() {
        assertTrue(PracticeAccessPolicy.canViewAnswers("author-1", "author-1", hasSubmitted = false))
        assertTrue(PracticeAccessPolicy.canViewAnswers("author-1", "learner-1", hasSubmitted = true))
        assertTrue(PracticeAccessPolicy.canViewAnswers("author-1", "learner-1", hasSubmitted = false, forceAnswers = true))
        assertFalse(PracticeAccessPolicy.canViewAnswers("author-1", "learner-1", hasSubmitted = false))
    }

    @Test
    fun `provenance visibility is redacted for practice responses`() {
        assertEquals("redacted", PracticeAccessPolicy.provenanceVisibility())
    }
}
