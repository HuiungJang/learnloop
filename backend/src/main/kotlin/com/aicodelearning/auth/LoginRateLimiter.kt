package com.aicodelearning.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class LoginRateLimiter(
    @param:Value("\${app.login-rate-limit.max-attempts:5}")
    private val maxAttempts: Int,
    @param:Value("\${app.login-rate-limit.window:PT1M}")
    private val window: Duration,
) {
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun consume(key: String): Boolean {
        val now = Instant.now()
        val bucket =
            buckets.compute(key) { _, current ->
                if (current == null || current.resetAt.isBefore(now)) {
                    Bucket(count = 1, resetAt = now.plus(window))
                } else {
                    current.copy(count = current.count + 1)
                }
            }

        return bucket != null && bucket.count <= maxAttempts
    }

    fun reset(key: String) {
        buckets.remove(key)
    }

    private data class Bucket(
        val count: Int,
        val resetAt: Instant,
    )
}
