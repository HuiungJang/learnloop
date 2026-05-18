package com.aicodelearning.learning

object PracticeAccessPolicy {
    const val PROVENANCE_VISIBILITY_REDACTED = "redacted"

    fun isPublishedOrganizationPractice(
        publicationStatus: String,
        visibility: String,
    ): Boolean = publicationStatus == "published" && visibility == "organization"

    fun canReadDraftPractice(
        authorUserId: String,
        currentUserId: String,
        hasReviewerRole: Boolean,
    ): Boolean = authorUserId == currentUserId || hasReviewerRole

    fun canViewAnswers(
        authorUserId: String,
        currentUserId: String,
        hasSubmitted: Boolean,
        forceAnswers: Boolean = false,
    ): Boolean = forceAnswers || hasSubmitted || authorUserId == currentUserId

    fun provenanceVisibility(): String = PROVENANCE_VISIBILITY_REDACTED
}
