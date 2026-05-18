package com.aicodelearning.platform

import com.aicodelearning.learning.PatternCardEntity
import com.aicodelearning.learning.PatternCardRepository
import com.aicodelearning.learning.PatternTagEntity
import com.aicodelearning.learning.PatternTagLinkEntity
import com.aicodelearning.learning.PatternTagLinkId
import com.aicodelearning.learning.PatternTagLinkRepository
import com.aicodelearning.learning.PatternTagRepository
import com.aicodelearning.learning.ProblemEntity
import com.aicodelearning.learning.ProblemFileEntity
import com.aicodelearning.learning.ProblemFileRepository
import com.aicodelearning.learning.ProblemHintEntity
import com.aicodelearning.learning.ProblemHintRepository
import com.aicodelearning.learning.ProblemProvenanceLinkEntity
import com.aicodelearning.learning.ProblemProvenanceLinkRepository
import com.aicodelearning.learning.ProblemRepository
import com.aicodelearning.organization.MembershipEntity
import com.aicodelearning.organization.MembershipRepository
import com.aicodelearning.organization.OrganizationEntity
import com.aicodelearning.organization.OrganizationRepository
import com.aicodelearning.organization.ProjectEntity
import com.aicodelearning.organization.ProjectRepository
import com.aicodelearning.organization.TeamEntity
import com.aicodelearning.organization.TeamRepository
import com.aicodelearning.organization.UserEntity
import com.aicodelearning.organization.UserRepository
import com.aicodelearning.provider.ProviderEntity
import com.aicodelearning.provider.ProviderRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
@Profile("local", "install")
class DemoDataInitializer(
    private val organizationRepository: OrganizationRepository,
    private val teamRepository: TeamRepository,
    private val projectRepository: ProjectRepository,
    private val userRepository: UserRepository,
    private val membershipRepository: MembershipRepository,
    private val providerRepository: ProviderRepository,
    private val patternCardRepository: PatternCardRepository,
    private val patternTagRepository: PatternTagRepository,
    private val patternTagLinkRepository: PatternTagLinkRepository,
    private val problemRepository: ProblemRepository,
    private val problemFileRepository: ProblemFileRepository,
    private val problemHintRepository: ProblemHintRepository,
    private val problemProvenanceLinkRepository: ProblemProvenanceLinkRepository,
    private val passwordEncoder: PasswordEncoder,
    @param:Value("\${app.demo-password:demo-password}")
    private val demoPassword: String,
) : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments) {
        val now = Instant.now()
        if (!organizationRepository.existsById("org-demo")) {
            organizationRepository.save(OrganizationEntity(id = "org-demo", name = "Demo Organization", slug = "demo", createdAt = now))
        }
        if (!teamRepository.existsById("team-platform")) {
            teamRepository.save(TeamEntity(id = "team-platform", organizationId = "org-demo", name = "Platform", createdAt = now))
        }
        if (!projectRepository.existsById("project-learning")) {
            projectRepository.save(
                ProjectEntity(
                    id = "project-learning",
                    organizationId = "org-demo",
                    teamId = "team-platform",
                    name = "Learning Platform",
                    createdAt = now,
                ),
            )
        }

        seedUser("u-admin", "admin@example.com", "Admin", "admin", now)
        seedUser("u-contributor", "contributor@example.com", "Contributor", "contributor", now)
        seedUser("u-reviewer", "reviewer@example.com", "Reviewer", "reviewer", now)
        seedUser("u-learner", "learner@example.com", "Learner", "learner", now)
        seedLocalMockProvider(now)
        seedDemoPractice(now)
    }

    private fun seedUser(
        id: String,
        email: String,
        displayName: String,
        role: String,
        now: Instant,
    ) {
        if (!userRepository.existsById(id)) {
            userRepository.save(
                UserEntity(
                    id = id,
                    email = email,
                    displayName = displayName,
                    passwordHash = passwordEncoder.encode(demoPassword),
                    createdAt = now,
                ),
            )
        }

        val membershipId = "membership_$id"
        if (!membershipRepository.existsById(membershipId)) {
            membershipRepository.save(
                MembershipEntity(
                    id = membershipId,
                    userId = id,
                    organizationId = "org-demo",
                    teamId = if (role == "admin") null else "team-platform",
                    projectId = if (role == "admin") null else "project-learning",
                    role = role,
                    createdAt = now,
                ),
            )
        }
    }

    private fun seedLocalMockProvider(now: Instant) {
        if (!providerRepository.existsById("provider-local-mock")) {
            providerRepository.save(
                ProviderEntity(
                    id = "provider-local-mock",
                    organizationId = "org-demo",
                    ownerUserId = null,
                    createdByUserId = "u-admin",
                    provider = "local",
                    model = "mock-pattern-generator",
                    scope = "organization",
                    authType = "local",
                    retentionMode = "none",
                    credentialRef = "local://mock",
                    credentialFingerprint = "local-mock",
                    secretPreview = null,
                    status = "active",
                    orgApproved = true,
                    createdAt = now,
                ),
            )
        }
    }

    private fun seedDemoPractice(now: Instant) {
        if (!patternCardRepository.existsById(DEMO_CARD_ID)) {
            patternCardRepository.save(
                PatternCardEntity(
                    id = DEMO_CARD_ID,
                    organizationId = "org-demo",
                    teamId = "team-platform",
                    projectId = "project-learning",
                    createdByUserId = "u-contributor",
                    title = "Normalize AI-generated tag labels",
                    summary = "Practice turning inconsistent AI-generated labels into stable display text.",
                    visibility = "organization",
                    publicationStatus = "published",
                    createdAt = now,
                    publishedAt = now,
                ),
            )
        }

        val languageTag =
            patternTagRepository.findByNormalizedName("language:typescript")
                ?: patternTagRepository.save(
                    PatternTagEntity(
                        id = "tag-demo-typescript",
                        tagType = "language",
                        name = "TypeScript",
                        normalizedName = "language:typescript",
                    ),
                )
        val patternTag =
            patternTagRepository.findByNormalizedName("pattern:pure-function")
                ?: patternTagRepository.save(
                    PatternTagEntity(
                        id = "tag-demo-pure-function",
                        tagType = "pattern",
                        name = "Pure Function",
                        normalizedName = "pattern:pure-function",
                    ),
                )
        listOf(languageTag, patternTag).forEach { tag ->
            val linkId = PatternTagLinkId(patternCardId = DEMO_CARD_ID, tagId = tag.id)
            if (!patternTagLinkRepository.existsById(linkId)) {
                patternTagLinkRepository.save(PatternTagLinkEntity(patternCardId = DEMO_CARD_ID, tagId = tag.id))
            }
        }

        if (!problemRepository.existsById(DEMO_PROBLEM_ID)) {
            problemRepository.save(
                ProblemEntity(
                    id = DEMO_PROBLEM_ID,
                    patternCardId = DEMO_CARD_ID,
                    problemType = "implementation",
                    prompt = "Implement formatTag so generated labels become stable, readable tags.",
                    referenceAnswer = "Trim input, split separators, lowercase each word, then join words with a single hyphen.",
                    difficulty = "easy",
                    createdAt = now,
                ),
            )
        }

        seedProblemFile(
            ProblemFileEntity(
                id = "problem-file-demo-format-tag",
                problemId = DEMO_PROBLEM_ID,
                path = "src/formatTag.ts",
                language = "typescript",
                fileRole = "starter",
                content =
                    """
                    export function formatTag(input: string): string {
                      // TODO: normalize generated labels into predictable tag ids.
                      return input
                    }
                    """.trimIndent(),
                readOnly = false,
                sortOrder = 1,
                createdAt = now,
            ),
        )
        seedProblemFile(
            ProblemFileEntity(
                id = "problem-file-demo-format-tag-test",
                problemId = DEMO_PROBLEM_ID,
                path = "src/formatTag.test.ts",
                language = "typescript",
                fileRole = "test",
                content =
                    """
                    import { describe, expect, it } from "vitest"
                    import { formatTag } from "./formatTag"

                    describe("formatTag", () => {
                      it("normalizes generated labels", () => {
                        expect(formatTag("  React Query  ")).toBe("react-query")
                        expect(formatTag("SPRING__BOOT")).toBe("spring-boot")
                      })
                    })
                    """.trimIndent(),
                readOnly = true,
                sortOrder = 2,
                createdAt = now,
            ),
        )
        seedProblemFile(
            ProblemFileEntity(
                id = "problem-file-demo-format-tag-solution",
                problemId = DEMO_PROBLEM_ID,
                path = "solution/formatTag.ts",
                language = "typescript",
                fileRole = "solution",
                content =
                    """
                    export function formatTag(input: string): string {
                      return input
                        .trim()
                        .split(/[\s_-]+/)
                        .filter(Boolean)
                        .map((part) => part.toLowerCase())
                        .join("-")
                    }
                    """.trimIndent(),
                readOnly = true,
                sortOrder = 90,
                createdAt = now,
            ),
        )
        seedProblemFile(
            ProblemFileEntity(
                id = "problem-file-demo-format-tag-hidden-test",
                problemId = DEMO_PROBLEM_ID,
                path = "hidden/formatTag.hidden.test.ts",
                language = "typescript",
                fileRole = "hidden_test",
                content = """expect(formatTag("Gemini---OAuth")).toBe("gemini-oauth")""",
                readOnly = true,
                sortOrder = 100,
                createdAt = now,
            ),
        )

        seedProblemHint(
            ProblemHintEntity(
                id = "problem-hint-demo-format-tag-1",
                problemId = DEMO_PROBLEM_ID,
                revealOrder = 1,
                label = "Start with boundaries",
                content = "Normalize whitespace at the beginning and end before handling inner separators.",
                revealPolicy = "manual",
                createdAt = now,
            ),
        )
        seedProblemHint(
            ProblemHintEntity(
                id = "problem-hint-demo-format-tag-2",
                problemId = DEMO_PROBLEM_ID,
                revealOrder = 2,
                label = "Treat separators consistently",
                content = "Spaces, underscores, and repeated hyphens should collapse into one separator.",
                revealPolicy = "after_run",
                createdAt = now,
            ),
        )

        if (!problemProvenanceLinkRepository.existsById("problem-provenance-demo-format-tag")) {
            problemProvenanceLinkRepository.save(
                ProblemProvenanceLinkEntity(
                    id = "problem-provenance-demo-format-tag",
                    problemId = DEMO_PROBLEM_ID,
                    sourceType = "diff",
                    sourceLabel = "Redacted AI-assisted diff",
                    redactedExcerpt = "A generated tagging helper used inconsistent casing and separators. File paths and raw code are omitted.",
                    sortOrder = 1,
                    createdAt = now,
                ),
            )
        }
    }

    private fun seedProblemFile(file: ProblemFileEntity) {
        if (!problemFileRepository.existsById(file.id)) {
            problemFileRepository.save(file)
        }
    }

    private fun seedProblemHint(hint: ProblemHintEntity) {
        if (!problemHintRepository.existsById(hint.id)) {
            problemHintRepository.save(hint)
        }
    }

    private companion object {
        const val DEMO_CARD_ID = "card-demo-practice-workbench"
        const val DEMO_PROBLEM_ID = "problem-demo-practice-workbench"
    }
}
