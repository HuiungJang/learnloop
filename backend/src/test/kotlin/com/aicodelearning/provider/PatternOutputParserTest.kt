package com.aicodelearning.provider

import com.aicodelearning.learning.PatternRecognitionPromptBuilder
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PatternOutputParserTest {
    private val objectMapper = ObjectMapper()
    private val parser = PatternOutputParser(objectMapper)

    @Test
    fun `accepts one valid pattern`() {
        val result = parser.parse(providerPatternOutput(objectMapper, "Parser Accepted Pattern"))

        assertEquals("Parser Accepted Pattern", result.title)
        assertEquals(1, result.tags.size)
        assertEquals(3, result.problems.size)
    }

    @Test
    fun `rejects invalid schema shapes`() {
        val exception =
            assertThrows(ProviderGenerationException::class.java) {
                parser.parse("""{"patterns":[]}""")
            }

        assertEquals(ProviderFailureCode.PROVIDER_INVALID_SCHEMA, exception.failureCode)
    }

    @Test
    fun `rejects prompt marker and secret like output`() {
        val markerException =
            assertThrows(ProviderGenerationException::class.java) {
                parser.parse("""{"patterns":[{"title":"${PatternRecognitionPromptBuilder.INTERNAL_PROMPT_MARKER}"}]}""")
            }
        val secretException =
            assertThrows(ProviderGenerationException::class.java) {
                parser.parse("""{"patterns":[{"title":"x","summary":"api_key = leaked-secret"}]}""")
            }

        assertEquals(ProviderFailureCode.PROVIDER_UNUSABLE_OUTPUT, markerException.failureCode)
        assertEquals(ProviderFailureCode.PROVIDER_UNUSABLE_OUTPUT, secretException.failureCode)
    }
}
