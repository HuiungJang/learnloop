package com.aicodelearning.auth

import com.aicodelearning.platform.RateLimitedException
import com.aicodelearning.platform.UnauthorizedException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
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
) {
    @PostMapping("/session")
    @ResponseStatus(HttpStatus.CREATED)
    fun createSession(
        @Valid @RequestBody request: LoginRequest,
        servletRequest: HttpServletRequest,
    ): SessionResponse {
        val rateLimitKey = "${servletRequest.remoteAddr}:${request.email.lowercase()}"
        if (!loginRateLimiter.consume(rateLimitKey)) {
            throw RateLimitedException("Rate limit exceeded")
        }

        val session =
            sessionService.createSession(request.email, request.password)
                ?: throw UnauthorizedException("Invalid email or password")

        loginRateLimiter.reset(rateLimitKey)
        return SessionResponse(
            token = session.token,
            user =
                UserResponse(
                    id = session.user.id,
                    email = session.user.email,
                    displayName = session.user.displayName,
                    memberships = session.user.memberships,
                ),
            expiresAt = session.expiresAt,
        )
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
}

data class LoginRequest(
    @field:Email
    @field:NotBlank
    val email: String = "",

    @field:NotBlank
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
