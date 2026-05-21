package com.aicodelearning.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64

class CredentialCryptoServiceTest {
    private val service = CredentialCryptoService(ProviderGenerationProperties(credentialEncryptionKey = testKey()))

    @Test
    fun `round trip opens sealed credential with matching AAD`() {
        val sealed =
            service.seal(
                providerId = "provider-test",
                organizationId = "org-demo",
                provider = ProviderCatalog.OPENAI,
                model = "gpt-test",
                credential = "secret-value-1234",
            )

        val opened =
            service.open(
                providerId = "provider-test",
                organizationId = "org-demo",
                provider = ProviderCatalog.OPENAI,
                model = "gpt-test",
                credential = sealed,
            )

        assertEquals("secret-value-1234", opened)
    }

    @Test
    fun `AAD mismatch fails decryption`() {
        val sealed =
            service.seal(
                providerId = "provider-test",
                organizationId = "org-demo",
                provider = ProviderCatalog.OPENAI,
                model = "gpt-test",
                credential = "secret-value-1234",
            )

        val exception =
            assertThrows(ProviderGenerationException::class.java) {
                service.open(
                    providerId = "provider-test",
                    organizationId = "org-demo",
                    provider = ProviderCatalog.OPENAI,
                    model = "other-model",
                    credential = sealed,
                )
            }

        assertEquals(ProviderFailureCode.PROVIDER_CONFIGURATION_INVALID, exception.failureCode)
    }

    @Test
    fun `same credential seals to different ciphertext`() {
        val first = sealSameSecret()
        val second = sealSameSecret()

        assertNotEquals(first.iv, second.iv)
        assertNotEquals(first.ciphertext, second.ciphertext)
    }

    private fun sealSameSecret(): SealedCredential =
        service.seal(
            providerId = "provider-test",
            organizationId = "org-demo",
            provider = ProviderCatalog.OPENAI,
            model = "gpt-test",
            credential = "secret-value-1234",
        )

    private fun testKey(): String = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
}
