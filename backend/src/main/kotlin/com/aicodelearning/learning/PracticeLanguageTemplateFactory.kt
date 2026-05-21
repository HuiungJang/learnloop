package com.aicodelearning.learning

import com.aicodelearning.platform.prefixedId
import java.time.Instant

object PracticeLanguageTemplateFactory {
    fun detectPrimaryLanguage(
        paths: Iterable<String>,
        contents: Iterable<String>,
    ): String? {
        val pathCounts =
            paths
                .mapNotNull(::languageForPath)
                .groupingBy { it }
                .eachCount()
        val pathLanguage = singleWinner(pathCounts)
        if (pathLanguage != null) return pathLanguage
        if (pathCounts.isNotEmpty()) return null

        val contentCounts =
            contents
                .mapNotNull(::languageForContent)
                .groupingBy { it }
                .eachCount()
        return singleWinner(contentCounts)
    }

    fun filesFor(
        problemId: String,
        language: String?,
        now: Instant,
    ): List<ProblemFileEntity> =
        when (language) {
            PracticeContract.LANGUAGE_SWIFT -> swiftFiles(problemId, now)
            PracticeContract.LANGUAGE_RUST -> rustFiles(problemId, now)
            else -> emptyList()
        }

    fun displayName(language: String?): String? =
        when (language) {
            PracticeContract.LANGUAGE_SWIFT -> "Swift"
            PracticeContract.LANGUAGE_RUST -> "Rust"
            PracticeContract.LANGUAGE_TYPESCRIPT -> "TypeScript"
            PracticeContract.LANGUAGE_KOTLIN -> "Kotlin"
            PracticeContract.LANGUAGE_JAVA -> "Java"
            else -> null
        }

    private fun languageForPath(path: String): String? {
        val normalized = path.trim().lowercase()
        return when {
            normalized.endsWith(".swift") || normalized.endsWith("package.swift") -> PracticeContract.LANGUAGE_SWIFT
            normalized.endsWith(".rs") || normalized.endsWith("cargo.toml") -> PracticeContract.LANGUAGE_RUST
            normalized.endsWith(".ts") || normalized.endsWith(".tsx") -> PracticeContract.LANGUAGE_TYPESCRIPT
            normalized.endsWith(".kt") || normalized.endsWith(".kts") -> PracticeContract.LANGUAGE_KOTLIN
            normalized.endsWith(".java") -> PracticeContract.LANGUAGE_JAVA
            else -> null
        }
    }

    private fun languageForContent(content: String): String? {
        val normalized = content.lowercase()
        return when {
            "import packagedescription" in normalized || "xctest" in normalized || "#expect(" in normalized -> PracticeContract.LANGUAGE_SWIFT
            "[package]" in normalized || "#[test]" in normalized || "cargo test" in normalized -> PracticeContract.LANGUAGE_RUST
            else -> null
        }
    }

    private fun singleWinner(counts: Map<String, Int>): String? {
        val sorted = counts.entries.sortedByDescending { it.value }
        val first = sorted.firstOrNull() ?: return null
        val second = sorted.getOrNull(1)
        return if (second == null || first.value > second.value) first.key else null
    }

    private fun swiftFiles(
        problemId: String,
        now: Instant,
    ): List<ProblemFileEntity> =
        listOf(
            problemFile(
                problemId = problemId,
                path = "Package.swift",
                language = PracticeContract.LANGUAGE_SWIFT,
                role = PracticeContract.FILE_ROLE_SUPPORT,
                readOnly = true,
                sortOrder = 0,
                content =
                    """
                    // swift-tools-version: 6.0
                    import PackageDescription

                    let package = Package(
                        name: "LearnLoopPractice",
                        products: [
                            .library(name: "LearnLoopPractice", targets: ["LearnLoopPractice"])
                        ],
                        targets: [
                            .target(name: "LearnLoopPractice"),
                            .testTarget(name: "LearnLoopPracticeTests", dependencies: ["LearnLoopPractice"])
                        ]
                    )
                    """.trimIndent(),
                now = now,
            ),
            problemFile(
                problemId = problemId,
                path = "Sources/LearnLoopPractice/Solution.swift",
                language = PracticeContract.LANGUAGE_SWIFT,
                role = PracticeContract.FILE_ROLE_STARTER,
                readOnly = false,
                sortOrder = 1,
                content =
                    """
                    public func normalizeOrderId(_ raw: String) -> String {
                        raw.trimmingCharacters(in: .whitespacesAndNewlines)
                    }
                    """.trimIndent(),
                now = now,
            ),
            problemFile(
                problemId = problemId,
                path = "Tests/LearnLoopPracticeTests/SolutionTests.swift",
                language = PracticeContract.LANGUAGE_SWIFT,
                role = PracticeContract.FILE_ROLE_TEST,
                readOnly = true,
                sortOrder = 2,
                content =
                    """
                    import Testing
                    @testable import LearnLoopPractice

                    @Test func normalizesOrderId() {
                        #expect(normalizeOrderId("  order-42\n") == "order-42")
                    }
                    """.trimIndent(),
                now = now,
            ),
        )

    private fun rustFiles(
        problemId: String,
        now: Instant,
    ): List<ProblemFileEntity> =
        listOf(
            problemFile(
                problemId = problemId,
                path = "Cargo.toml",
                language = PracticeContract.LANGUAGE_RUST,
                role = PracticeContract.FILE_ROLE_SUPPORT,
                readOnly = true,
                sortOrder = 0,
                content =
                    """
                    [package]
                    name = "learnloop_practice"
                    version = "0.1.0"
                    edition = "2021"
                    publish = false

                    [lib]
                    path = "src/lib.rs"
                    """.trimIndent(),
                now = now,
            ),
            problemFile(
                problemId = problemId,
                path = "src/lib.rs",
                language = PracticeContract.LANGUAGE_RUST,
                role = PracticeContract.FILE_ROLE_STARTER,
                readOnly = false,
                sortOrder = 1,
                content =
                    """
                    pub fn normalize_order_id(raw: &str) -> String {
                        raw.trim().to_owned()
                    }
                    """.trimIndent(),
                now = now,
            ),
            problemFile(
                problemId = problemId,
                path = "tests/solution_test.rs",
                language = PracticeContract.LANGUAGE_RUST,
                role = PracticeContract.FILE_ROLE_TEST,
                readOnly = true,
                sortOrder = 2,
                content =
                    """
                    use learnloop_practice::normalize_order_id;

                    #[test]
                    fn normalizes_order_id() {
                        assert_eq!(normalize_order_id("  order-42\n"), "order-42");
                    }
                    """.trimIndent(),
                now = now,
            ),
        )

    private fun problemFile(
        problemId: String,
        path: String,
        language: String,
        role: String,
        readOnly: Boolean,
        sortOrder: Int,
        content: String,
        now: Instant,
    ): ProblemFileEntity =
        ProblemFileEntity(
            id = prefixedId("problem_file"),
            problemId = problemId,
            path = path,
            language = language,
            fileRole = role,
            content = content,
            readOnly = readOnly,
            sortOrder = sortOrder,
            createdAt = now,
        )
}
