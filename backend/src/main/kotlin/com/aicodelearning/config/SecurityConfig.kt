package com.aicodelearning.config

import com.aicodelearning.auth.BearerTokenAuthenticationFilter
import com.aicodelearning.platform.ErrorBody
import com.aicodelearning.platform.ErrorEnvelope
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableConfigurationProperties(CorsProperties::class)
class SecurityConfig(
    private val corsProperties: CorsProperties,
    private val objectMapper: ObjectMapper,
) {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        bearerTokenAuthenticationFilter: BearerTokenAuthenticationFilter,
    ): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .cors(Customizer.withDefaults())
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required")
                }
                it.accessDeniedHandler { _, response, _ ->
                    writeError(response, HttpServletResponse.SC_FORBIDDEN, "Not allowed for this organization scope")
                }
            }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/health", "/actuator/health").permitAll()
                it.requestMatchers(HttpMethod.POST, "/api/session").permitAll()
                it.requestMatchers("/v3/api-docs/**").permitAll()
                it.anyRequest().authenticated()
            }
            .addFilterBefore(bearerTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .build()

    @Bean
    fun userDetailsService(): UserDetailsService = InMemoryUserDetailsManager()

    @Bean
    fun bearerTokenAuthenticationFilter(
        sessionService: com.aicodelearning.auth.SessionService,
        objectMapper: ObjectMapper,
    ): BearerTokenAuthenticationFilter = BearerTokenAuthenticationFilter(sessionService, objectMapper)

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration =
            CorsConfiguration().apply {
                allowedOrigins = corsProperties.allowedOrigins
                allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                allowedHeaders = listOf("Authorization", "Content-Type", "Accept")
                exposedHeaders = listOf("Location")
                allowCredentials = false
            }

        return UrlBasedCorsConfigurationSource().also {
            it.registerCorsConfiguration("/api/**", configuration)
        }
    }

    private fun writeError(
        response: HttpServletResponse,
        status: Int,
        message: String,
    ) {
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(response.outputStream, ErrorEnvelope(ErrorBody(message = message, status = status)))
    }
}
