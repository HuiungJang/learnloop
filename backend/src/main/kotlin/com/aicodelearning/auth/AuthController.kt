package com.aicodelearning.auth

import com.aicodelearning.platform.ForbiddenException
import com.aicodelearning.platform.RateLimitedException
import com.aicodelearning.platform.UnauthorizedException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api")
class AuthController(
    private val sessionService: SessionService,
    private val loginRateLimiter: LoginRateLimiter,
    @param:Value("\${app.registration.enabled:false}")
    private val registrationEnabled: Boolean,
) {
    @PostMapping("/session")
    @ResponseStatus(HttpStatus.CREATED)
    fun createSession(
        @Valid @RequestBody request: LoginRequest,
        servletRequest: HttpServletRequest,
    ): SessionResponse {
        val rateLimitKey = "${servletRequest.remoteAddr}:${request.email.trim().lowercase()}"
        if (!loginRateLimiter.consume(rateLimitKey)) {
            throw RateLimitedException("Rate limit exceeded")
        }

        val session =
            sessionService.createSession(request.email, request.password)
                ?: throw UnauthorizedException("Invalid email or password")

        loginRateLimiter.reset(rateLimitKey)
        return session.toResponse()
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        @Valid @RequestBody request: RegisterRequest,
    ): SessionResponse {
        if (!registrationEnabled) {
            throw ForbiddenException("Registration is disabled in local owner mode")
        }

        val session =
            sessionService.register(
                email = request.email,
                displayName = request.displayName,
                password = request.password,
            )

        return session.toResponse()
    }

    @GetMapping("/me")
    fun me(
        @AuthenticationPrincipal currentUser: CurrentUser,
    ): UserResponse =
        UserResponse(
            id = currentUser.id,
            email = currentUser.email,
            displayName = currentUser.displayName,
            memberships = currentUser.memberships,
        )

    private fun CreatedSession.toResponse(): SessionResponse =
        SessionResponse(
            token = token,
            user =
                UserResponse(
                    id = user.id,
                    email = user.email,
                    displayName = user.displayName,
                    memberships = user.memberships,
                ),
            expiresAt = expiresAt,
        )
}

data class LoginRequest(
    @field:Email
    @field:NotBlank
    val email: String = "",

    @field:NotBlank
    val password: String = "",
)

data class RegisterRequest(
    @field:Email
    @field:NotBlank
    val email: String = "",

    @field:NotBlank
    @field:Size(min = 2, max = 80)
    val displayName: String = "",

    @field:NotBlank
    @field:Size(min = 8, max = 128)
    val password: String = "",
)

data class SessionResponse(
    val token: String,
    val user: UserResponse,
    val expiresAt: Instant,
)

data class UserResponse(
    val id: String,
    val email: String,
    val displayName: String,
    val memberships: List<com.aicodelearning.organization.MembershipSummary>,
)
