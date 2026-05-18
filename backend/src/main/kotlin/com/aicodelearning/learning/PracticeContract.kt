package com.aicodelearning.learning

import com.aicodelearning.platform.BadRequestException

object PracticeContract {
    const val LANGUAGE_TYPESCRIPT = "typescript"
    const val LANGUAGE_KOTLIN = "kotlin"
    const val LANGUAGE_JAVA = "java"

    const val FILE_ROLE_STARTER = "starter"
    const val FILE_ROLE_TEST = "test"
    const val FILE_ROLE_SUPPORT = "support"
    const val FILE_ROLE_SOLUTION = "solution"
    const val FILE_ROLE_HIDDEN_TEST = "hidden_test"

    const val HINT_REVEAL_MANUAL = "manual"
    const val HINT_REVEAL_AFTER_RUN = "after_run"
    const val HINT_REVEAL_AFTER_SUBMIT = "after_submit"
    const val HINT_REVEAL_PATTERN = "pattern_reveal"

    const val ATTEMPT_STATUS_DRAFT = "draft"
    const val ATTEMPT_STATUS_SUBMITTED = "submitted"

    const val RUN_STATUS_PASSED = "passed"
    const val RUN_STATUS_FAILED = "failed"
    const val RUN_STATUS_COMPILE_ERROR = "compile_error"
    const val RUN_STATUS_TIMEOUT = "timeout"
    const val RUN_STATUS_RESOURCE_LIMITED = "resource_limited"
    const val RUN_STATUS_RUNNER_UNAVAILABLE = "runner_unavailable"

    const val RESULT_STATUS_SUBMITTED = "submitted"
    const val RESULT_STATUS_PASSED = "passed"
    const val RESULT_STATUS_FAILED = "failed"
    const val RESULT_STATUS_NEEDS_REVIEW = "needs_review"

    const val MAX_FILE_COUNT = 20
    const val MAX_FILE_BYTES = 64 * 1024
    const val MAX_TOTAL_FILE_BYTES = 256 * 1024
    const val MAX_STDIO_EXCERPT_BYTES = 16 * 1024
    const val MAX_PROVENANCE_EXCERPT_CHARS = 2_000
    const val MAX_FILE_PATH_CHARS = 240

    val supportedLanguages = setOf(LANGUAGE_TYPESCRIPT, LANGUAGE_KOTLIN, LANGUAGE_JAVA)
    val fileRoles = setOf(FILE_ROLE_STARTER, FILE_ROLE_TEST, FILE_ROLE_SUPPORT, FILE_ROLE_SOLUTION, FILE_ROLE_HIDDEN_TEST)
    val hintRevealPolicies = setOf(HINT_REVEAL_MANUAL, HINT_REVEAL_AFTER_RUN, HINT_REVEAL_AFTER_SUBMIT, HINT_REVEAL_PATTERN)
    val attemptStatuses = setOf(ATTEMPT_STATUS_DRAFT, ATTEMPT_STATUS_SUBMITTED)
    val runStatuses = setOf(RUN_STATUS_PASSED, RUN_STATUS_FAILED, RUN_STATUS_COMPILE_ERROR, RUN_STATUS_TIMEOUT, RUN_STATUS_RESOURCE_LIMITED, RUN_STATUS_RUNNER_UNAVAILABLE)
    val resultStatuses = setOf(RESULT_STATUS_SUBMITTED, RESULT_STATUS_PASSED, RESULT_STATUS_FAILED, RESULT_STATUS_NEEDS_REVIEW)

    fun requireSupportedLanguage(language: String) {
        requireAllowed("language", language, supportedLanguages)
    }

    fun requireFileRole(role: String) {
        requireAllowed("file role", role, fileRoles)
    }

    fun requireHintRevealPolicy(policy: String) {
        requireAllowed("hint reveal policy", policy, hintRevealPolicies)
    }

    fun requireAttemptStatus(status: String) {
        requireAllowed("attempt status", status, attemptStatuses)
    }

    fun requireRunStatus(status: String) {
        requireAllowed("run status", status, runStatuses)
    }

    fun requireResultStatus(status: String) {
        requireAllowed("result status", status, resultStatuses)
    }

    fun validateAttemptSyncRequest(request: PracticeAttemptSyncRequest) {
        requireSupportedLanguage(request.language)
        requireAttemptStatus(request.intent)
        if (request.files.isEmpty()) {
            throw BadRequestException("attempt files are required")
        }
        if (request.files.size > MAX_FILE_COUNT) {
            throw BadRequestException("attempt may include at most $MAX_FILE_COUNT files")
        }

        var totalBytes = 0
        val normalizedPaths = mutableSetOf<String>()
        request.files.forEach { file ->
            val normalizedPath = normalizeFilePath(file.path)
            if (!normalizedPaths.add(normalizedPath)) {
                throw BadRequestException("attempt file paths must be unique")
            }

            val fileBytes = file.content.toByteArray(Charsets.UTF_8).size
            if (fileBytes > MAX_FILE_BYTES) {
                throw BadRequestException("attempt file exceeds maximum size")
            }
            totalBytes += fileBytes
        }

        if (totalBytes > MAX_TOTAL_FILE_BYTES) {
            throw BadRequestException("attempt payload exceeds maximum size")
        }
    }

    fun normalizeFilePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isBlank()) {
            throw BadRequestException("file path is required")
        }
        if (trimmed.length > MAX_FILE_PATH_CHARS) {
            throw BadRequestException("file path exceeds maximum length")
        }
        if (trimmed.contains('\u0000')) {
            throw BadRequestException("file path contains invalid characters")
        }
        if (trimmed.startsWith("/") || trimmed.startsWith("~") || windowsDrivePattern.matches(trimmed)) {
            throw BadRequestException("file path must be relative")
        }
        if (trimmed.contains('\\')) {
            throw BadRequestException("file path must use forward slashes")
        }

        val parts = trimmed.split("/")
        if (parts.any { it.isBlank() || it == "." || it == ".." }) {
            throw BadRequestException("file path must stay inside the exercise root")
        }

        return parts.joinToString("/")
    }

    private fun requireAllowed(
        field: String,
        value: String,
        allowed: Set<String>,
    ) {
        if (value !in allowed) {
            throw BadRequestException("$field must be one of: ${allowed.sorted().joinToString(", ")}")
        }
    }

    private val windowsDrivePattern = Regex("^[A-Za-z]:.*")
}
