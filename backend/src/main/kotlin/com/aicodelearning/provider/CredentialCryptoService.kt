package com.aicodelearning.provider

import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Service
class CredentialCryptoService(
    private val properties: ProviderGenerationProperties,
) {
    private val secureRandom = SecureRandom()

    fun seal(
        providerId: String,
        organizationId: String,
        provider: String,
        model: String,
        credential: String,
    ): SealedCredential {
        val iv = ByteArray(GCM_IV_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(requiredKey(), "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad(providerId, organizationId, provider, model))
        val sealed = cipher.doFinal(credential.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = sealed.copyOfRange(0, sealed.size - GCM_TAG_BYTES)
        val tag = sealed.copyOfRange(sealed.size - GCM_TAG_BYTES, sealed.size)

        return SealedCredential(
            algorithm = ALGORITHM,
            iv = encoder.encodeToString(iv),
            tag = encoder.encodeToString(tag),
            ciphertext = encoder.encodeToString(ciphertext),
        )
    }

    fun open(
        providerId: String,
        organizationId: String,
        provider: String,
        model: String,
        credential: SealedCredential,
    ): String {
        if (credential.algorithm != ALGORITHM) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_CONFIGURATION_INVALID)
        }
        return try {
            val iv = decoder.decode(credential.iv)
            val tag = decoder.decode(credential.tag)
            val ciphertext = decoder.decode(credential.ciphertext)
            val sealed = ciphertext + tag
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(requiredKey(), "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad(providerId, organizationId, provider, model))
            String(cipher.doFinal(sealed), StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_CONFIGURATION_INVALID)
        } catch (_: Exception) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_CONFIGURATION_INVALID)
        }
    }

    fun isConfigured(): Boolean =
        runCatching { requiredKey() }.isSuccess

    private fun requiredKey(): ByteArray {
        val raw = properties.credentialEncryptionKey.trim()
        if (raw.isBlank()) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_CONFIGURATION_INVALID)
        }

        val decoded =
            decodeBase64(raw)
                ?: decodeHex(raw)
                ?: raw.toByteArray(StandardCharsets.UTF_8)
        if (decoded.size != AES_256_KEY_BYTES) {
            throw ProviderGenerationException(ProviderFailureCode.PROVIDER_CONFIGURATION_INVALID)
        }
        return decoded
    }

    private fun aad(
        providerId: String,
        organizationId: String,
        provider: String,
        model: String,
    ): ByteArray =
        "$providerId|$organizationId|$provider|$model".toByteArray(StandardCharsets.UTF_8)

    private fun decodeBase64(value: String): ByteArray? =
        runCatching { decoder.decode(value) }.getOrNull()

    private fun decodeHex(value: String): ByteArray? {
        if (value.length != AES_256_KEY_BYTES * 2 || !value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return null
        }
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private companion object {
        const val ALGORITHM = "AES_GCM_V1"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_256_KEY_BYTES = 32
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val GCM_TAG_BITS = 128
        val encoder: Base64.Encoder = Base64.getEncoder()
        val decoder: Base64.Decoder = Base64.getDecoder()
    }
}

data class SealedCredential(
    val algorithm: String,
    val iv: String,
    val tag: String,
    val ciphertext: String,
)
