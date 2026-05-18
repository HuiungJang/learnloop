package com.aicodelearning.learning

import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.organization.AuthorizationService
import com.aicodelearning.platform.BadRequestException
import com.aicodelearning.platform.ConflictException
import com.aicodelearning.platform.NotFoundException
import com.aicodelearning.platform.prefixedId
import com.aicodelearning.runner.RunnerExecutor
import com.aicodelearning.runner.RunnerRequestValidator
import com.aicodelearning.runner.RunnerRunFile
import com.aicodelearning.runner.RunnerRunRequest
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class PracticeService(
    private val problemRepository: ProblemRepository,
    private val patternCardRepository: PatternCardRepository,
    private val problemFileRepository: ProblemFileRepository,
    private val problemHintRepository: ProblemHintRepository,
    private val problemProvenanceLinkRepository: ProblemProvenanceLinkRepository,
    private val submissionRepository: SubmissionRepository,
    private val submissionFileRepository: SubmissionFileRepository,
    private val sandboxRunResultRepository: SandboxRunResultRepository,
    private val authorizationService: AuthorizationService,
    private val runnerRequestValidator: RunnerRequestValidator,
    private val runnerExecutor: RunnerExecutor,
    private val objectMapper: ObjectMapper,
) {
    @Transactional(readOnly = true)
    fun detail(
        currentUser: CurrentUser,
        problemId: String,
    ): PracticeProblemResponse {
        val problem = problemRepository.findById(problemId).orElseThrow { NotFoundException("Problem not found") }
        val card = patternCardRepository.findById(problem.patternCardId).orElseThrow { NotFoundException("Pattern card not found") }
        authorizePracticeRead(currentUser, card)

        val files = problemFileRepository.findByProblemIdOrderBySortOrderAscPathAsc(problem.id)
        val hints = problemHintRepository.findByProblemIdOrderByRevealOrderAsc(problem.id)
        val provenance = problemProvenanceLinkRepository.findByProblemIdOrderBySortOrderAsc(problem.id)

        return PracticeProblemResponse(
            id = problem.id,
            patternCardId = card.id,
            title = card.title,
            prompt = problem.prompt,
            difficulty = problem.difficulty,
            assetRevision = PracticeAssetRevision.compute(problem, files, hints, provenance),
            files =
                files
                    .filter { it.fileRole in visibleFileRoles }
                    .map {
                        PracticeFileResponse(
                            path = it.path,
                            language = it.language,
                            role = it.fileRole,
                            content = it.content,
                            readOnly = it.readOnly,
                            sortOrder = it.sortOrder,
                        )
                    },
            hints =
                hints.map {
                    PracticeHintResponse(
                        id = it.id,
                        revealOrder = it.revealOrder,
                        label = it.label,
                        content = if (it.revealPolicy == PracticeContract.HINT_REVEAL_MANUAL) it.content else null,
                        revealed = false,
                        revealPolicy = it.revealPolicy,
                    )
                },
            provenance =
                provenance.map {
                    PracticeProvenanceResponse(
                        sourceType = it.sourceType,
                        sourceLabel = it.sourceLabel,
                        redactedExcerpt = it.redactedExcerpt,
                        evidenceItemId = null,
                    )
                },
            attempt = null,
            latestRun = sandboxRunResultRepository.findFirstByUserIdAndProblemIdOrderByCreatedAtDesc(currentUser.id, problem.id)?.toPracticeRunResultResponse(),
        )
    }

    fun run(
        currentUser: CurrentUser,
        problemId: String,
        request: PracticeRunRequest,
    ): PracticeRunResultResponse {
        val problem = problemRepository.findById(problemId).orElseThrow { NotFoundException("Problem not found") }
        val card = patternCardRepository.findById(problem.patternCardId).orElseThrow { NotFoundException("Pattern card not found") }
        authorizePracticeRead(currentUser, card)
        PracticeContract.requireSupportedLanguage(request.language)
        if (request.language != PracticeContract.LANGUAGE_TYPESCRIPT) {
            throw BadRequestException("only TypeScript runs are available")
        }

        val canonicalFiles = problemFileRepository.findByProblemIdOrderBySortOrderAscPathAsc(problem.id)
        val currentAssetRevision = currentAssetRevision(problem, canonicalFiles)
        if (request.assetRevision != currentAssetRevision) {
            throw ConflictException("Practice problem has changed. Refresh before running this attempt.")
        }

        val runnerRequest =
            RunnerRunRequest(
                language = request.language,
                testHarnessId = TYPESCRIPT_HARNESS_ID,
                timeoutMs = request.timeoutMs ?: DEFAULT_RUN_TIMEOUT_MS,
                files = runnerFiles(request.files, canonicalFiles),
            )
        val validatedRequest = runnerRequestValidator.validate(runnerRequest)
        val result = runnerExecutor.run(validatedRequest)
        val tests = parseTapTests(result.stdoutExcerpt)
        val submissionId =
            request.clientAttemptId
                ?.takeIf { it.isNotBlank() }
                ?.let { submissionRepository.findByUserIdAndProblemIdAndClientAttemptId(currentUser.id, problem.id, it) }
                ?.id

        val saved =
            sandboxRunResultRepository.save(
                SandboxRunResultEntity(
                    id = validatedRequest.runId,
                    problemId = problem.id,
                    userId = currentUser.id,
                    submissionId = submissionId,
                    status = result.status,
                    runnerKind = validatedRequest.harness.id,
                    durationMs = result.durationMs,
                    testsJson = objectMapper.writeValueAsString(tests),
                    stdoutExcerpt = result.stdoutExcerpt.ifBlank { null },
                    stderrExcerpt = result.stderrExcerpt.ifBlank { null },
                    failureReason = failureReason(result.status),
                    createdAt = Instant.now(),
                ),
            )

        return saved.toPracticeRunResultResponse()
    }

    @Transactional(readOnly = true)
    fun currentUserAttempts(
        currentUser: CurrentUser,
        problemId: String,
    ): List<PracticeAttemptResponse> {
        val problem = problemRepository.findById(problemId).orElseThrow { NotFoundException("Problem not found") }
        val card = patternCardRepository.findById(problem.patternCardId).orElseThrow { NotFoundException("Pattern card not found") }
        authorizePracticeRead(currentUser, card)

        val canonicalFiles = problemFileRepository.findByProblemIdOrderBySortOrderAscPathAsc(problem.id)
        val currentAssetRevision = currentAssetRevision(problem, canonicalFiles)
        val attempts = submissionRepository.findByUserIdAndProblemIdInOrderByUpdatedAtDesc(currentUser.id, listOf(problem.id))
        val filesBySubmission =
            if (attempts.isEmpty()) {
                emptyMap()
            } else {
                submissionFileRepository
                    .findBySubmissionIdInOrderBySubmissionIdAscPathAsc(attempts.map { it.id })
                    .groupBy { it.submissionId }
            }
        val defaultLanguage = canonicalFiles.firstOrNull { it.fileRole == PracticeContract.FILE_ROLE_STARTER }?.language ?: PracticeContract.LANGUAGE_TYPESCRIPT

        return attempts.map {
            it.toPracticeAttemptResponse(filesBySubmission.getValueOrEmpty(it.id), currentAssetRevision, defaultLanguage)
        }
    }

    @Transactional
    fun syncLocalAttempt(
        currentUser: CurrentUser,
        problemId: String,
        request: PracticeAttemptSyncRequest,
    ): PracticeAttemptResponse {
        PracticeContract.validateAttemptSyncRequest(request)
        if (request.intent != PracticeContract.ATTEMPT_STATUS_DRAFT) {
            throw BadRequestException("local sync only accepts draft attempts")
        }

        val problem = problemRepository.findById(problemId).orElseThrow { NotFoundException("Problem not found") }
        val card = patternCardRepository.findById(problem.patternCardId).orElseThrow { NotFoundException("Pattern card not found") }
        authorizePracticeRead(currentUser, card)

        val canonicalFiles = problemFileRepository.findByProblemIdOrderBySortOrderAscPathAsc(problem.id)
        val currentAssetRevision = currentAssetRevision(problem, canonicalFiles)
        if (request.assetRevision != currentAssetRevision) {
            throw ConflictException("Practice problem has changed. Refresh before syncing this attempt.")
        }

        val now = Instant.now()
        val existing =
            submissionRepository.findByUserIdAndProblemIdAndClientAttemptId(
                currentUser.id,
                problem.id,
                request.clientAttemptId,
            )
        if (existing?.attemptStatus == PracticeContract.ATTEMPT_STATUS_SUBMITTED) {
            throw ConflictException("Submitted attempts cannot be updated through local sync")
        }

        val attempt =
            existing
                ?: SubmissionEntity(
                    id = prefixedId("submission"),
                    problemId = problem.id,
                    userId = currentUser.id,
                    textAnswer = "",
                    resultStatus = PracticeContract.RESULT_STATUS_SUBMITTED,
                    createdAt = now,
                    clientAttemptId = request.clientAttemptId,
                )

        attempt.assetRevision = request.assetRevision
        attempt.language = request.language
        attempt.attemptStatus = request.intent
        attempt.metadataJson = """{"localUpdatedAt":"${request.localUpdatedAt}"}"""
        attempt.updatedAt = now
        val savedAttempt = submissionRepository.save(attempt)

        submissionFileRepository.deleteBySubmissionId(savedAttempt.id)
        val savedFiles =
            submissionFileRepository.saveAll(
                request.files.map { file ->
                    SubmissionFileEntity(
                        id = prefixedId("submission_file"),
                        submissionId = savedAttempt.id,
                        path = PracticeContract.normalizeFilePath(file.path),
                        content = file.content,
                        createdAt = now,
                    )
                },
            )

        val defaultLanguage = canonicalFiles.firstOrNull { it.fileRole == PracticeContract.FILE_ROLE_STARTER }?.language ?: request.language
        return savedAttempt.toPracticeAttemptResponse(savedFiles.sortedBy { it.path }, currentAssetRevision, defaultLanguage)
    }

    private fun authorizePracticeRead(
        currentUser: CurrentUser,
        card: PatternCardEntity,
    ) {
        if (PracticeAccessPolicy.isPublishedOrganizationPractice(card.publicationStatus, card.visibility)) {
            authorizationService.requireRole(currentUser, card.organizationId, "learner", card.teamId, card.projectId)
            return
        }

        if (PracticeAccessPolicy.canReadDraftPractice(card.createdByUserId, currentUser.id, hasReviewerRole = false)) {
            return
        }

        authorizationService.requireRole(currentUser, card.organizationId, "reviewer", card.teamId, card.projectId)
    }

    private fun currentAssetRevision(
        problem: ProblemEntity,
        files: List<ProblemFileEntity> = problemFileRepository.findByProblemIdOrderBySortOrderAscPathAsc(problem.id),
    ): String =
        PracticeAssetRevision.compute(
            problem = problem,
            files = files,
            hints = problemHintRepository.findByProblemIdOrderByRevealOrderAsc(problem.id),
            provenance = problemProvenanceLinkRepository.findByProblemIdOrderBySortOrderAsc(problem.id),
        )

    private fun SubmissionEntity.toPracticeAttemptResponse(
        files: List<SubmissionFileEntity>,
        defaultAssetRevision: String,
        defaultLanguage: String,
    ): PracticeAttemptResponse =
        PracticeAttemptResponse(
            id = id,
            problemId = problemId,
            clientAttemptId = clientAttemptId ?: id,
            assetRevision = assetRevision ?: defaultAssetRevision,
            language = language ?: defaultLanguage,
            status = attemptStatus,
            files = files.map { PracticeAttemptFileResponse(path = it.path, content = it.content) },
            score = score,
            resultStatus = resultStatus,
            updatedAt = updatedAt,
            submittedAt = submittedAt,
        )

    private fun SandboxRunResultEntity.toPracticeRunResultResponse(): PracticeRunResultResponse =
        PracticeRunResultResponse(
            id = id,
            status = status,
            runnerKind = runnerKind,
            durationMs = durationMs,
            tests = parseTestsJson(testsJson),
            stdoutExcerpt = stdoutExcerpt,
            stderrExcerpt = stderrExcerpt,
            failedDiff = failedDiff,
            failureReason = failureReason,
            createdAt = createdAt,
        )

    private fun runnerFiles(
        requestFiles: List<PracticeAttemptFileRequest>,
        canonicalFiles: List<ProblemFileEntity>,
    ): List<RunnerRunFile> {
        if (requestFiles.isEmpty()) {
            throw BadRequestException("run files are required")
        }
        val testFiles = canonicalFiles.filter { it.fileRole == PracticeContract.FILE_ROLE_TEST }
        if (testFiles.isEmpty()) {
            throw BadRequestException("practice problem has no runnable tests")
        }

        return requestFiles.map { RunnerRunFile(path = it.path, content = it.content) } +
            testFiles.map { RunnerRunFile(path = it.path, content = it.content) }
    }

    private fun parseTestsJson(testsJson: String): List<PracticeRunTestResponse> =
        try {
            objectMapper.readValue(testsJson, object : TypeReference<List<PracticeRunTestResponse>>() {})
        } catch (_: Exception) {
            emptyList()
        }

    private fun parseTapTests(stdout: String): List<PracticeRunTestResponse> =
        stdout
            .lineSequence()
            .mapNotNull { line ->
                val match = tapResultRegex.find(line.trim()) ?: return@mapNotNull null
                val status = if (match.groupValues[1] == "ok") PracticeContract.RUN_STATUS_PASSED else PracticeContract.RUN_STATUS_FAILED
                PracticeRunTestResponse(
                    name = match.groupValues[2],
                    status = status,
                    message = null,
                    durationMs = null,
                )
            }
            .toList()

    private fun failureReason(status: String): String? =
        when (status) {
            PracticeContract.RUN_STATUS_PASSED -> "All tests passed."
            PracticeContract.RUN_STATUS_FAILED -> "One or more tests failed."
            PracticeContract.RUN_STATUS_COMPILE_ERROR -> "TypeScript compilation failed."
            PracticeContract.RUN_STATUS_TIMEOUT -> "The run exceeded the time limit."
            PracticeContract.RUN_STATUS_RESOURCE_LIMITED -> "The run exceeded sandbox resource limits."
            PracticeContract.RUN_STATUS_RUNNER_UNAVAILABLE -> "The local runner is unavailable."
            else -> null
        }

    private companion object {
        const val DEFAULT_RUN_TIMEOUT_MS = 5_000L
        const val TYPESCRIPT_HARNESS_ID = "typescript-node-test"
        val tapResultRegex = Regex("^(ok|not ok)\\s+\\d+\\s+-\\s+(.+)$")
        val visibleFileRoles = setOf(PracticeContract.FILE_ROLE_STARTER, PracticeContract.FILE_ROLE_SUPPORT)
    }
}

private fun <T> Map<String, List<T>>.getValueOrEmpty(key: String): List<T> = this[key] ?: emptyList()
