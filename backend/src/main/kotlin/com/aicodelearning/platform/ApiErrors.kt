package com.aicodelearning.platform

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

data class ErrorEnvelope(
    val error: ErrorBody,
)

data class ErrorBody(
    val message: String,
    val status: Int,
    val code: String? = null,
    val fields: Map<String, String>? = null,
)

open class ApiException(
    val status: HttpStatus,
    override val message: String,
    val code: String? = null,
) : RuntimeException(message)

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun handleApiException(exception: ApiException): ResponseEntity<ErrorEnvelope> =
        error(exception.status, exception.message, exception.code)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(exception: MethodArgumentNotValidException): ResponseEntity<ErrorEnvelope> {
        val fields =
            exception
                .bindingResult
                .fieldErrors
                .associate { it.field to (it.defaultMessage ?: "Invalid value") }

        val message =
            exception.bindingResult.fieldErrors.firstOrNull()?.safeMessage()
                ?: "Validation failed"

        return error(HttpStatus.UNPROCESSABLE_ENTITY, message, "validation_failed", fields)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableJson(): ResponseEntity<ErrorEnvelope> =
        error(HttpStatus.UNPROCESSABLE_ENTITY, "Malformed JSON request", "malformed_json")

    private fun error(
        status: HttpStatus,
        message: String,
        code: String? = null,
        fields: Map<String, String>? = null,
    ): ResponseEntity<ErrorEnvelope> =
        ResponseEntity
            .status(status)
            .body(ErrorEnvelope(ErrorBody(message = message, status = status.value(), code = code, fields = fields)))

    private fun FieldError.safeMessage(): String = defaultMessage ?: "Invalid ${field}"
}

@RestController
class ApiFallbackController {
    @RequestMapping("/api/**")
    fun notFound(request: HttpServletRequest): ResponseEntity<ErrorEnvelope> =
        ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(
                ErrorEnvelope(
                    ErrorBody(
                        message = "API route not found",
                        status = HttpStatus.NOT_FOUND.value(),
                        code = "not_found",
                    ),
                ),
            )
}

class UnauthorizedException(message: String) : ApiException(HttpStatus.UNAUTHORIZED, message, "unauthorized")

class ForbiddenException(message: String) : ApiException(HttpStatus.FORBIDDEN, message, "forbidden")

class RateLimitedException(message: String) : ApiException(HttpStatus.TOO_MANY_REQUESTS, message, "rate_limited")
