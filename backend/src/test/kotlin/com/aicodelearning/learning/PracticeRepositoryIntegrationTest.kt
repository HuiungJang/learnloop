package com.aicodelearning.learning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest
class PracticeRepositoryIntegrationTest {
    @Autowired
    private lateinit var patternCardRepository: PatternCardRepository

    @Autowired
    private lateinit var problemRepository: ProblemRepository

    @Autowired
    private lateinit var problemFileRepository: ProblemFileRepository

    @Autowired
    private lateinit var problemHintRepository: ProblemHintRepository

    @Autowired
    private lateinit var problemProvenanceLinkRepository: ProblemProvenanceLinkRepository

    @Autowired
    private lateinit var submissionRepository: SubmissionRepository

    @Autowired
    private lateinit var submissionFileRepository: SubmissionFileRepository

    @Autowired
    private lateinit var sandboxRunResultRepository: SandboxRunResultRepository

    @Test
    fun `practice repositories load files hints provenance and latest attempt`() {
        val suffix = System.nanoTime().toString()
        val now = Instant.now()
        val cardId = "card-practice-$suffix"
        val problemId = "problem-practice-$suffix"
        val oldSubmissionId = "submission-old-$suffix"
        val latestSubmissionId = "submission-latest-$suffix"

        patternCardRepository.save(
            PatternCardEntity(
                id = cardId,
                organizationId = "org-demo",
                teamId = "team-platform",
                projectId = "project-learning",
                createdByUserId = "u-contributor",
                title = "Repository-backed practice",
                summary = "Loads the practice workbench read model.",
                visibility = "organization",
                publicationStatus = "published",
                createdAt = now,
                publishedAt = now,
            ),
        )
        problemRepository.save(
            ProblemEntity(
                id = problemId,
                patternCardId = cardId,
                problemType = "implementation",
                prompt = "Implement the formatter.",
                referenceAnswer = "Use a pure formatting function.",
                difficulty = "easy",
                createdAt = now,
            ),
        )

        problemFileRepository.saveAll(
            listOf(
                ProblemFileEntity(
                    id = "problem-file-main-$suffix",
                    problemId = problemId,
                    path = "src/main.ts",
                    language = "typescript",
                    fileRole = "starter",
                    content = "export function format(input: string) { return input }",
                    readOnly = false,
                    sortOrder = 1,
                    createdAt = now,
                ),
                ProblemFileEntity(
                    id = "problem-file-test-$suffix",
                    problemId = problemId,
                    path = "src/main.test.ts",
                    language = "typescript",
                    fileRole = "test",
                    content = "expect(format('a')).toBe('A')",
                    readOnly = true,
                    sortOrder = 2,
                    createdAt = now,
                ),
            ),
        )
        problemHintRepository.save(
            ProblemHintEntity(
                id = "problem-hint-$suffix",
                problemId = problemId,
                revealOrder = 1,
                label = "Normalize before returning",
                content = "Use uppercase conversion before returning the value.",
                revealPolicy = "manual",
                createdAt = now,
            ),
        )
        problemProvenanceLinkRepository.save(
            ProblemProvenanceLinkEntity(
                id = "problem-provenance-$suffix",
                problemId = problemId,
                sourceType = "diff",
                sourceLabel = "Repository diff",
                redactedExcerpt = "A formatter changed behavior in a generated diff.",
                sortOrder = 1,
                createdAt = now,
            ),
        )

        submissionRepository.save(
            SubmissionEntity(
                id = oldSubmissionId,
                problemId = problemId,
                userId = "u-learner",
                textAnswer = "",
                resultStatus = "submitted",
                createdAt = now.minusSeconds(120),
                clientAttemptId = "attempt-old-$suffix",
                assetRevision = "rev-1",
                language = "typescript",
                attemptStatus = "draft",
                updatedAt = now.minusSeconds(120),
            ),
        )
        submissionRepository.save(
            SubmissionEntity(
                id = latestSubmissionId,
                problemId = problemId,
                userId = "u-learner",
                textAnswer = "",
                resultStatus = "passed",
                createdAt = now.minusSeconds(60),
                clientAttemptId = "attempt-latest-$suffix",
                assetRevision = "rev-1",
                language = "typescript",
                attemptStatus = "submitted",
                updatedAt = now,
                submittedAt = now,
            ),
        )
        submissionFileRepository.save(
            SubmissionFileEntity(
                id = "submission-file-$suffix",
                submissionId = latestSubmissionId,
                path = "src/main.ts",
                content = "export function format(input: string) { return input.toUpperCase() }",
                createdAt = now,
            ),
        )
        sandboxRunResultRepository.save(
            SandboxRunResultEntity(
                id = "sandbox-run-$suffix",
                problemId = problemId,
                userId = "u-learner",
                submissionId = latestSubmissionId,
                status = "passed",
                runnerKind = "local-test",
                durationMs = 42,
                testsJson = """[{"name":"format","status":"passed"}]""",
                stdoutExcerpt = "1 passed",
                createdAt = now,
            ),
        )

        val files = problemFileRepository.findByProblemIdOrderBySortOrderAscPathAsc(problemId)
        assertEquals(listOf("src/main.ts", "src/main.test.ts"), files.map { it.path })
        assertEquals(files.map { it.id }, problemFileRepository.findByProblemIdInOrderByProblemIdAscSortOrderAscPathAsc(listOf(problemId)).map { it.id })

        val hints = problemHintRepository.findByProblemIdInOrderByProblemIdAscRevealOrderAsc(listOf(problemId))
        assertEquals(listOf("Normalize before returning"), hints.map { it.label })

        val provenance = problemProvenanceLinkRepository.findByProblemIdInOrderByProblemIdAscSortOrderAsc(listOf(problemId))
        assertEquals(listOf("Repository diff"), provenance.map { it.sourceLabel })

        val latestSubmission = submissionRepository.findFirstByUserIdAndProblemIdOrderByUpdatedAtDesc("u-learner", problemId)
        assertNotNull(latestSubmission)
        assertEquals(latestSubmissionId, latestSubmission?.id)
        assertEquals(
            latestSubmissionId,
            submissionRepository.findByUserIdAndProblemIdAndClientAttemptId("u-learner", problemId, "attempt-latest-$suffix")?.id,
        )

        val submissionFiles = submissionFileRepository.findBySubmissionIdInOrderBySubmissionIdAscPathAsc(listOf(latestSubmissionId))
        assertEquals(listOf("src/main.ts"), submissionFiles.map { it.path })

        val latestRun = sandboxRunResultRepository.findFirstByUserIdAndProblemIdOrderByCreatedAtDesc("u-learner", problemId)
        assertEquals("passed", latestRun?.status)
        assertEquals(
            listOf(latestRun?.id),
            sandboxRunResultRepository.findBySubmissionIdOrderByCreatedAtDesc(latestSubmissionId).map { it.id },
        )
    }

    companion object {
        @Container
        @JvmField
        val postgres = PracticePostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun configurePostgres(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}

class PracticePostgreSQLContainer(imageName: String) : PostgreSQLContainer<PracticePostgreSQLContainer>(imageName)
