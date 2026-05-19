package com.aicodelearning.evidence

import com.aicodelearning.auth.sha256Hex
import com.aicodelearning.platform.BadRequestException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LocalSessionArtifactPreflightTest {
    private val preflight = LocalSessionArtifactPreflight(SecretScanner())

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `normalizes valid repo-relative unicode paths`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))
        val decomposed = "src/cafe\u0301.ts"

        val result =
            preflight.validate(
                request(artifact("file_before", path = " $decomposed ", content = "export const value = 1")),
                repoRoot,
            )

        assertEquals("scanned_clean", result.status)
        assertTrue(result.generationEligible)
        assertEquals("src/café.ts", result.artifacts.single().repoRelativePath)
    }

    @Test
    fun `rejects artifact paths that are absolute traversal encoded or platform-specific`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))
        listOf(
            "/src/main.ts",
            "~/src/main.ts",
            "C:/src/main.ts",
            "src/../secret.ts",
            "src/./main.ts",
            "src//main.ts",
            "src\\main.ts",
            "src/%2e%2e/secret.ts",
            "src/%252e%252e/secret.ts",
            "src/main.ts\u0000",
        ).forEach { path ->
            assertThrows(BadRequestException::class.java) {
                preflight.validate(request(artifact("file_after", path = path, content = "x")), repoRoot)
            }
        }

        assertThrows(BadRequestException::class.java) {
            preflight.validate(request(artifact("prompt", path = "src/prompt.md", content = "prompt text")), repoRoot)
        }
    }

    @Test
    fun `rejects symlink escapes from the approved repo root`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))
        val outside = Files.createDirectories(tempDir.resolve("outside"))
        Files.createSymbolicLink(repoRoot.resolve("linked"), outside)

        assertThrows(BadRequestException::class.java) {
            preflight.validate(
                request(artifact("file_before", path = "linked/secret.ts", content = "export const secret = true")),
                repoRoot,
            )
        }
    }

    @Test
    fun `ignored artifacts still reject symlink escapes`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))
        val outside = Files.createDirectories(tempDir.resolve("outside"))
        Files.createSymbolicLink(repoRoot.resolve("linked"), outside)

        assertThrows(BadRequestException::class.java) {
            preflight.validate(
                request(artifact("file_before", path = "linked/.env.local", content = "SECRET=value")),
                repoRoot,
            )
        }
    }

    @Test
    fun `ignore rules apply to contained symlink target paths`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))
        Files.createDirectories(repoRoot.resolve("src"))
        Files.createDirectories(repoRoot.resolve("node_modules/pkg"))
        Files.writeString(repoRoot.resolve(".env.local"), "SECRET=value")
        Files.createSymbolicLink(repoRoot.resolve("src/env-link"), repoRoot.resolve(".env.local"))
        Files.createSymbolicLink(repoRoot.resolve("src/deps"), repoRoot.resolve("node_modules"))

        val result =
            preflight.validate(
                request(
                    artifact("file_before", path = "src/env-link", content = "safe"),
                    artifact("file_before", path = "src/deps/pkg/index.js", content = "dependency"),
                ),
                repoRoot,
            )

        assertEquals(0, result.artifacts.size)
        assertEquals(listOf("sensitive_file", "ignored_directory"), result.ignoredArtifacts.map { it.reason })
    }

    @Test
    fun `ignores hidden sensitive binary dependency and build artifacts before storage`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))
        val result =
            preflight.validate(
                request(
                    artifact("file_before", path = ".env.local", content = "SECRET=value"),
                    artifact("file_before", path = "src/.hidden.ts", content = "hidden"),
                    artifact("file_before", path = "src/private.pem", content = "-----BEGIN PRIVATE KEY-----"),
                    artifact("file_before", path = "node_modules/pkg/index.js", content = "dependency"),
                    artifact("file_before", path = "dist/app.js", content = "build"),
                    artifact("file_before", path = "assets/image.png", content = "not text"),
                ),
                repoRoot,
            )

        assertEquals(0, result.artifacts.size)
        assertEquals(
            listOf("sensitive_file", "hidden_path", "sensitive_file", "ignored_directory", "ignored_directory", "binary_or_archive"),
            result.ignoredArtifacts.map { it.reason },
        )
        assertFalse(result.generationEligible)
    }

    @Test
    fun `enforces artifact count file count and session size limits`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))
        val tooManyArtifacts =
            (1..LocalSessionArtifactPreflight.MAX_ARTIFACTS + 1).map {
                artifact("prompt", content = "x")
            }.toTypedArray()
        assertThrows(BadRequestException::class.java) {
            preflight.validate(request(*tooManyArtifacts), repoRoot)
        }

        val tooManyFiles =
            (1..LocalSessionArtifactPreflight.MAX_FILES + 1).map {
                artifact("file_before", path = "src/file$it.ts", content = "x")
            }.toTypedArray()
        assertThrows(BadRequestException::class.java) {
            preflight.validate(request(*tooManyFiles), repoRoot)
        }

        assertThrows(BadRequestException::class.java) {
            preflight.validate(
                request(artifact("file_before", path = "src/huge.ts", content = null, sizeBytes = LocalSessionArtifactPreflight.MAX_SESSION_BYTES + 1L)),
                repoRoot,
            )
        }

        assertThrows(BadRequestException::class.java) {
            preflight.validate(request(artifact("tool_event", content = null, sizeBytes = -1L)), repoRoot)
        }
        assertThrows(BadRequestException::class.java) {
            preflight.validate(request(artifact("tool_event", content = null, sizeBytes = Long.MAX_VALUE)), repoRoot)
        }
        assertThrows(BadRequestException::class.java) {
            preflight.validate(
                request(
                    artifact(
                        "tool_event",
                        content = null,
                        sizeBytes = LocalSessionArtifactPreflight.MAX_SESSION_BYTES.toLong() - 1L,
                        metadata = mapOf("event" to "exit"),
                    ),
                ),
                repoRoot,
            )
        }
    }

    @Test
    fun `oversized artifacts preserve metadata hash truncation and limit reason without raw content`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))
        val content = "a".repeat(LocalSessionArtifactPreflight.MAX_TEXT_ARTIFACT_BYTES + 1)
        val result =
            preflight.validate(
                request(
                    artifact(
                        "file_after",
                        path = "src/large.ts",
                        content = content,
                        metadata = mapOf("language" to "typescript"),
                    ),
                ),
                repoRoot,
            )

        val artifact = result.artifacts.single()
        assertEquals("rejected_by_path_or_size", result.status)
        assertFalse(result.generationEligible)
        assertEquals("src/large.ts", artifact.repoRelativePath)
        assertEquals(sha256Hex(content), artifact.contentHash)
        assertEquals(mapOf("language" to "typescript"), artifact.metadata)
        assertTrue(artifact.contentTruncated)
        assertEquals("artifact_too_large", artifact.limitReason)
        assertNull(artifact.contentText)
    }

    @Test
    fun `diff artifacts use a separate one megabyte limit`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))
        val diffChunk = "d".repeat((LocalSessionArtifactPreflight.MAX_DIFF_BYTES / 2) + 1)
        val result =
            preflight.validate(
                request(
                    artifact("diff", path = "src/one.diff", content = diffChunk),
                    artifact("diff", path = "src/two.diff", content = diffChunk),
                ),
                repoRoot,
            )

        assertEquals("diff_too_large", result.artifacts.last().limitReason)
        assertNull(result.artifacts.last().contentText)
    }

    @Test
    fun `secret findings quarantine artifacts before raw persistence or generation eligibility`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))
        val secret = "sk-testtesttesttesttesttesttesttest"

        val result =
            preflight.validate(
                request(artifact("prompt", content = "Use token $secret")),
                repoRoot,
            )

        val artifact = result.artifacts.single()
        assertEquals("quarantined_secret", result.status)
        assertFalse(result.generationEligible)
        assertNull(artifact.contentText)
        assertEquals("openai_key", artifact.secretFindings.single().type)
        assertEquals("openai_key", result.secretFindings.single().finding.type)
        assertFalse(result.secretFindings.single().finding.fingerprint.contains(secret))
    }

    @Test
    fun `secret findings scan paths and safe metadata before generation eligibility`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))
        val pathSecret = "api_key=secretvalue123"
        val metadataSecret = "sk-testtesttesttesttesttesttesttest"

        val result =
            preflight.validate(
                request(
                    artifact("file_before", path = "src/$pathSecret.ts", content = "export const x = 1"),
                    artifact("tool_event", content = null, metadata = mapOf("event" to "exit", "provider" to metadataSecret)),
                ),
                repoRoot,
            )

        assertEquals("quarantined_secret", result.status)
        assertFalse(result.generationEligible)
        assertEquals(listOf("assigned_secret", "openai_key"), result.secretFindings.map { it.finding.type }.sorted())
        assertNull(result.artifacts.first().repoRelativePath)
        assertNull(result.artifacts.first().contentText)
        assertEquals(mapOf("event" to "exit"), result.artifacts.last().metadata)
    }

    @Test
    fun `prefixed secret variable names quarantine content paths and metadata`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))

        val result =
            preflight.validate(
                request(
                    artifact("prompt", content = "AWS_SECRET_ACCESS_KEY=abcdefghijklmnopqrstuvwxyz"),
                    artifact("file_after", path = "src/ANTHROPIC_API_KEY=abcdefghijklmnop.ts", content = "export const x = 1"),
                    artifact("tool_event", content = null, metadata = mapOf("provider" to "SLACK_BOT_TOKEN=xoxb-abcdefghijklmnop")),
                ),
                repoRoot,
            )

        assertEquals("quarantined_secret", result.status)
        assertFalse(result.generationEligible)
        assertEquals(3, result.secretFindings.count { it.finding.type == "assigned_secret" })
        assertNull(result.artifacts[0].contentText)
        assertNull(result.artifacts[1].repoRelativePath)
        assertNull(result.artifacts[1].contentText)
        assertEquals(emptyMap<String, String>(), result.artifacts[2].metadata)
    }

    @Test
    fun `ignored paths are scanned before returning ignored metadata`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))

        val result =
            preflight.validate(
                request(
                    artifact("file_before", path = "node_modules/api_key=secretvalue123/index.js", content = "ignored"),
                    artifact("prompt", content = "safe prompt"),
                ),
                repoRoot,
            )

        assertEquals("quarantined_secret", result.status)
        assertFalse(result.generationEligible)
        assertEquals("assigned_secret", result.secretFindings.single().finding.type)
        assertNull(result.ignoredArtifacts.single().repoRelativePath)
    }

    @Test
    fun `ignored artifact content and metadata are scanned before generation eligibility`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))

        val result =
            preflight.validate(
                request(
                    artifact("file_before", path = ".env.local", content = "SLACK_BOT_TOKEN=xoxb-abcdefghijklmnop"),
                    artifact("file_before", path = "node_modules/pkg/index.js", content = "ignored", metadata = mapOf("provider" to "ANTHROPIC_API_KEY=abcdefghijklmnop")),
                    artifact("prompt", content = "safe prompt"),
                ),
                repoRoot,
            )

        assertEquals("quarantined_secret", result.status)
        assertFalse(result.generationEligible)
        assertEquals(2, result.secretFindings.count { it.finding.type == "assigned_secret" })
    }

    @Test
    fun `allows one hundred file paths plus prompt response and tool events`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))
        val fileArtifacts =
            (1..LocalSessionArtifactPreflight.MAX_FILES).map {
                artifact("file_after", path = "src/file$it.ts", content = "export const value$it = $it")
            }

        val result =
            preflight.validate(
                request(
                    artifact("prompt", content = "make changes"),
                    artifact("ai_response", content = "done"),
                    artifact("tool_event", content = null, metadata = mapOf("event" to "exit")),
                    *fileArtifacts.toTypedArray(),
                ),
                repoRoot,
            )

        assertEquals("scanned_clean", result.status)
        assertTrue(result.generationEligible)
    }

    @Test
    fun `metadata is allowlisted bounded and does not preserve absolute paths`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))
        val result =
            preflight.validate(
                request(
                    artifact(
                        "tool_event",
                        content = null,
                        metadata =
                            mapOf(
                                "event" to "command_exit",
                                "cwd" to "/Users/example/project",
                                "provider" to "codex-cli,/Users/example/project",
                                "durationMs" to "cwd:/Users/example/project",
                                "limitReason" to "exit;~/secret-repo",
                                "truncated" to "\\\\server\\share",
                                "stdout" to "raw output",
                                "tool" to "codex-cli",
                            ),
                    ),
                ),
                repoRoot,
            )

        assertEquals(mapOf("event" to "command_exit", "tool" to "codex-cli"), result.artifacts.single().metadata)

        assertThrows(BadRequestException::class.java) {
            preflight.validate(
                request(
                    artifact(
                        "tool_event",
                        content = null,
                        metadata = mapOf("event" to "x".repeat(LocalSessionArtifactPreflight.MAX_METADATA_BYTES + 1)),
                    ),
                ),
                repoRoot,
            )
        }
        assertThrows(BadRequestException::class.java) {
            preflight.validate(
                request(
                    artifact(
                        "tool_event",
                        content = null,
                        metadata = mapOf("stdout" to "x".repeat(LocalSessionArtifactPreflight.MAX_METADATA_BYTES + 1)),
                    ),
                ),
                repoRoot,
            )
        }
    }

    @Test
    fun `metadata values are normalized by key before preservation`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))

        val result =
            preflight.validate(
                request(
                    artifact(
                        "tool_event",
                        content = null,
                        metadata =
                            mapOf(
                                "language" to "typescript",
                                "event" to "stdout raw output",
                                "exitCode" to "0",
                                "tool" to "codex-cli",
                                "provider" to "codex cli raw text",
                                "durationMs" to "42",
                                "truncated" to "TRUE",
                            ),
                    ),
                ),
                repoRoot,
            )

        assertEquals(
            mapOf(
                "language" to "typescript",
                "exitCode" to "0",
                "tool" to "codex-cli",
                "durationMs" to "42",
                "truncated" to "true",
            ),
            result.artifacts.single().metadata,
        )
    }

    @Test
    fun `metadata secrets are scanned before result truncation`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))
        val secret = "sk-testtesttesttesttesttesttesttest"
        val result =
            preflight.validate(
                request(
                    artifact(
                        "tool_event",
                        content = null,
                        metadata =
                            mapOf(
                                " provider " to "x".repeat(LocalSessionArtifactPreflight.MAX_METADATA_VALUE_CHARS + 1) + " " + secret,
                                "tool" to "codex-cli",
                            ),
                    ),
                ),
                repoRoot,
            )

        assertEquals("quarantined_secret", result.status)
        assertEquals("openai_key", result.secretFindings.single().finding.type)
        assertEquals(mapOf("tool" to "codex-cli"), result.artifacts.single().metadata)
    }

    @Test
    fun `content hash and limit reason are constrained before safe results`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))

        assertThrows(BadRequestException::class.java) {
            preflight.validate(
                request(artifact("prompt", content = "safe", contentHash = "sk-testtesttesttesttesttesttesttest")),
                repoRoot,
            )
        }

        val reportedHash = "f".repeat(64)
        val missingContentResult =
            preflight.validate(
                request(
                    artifact(
                        "prompt",
                        content = null,
                        contentHash = reportedHash,
                        contentTruncated = true,
                    ),
                ),
                repoRoot,
            )

        assertEquals(sha256Hex("local-session-reported-content-hash:$reportedHash"), missingContentResult.artifacts.single().contentHash)

        val result =
            preflight.validate(
                request(
                    artifact(
                        "prompt",
                        content = "safe",
                        contentTruncated = true,
                        limitReason = "/Users/example/raw-output",
                    ),
                ),
                repoRoot,
            )

        assertEquals("client_truncated", result.artifacts.single().limitReason)
        assertNull(result.artifacts.single().contentText)
    }

    @Test
    fun `content with absolute local paths is not preserved`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))

        val result =
            preflight.validate(
                request(artifact("prompt", content = "Open /Users/example/private-project/src/App.ts")),
                repoRoot,
            )

        val artifact = result.artifacts.single()
        assertEquals("rejected_by_path_or_size", result.status)
        assertFalse(result.generationEligible)
        assertNull(artifact.contentText)
        assertEquals(LocalSessionArtifactPreflight.UNSAFE_CONTENT_PATH_LIMIT_REASON, artifact.limitReason)
    }

    @Test
    fun `tool event content is never preserved as raw text`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))

        val result =
            preflight.validate(
                request(
                    artifact("prompt", content = "safe prompt"),
                    artifact("tool_event", content = "stdout raw output", metadata = mapOf("event" to "stdout")),
                ),
                repoRoot,
            )

        assertTrue(result.generationEligible)
        assertNull(result.artifacts.last().contentText)
        assertEquals(mapOf("event" to "stdout"), result.artifacts.last().metadata)
    }

    @Test
    fun `tool events alone are not generation eligible`() {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))

        val result =
            preflight.validate(
                request(artifact("tool_event", content = null, metadata = mapOf("event" to "start"))),
                repoRoot,
            )

        assertEquals("scanned_clean", result.status)
        assertFalse(result.generationEligible)
    }

    private fun request(vararg artifacts: LocalAiSessionArtifactRequest): LocalAiSessionIngestRequest =
        LocalAiSessionIngestRequest(
            organizationId = "org-demo",
            title = "Codex session",
            repoIdentityHash = "repo-identity",
            repositoryDisplayLabel = "repo",
            toolProvider = "codex-cli",
            idempotencyKey = "repo-identity:event",
            artifacts = artifacts.toList(),
        )

    private fun artifact(
        itemType: String,
        path: String? = null,
        content: String?,
        sizeBytes: Long? = null,
        metadata: Map<String, String> = emptyMap(),
        contentHash: String = "a".repeat(64),
        contentTruncated: Boolean = false,
        limitReason: String? = null,
    ): LocalAiSessionArtifactRequest =
        LocalAiSessionArtifactRequest(
            itemType = itemType,
            repoRelativePath = path,
            sizeBytes = sizeBytes,
            metadata = metadata,
            contentHash = contentHash,
            contentTruncated = contentTruncated,
            limitReason = limitReason,
            content = content,
        )
}
