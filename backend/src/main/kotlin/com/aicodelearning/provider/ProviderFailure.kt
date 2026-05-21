package com.aicodelearning.provider

import com.aicodelearning.platform.ApiException
import org.springframework.http.HttpStatus

enum class ProviderFailureCode(val value: String) {
    PROVIDER_CONFIGURATION_INVALID("provider_configuration_invalid"),
    PROVIDER_TIMEOUT("provider_timeout"),
    PROVIDER_NETWORK_ERROR("provider_network_error"),
    PROVIDER_HTTP_ERROR("provider_http_error"),
    PROVIDER_MISSING_OUTPUT("provider_missing_output"),
    PROVIDER_INVALID_JSON("provider_invalid_json"),
    PROVIDER_INVALID_SCHEMA("provider_invalid_schema"),
    PROVIDER_UNUSABLE_OUTPUT("provider_unusable_output"),
    SOURCE_EVIDENCE_UNAVAILABLE("source_evidence_unavailable"),
}

class ProviderGenerationException(
    val failureCode: ProviderFailureCode,
) : RuntimeException("Provider generation failed")

class ProviderGenerationApiException(
    generationRunId: String,
    failureCode: ProviderFailureCode,
) : ApiException(
        status = failureCode.httpStatus(),
        message = "Provider generation failed",
        code = "provider_generation_failed",
        fields =
            mapOf(
                "generationRunId" to generationRunId,
                "failureCode" to failureCode.value,
            ),
    )

private fun ProviderFailureCode.httpStatus(): HttpStatus =
    when (this) {
        ProviderFailureCode.PROVIDER_CONFIGURATION_INVALID,
        ProviderFailureCode.SOURCE_EVIDENCE_UNAVAILABLE,
        -> HttpStatus.UNPROCESSABLE_ENTITY
        else -> HttpStatus.SERVICE_UNAVAILABLE
    }
