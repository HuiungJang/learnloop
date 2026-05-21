package com.aicodelearning.learning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class PracticeLanguageTemplateFactoryTest {
    @Test
    fun `detects Swift and Rust from clear source paths`() {
        assertEquals(
            PracticeContract.LANGUAGE_SWIFT,
            PracticeLanguageTemplateFactory.detectPrimaryLanguage(
                paths = listOf("Package.swift", "Sources/LearnLoopPractice/Solution.swift"),
                contents = emptyList(),
            ),
        )
        assertEquals(
            PracticeContract.LANGUAGE_RUST,
            PracticeLanguageTemplateFactory.detectPrimaryLanguage(
                paths = listOf("Cargo.toml", "src/lib.rs", "tests/solution_test.rs"),
                contents = emptyList(),
            ),
        )
    }

    @Test
    fun `does not choose a language for ambiguous evidence`() {
        assertNull(
            PracticeLanguageTemplateFactory.detectPrimaryLanguage(
                paths = listOf("Sources/LearnLoopPractice/Solution.swift", "src/lib.rs"),
                contents = emptyList(),
            ),
        )
    }

    @Test
    fun `creates Swift Package Manager practice files`() {
        val files = PracticeLanguageTemplateFactory.filesFor("problem-swift", PracticeContract.LANGUAGE_SWIFT, Instant.parse("2026-05-21T00:00:00Z"))

        assertEquals(listOf("Package.swift", "Sources/LearnLoopPractice/Solution.swift", "Tests/LearnLoopPracticeTests/SolutionTests.swift"), files.map { it.path })
        assertEquals(listOf("support", "starter", "test"), files.map { it.fileRole })
        assertTrue(files.single { it.fileRole == PracticeContract.FILE_ROLE_STARTER }.content.contains("normalizeOrderId"))
    }

    @Test
    fun `creates Cargo practice files`() {
        val files = PracticeLanguageTemplateFactory.filesFor("problem-rust", PracticeContract.LANGUAGE_RUST, Instant.parse("2026-05-21T00:00:00Z"))

        assertEquals(listOf("Cargo.toml", "src/lib.rs", "tests/solution_test.rs"), files.map { it.path })
        assertEquals(listOf("support", "starter", "test"), files.map { it.fileRole })
        assertTrue(files.single { it.fileRole == PracticeContract.FILE_ROLE_STARTER }.content.contains("normalize_order_id"))
    }
}
