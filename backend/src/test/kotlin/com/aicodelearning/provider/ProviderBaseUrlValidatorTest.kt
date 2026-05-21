package com.aicodelearning.provider

import com.aicodelearning.platform.BadRequestException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProviderBaseUrlValidatorTest {
    @Test
    fun `normalizes safe https base URL`() {
        val validator = ProviderBaseUrlValidator(ProviderGenerationProperties())

        assertEquals("https://8.8.8.8/v1", validator.normalizeAndValidate("https://8.8.8.8/v1/"))
    }

    @Test
    fun `rejects credentials query and fragment`() {
        val validator = ProviderBaseUrlValidator(ProviderGenerationProperties())

        assertThrows(BadRequestException::class.java) { validator.normalizeAndValidate("https://user@example.com") }
        assertThrows(BadRequestException::class.java) { validator.normalizeAndValidate("https://8.8.8.8?key=secret") }
        assertThrows(BadRequestException::class.java) { validator.normalizeAndValidate("https://8.8.8.8#token") }
    }

    @Test
    fun `allows loopback http only when explicitly enabled`() {
        assertThrows(BadRequestException::class.java) {
            ProviderBaseUrlValidator(ProviderGenerationProperties()).normalizeAndValidate("http://127.0.0.1:9999")
        }

        val normalized =
            ProviderBaseUrlValidator(ProviderGenerationProperties(allowLoopbackBaseUrl = true))
                .normalizeAndValidate("http://127.0.0.1:9999/")

        assertEquals("http://127.0.0.1:9999", normalized)
    }

    @Test
    fun `rejects private and metadata hosts`() {
        val validator = ProviderBaseUrlValidator(ProviderGenerationProperties(allowLoopbackBaseUrl = true))

        assertThrows(BadRequestException::class.java) { validator.normalizeAndValidate("http://192.168.0.10") }
        assertThrows(BadRequestException::class.java) { validator.normalizeAndValidate("https://169.254.169.254") }
    }

    @Test
    fun `allows docker host bridge only under loopback test flag`() {
        assertThrows(BadRequestException::class.java) {
            ProviderBaseUrlValidator(ProviderGenerationProperties()).normalizeAndValidate("http://host.docker.internal:9999")
        }

        val normalized =
            ProviderBaseUrlValidator(ProviderGenerationProperties(allowLoopbackBaseUrl = true))
                .normalizeAndValidate("http://host.docker.internal:9999/")

        assertEquals("http://host.docker.internal:9999", normalized)
    }
}
