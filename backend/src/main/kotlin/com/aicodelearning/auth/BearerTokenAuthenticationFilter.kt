package com.aicodelearning.auth

import com.aicodelearning.platform.ErrorBody
import com.aicodelearning.platform.ErrorEnvelope
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class BearerTokenAuthenticationFilter(
    private val sessionService: SessionService,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authorization = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (authorization.isNullOrBlank()) {
            filterChain.doFilter(request, response)
            return
        }

        if (!authorization.startsWith("Bearer ")) {
            writeUnauthorized(response)
            return
        }

        val token = authorization.removePrefix("Bearer ").trim()
        if (token.isBlank()) {
            writeUnauthorized(response)
            return
        }

        val currentUser = sessionService.authenticate(token)
        if (currentUser == null) {
            writeUnauthorized(response)
            return
        }

        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(currentUser, null, currentUser.authorities())

        filterChain.doFilter(request, response)
    }

    private fun writeUnauthorized(response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(
            response.outputStream,
            ErrorEnvelope(ErrorBody(message = "Invalid or expired session", status = HttpServletResponse.SC_UNAUTHORIZED)),
        )
    }
}
