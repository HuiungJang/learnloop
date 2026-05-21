package com.aicodelearning.evidence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class EvidenceRetentionSchedulePolicyTest {
    private val baseTime: Instant = Instant.parse("2026-05-20T00:00:00Z")

    @Test
    fun `schedule is due when startup jitter has elapsed`() {
        val policy =
            EvidenceRetentionSchedulePolicy(
                scheduleInterval = Duration.ofDays(1),
                startupJitterMax = Duration.ZERO,
                recurringJitterMax = Duration.ZERO,
                appStartedAt = baseTime,
            )

        val decision = policy.evaluate(settings(), EvidenceRetentionScheduleHealth(), baseTime)

        assertTrue(decision.shouldRun)
        assertEquals("due", decision.reason)
        assertEquals(baseTime, decision.nextRunAt)
    }

    @Test
    fun `schedule waits until the next due time`() {
        val policy =
            EvidenceRetentionSchedulePolicy(
                scheduleInterval = Duration.ofDays(1),
                startupJitterMax = Duration.ZERO,
                recurringJitterMax = Duration.ZERO,
                appStartedAt = baseTime,
            )

        val decision = policy.evaluate(settings(lastCleanupAt = baseTime), EvidenceRetentionScheduleHealth(), baseTime.plus(Duration.ofHours(23)))

        assertFalse(decision.shouldRun)
        assertEquals("not_due", decision.reason)
        assertEquals(baseTime.plus(Duration.ofDays(1)), decision.nextRunAt)
    }

    @Test
    fun `startup jitter prevents every app start from running cleanup immediately`() {
        val policy =
            EvidenceRetentionSchedulePolicy(
                scheduleInterval = Duration.ofDays(1),
                startupJitterMax = Duration.ofMinutes(5),
                recurringJitterMax = Duration.ZERO,
                appStartedAt = baseTime,
            )
        val jitteredSettings =
            (1..100)
                .map { settings(organizationId = "org-jitter-$it") }
                .first { !policy.evaluate(it, EvidenceRetentionScheduleHealth(), baseTime).shouldRun }

        val decision = policy.evaluate(jitteredSettings, EvidenceRetentionScheduleHealth(), baseTime)

        assertFalse(decision.shouldRun)
        assertEquals("not_due", decision.reason)
        assertNotNull(decision.nextRunAt)
        assertTrue(decision.nextRunAt!!.isAfter(baseTime))
    }

    @Test
    fun `degraded backend or companion health skips scheduled cleanup`() {
        val policy =
            EvidenceRetentionSchedulePolicy(
                scheduleInterval = Duration.ofDays(1),
                startupJitterMax = Duration.ZERO,
                recurringJitterMax = Duration.ZERO,
                appStartedAt = baseTime,
            )

        val decision = policy.evaluate(settings(), EvidenceRetentionScheduleHealth(backendStatus = "ok", companionStatus = "degraded"), baseTime)

        assertFalse(decision.shouldRun)
        assertEquals("degraded_health", decision.reason)
    }

    @Test
    fun `disabled cleanup skips scheduled cleanup`() {
        val policy =
            EvidenceRetentionSchedulePolicy(
                scheduleInterval = Duration.ofDays(1),
                startupJitterMax = Duration.ZERO,
                recurringJitterMax = Duration.ZERO,
                appStartedAt = baseTime,
            )

        val decision = policy.evaluate(settings(retentionMode = "disabled"), EvidenceRetentionScheduleHealth(), baseTime)

        assertFalse(decision.shouldRun)
        assertEquals("cleanup_disabled", decision.reason)
    }

    private fun settings(
        organizationId: String = "org-demo",
        retentionMode: String = "default",
        lastCleanupAt: Instant? = null,
    ): EvidenceRetentionSettings =
        EvidenceRetentionSettings(
            organizationId = organizationId,
            ownerUserId = "u-local-owner",
            retentionMode = retentionMode,
            retentionDays = if (retentionMode == "default") 30 else null,
            automaticCleanupEnabled = retentionMode != "disabled",
            immediatePurge = retentionMode == "immediate",
            updatedAt = null,
            lastCleanupAt = lastCleanupAt,
            lastCleanupPurgedItems = 0,
            lastCleanupReclaimedBytes = 0,
            lastCleanupRemainingItems = 0,
        )
}
