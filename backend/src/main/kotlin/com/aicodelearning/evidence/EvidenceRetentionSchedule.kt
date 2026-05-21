package com.aicodelearning.evidence

import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.organization.MembershipRepository
import com.aicodelearning.organization.MembershipSummary
import com.aicodelearning.organization.UserRepository
import com.aicodelearning.platform.LocalOwnerAccess
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

class EvidenceRetentionSchedulePolicy(
    private val scheduleInterval: Duration,
    private val startupJitterMax: Duration,
    private val recurringJitterMax: Duration,
    private val appStartedAt: Instant,
) {
    fun evaluate(
        settings: EvidenceRetentionSettings,
        health: EvidenceRetentionScheduleHealth,
        now: Instant,
    ): EvidenceRetentionScheduleDecision {
        if (!settings.automaticCleanupEnabled) {
            return EvidenceRetentionScheduleDecision(false, "cleanup_disabled", null)
        }
        if (!health.healthy) {
            return EvidenceRetentionScheduleDecision(false, "degraded_health", null)
        }

        val nextRunAt =
            if (settings.lastCleanupAt == null) {
                appStartedAt.plus(stableJitter(settings.organizationId, startupJitterMax))
            } else {
                settings.lastCleanupAt.plus(scheduleInterval).plus(stableJitter(settings.organizationId, recurringJitterMax))
            }
        return if (now.isBefore(nextRunAt)) {
            EvidenceRetentionScheduleDecision(false, "not_due", nextRunAt)
        } else {
            EvidenceRetentionScheduleDecision(true, "due", nextRunAt)
        }
    }

    private fun stableJitter(
        key: String,
        max: Duration,
    ): Duration {
        val maxMillis = max.toMillis().coerceAtLeast(0)
        if (maxMillis == 0L) return Duration.ZERO
        val hash = abs(key.hashCode().toLong())
        return Duration.ofMillis(hash % (maxMillis + 1))
    }
}

data class EvidenceRetentionScheduleHealth(
    val backendStatus: String = "ok",
    val companionStatus: String = "ok",
) {
    val healthy: Boolean = backendStatus == "ok" && companionStatus == "ok"
}

data class EvidenceRetentionScheduleDecision(
    val shouldRun: Boolean,
    val reason: String,
    val nextRunAt: Instant?,
)

@Component
class EvidenceRetentionScheduleHealthProbe {
    fun current(): EvidenceRetentionScheduleHealth = EvidenceRetentionScheduleHealth()
}

@Component
class EvidenceRetentionScheduledCleanup(
    private val userRepository: UserRepository,
    private val membershipRepository: MembershipRepository,
    private val retentionSettingsService: EvidenceRetentionSettingsService,
    private val retentionCleanupService: EvidenceRetentionCleanupService,
    private val healthProbe: EvidenceRetentionScheduleHealthProbe,
    @param:Value("\${app.retention.cleanup.scheduler-enabled:true}")
    private val schedulerEnabled: Boolean,
    @param:Value("\${app.retention.cleanup.schedule-interval-ms:86400000}")
    private val scheduleIntervalMs: Long,
    @param:Value("\${app.retention.cleanup.startup-jitter-ms:300000}")
    private val startupJitterMs: Long,
    @param:Value("\${app.retention.cleanup.recurring-jitter-ms:300000}")
    private val recurringJitterMs: Long,
    @param:Value("\${app.retention.cleanup.batch-size:100}")
    private val batchSize: Int,
) {
    private val appStartedAt: Instant = Instant.now()
    private val logger = LoggerFactory.getLogger(EvidenceRetentionScheduledCleanup::class.java)

    @Scheduled(
        fixedDelayString = "\${app.retention.cleanup.check-interval-ms:900000}",
        initialDelayString = "\${app.retention.cleanup.tick-initial-delay-ms:300000}",
    )
    fun runDueCleanup() {
        if (!schedulerEnabled) return
        val owner = userRepository.findById(LocalOwnerAccess.LOCAL_OWNER_ID).orElse(null) ?: return
        val memberships = membershipRepository.findByUserId(owner.id)
        val currentUser =
            CurrentUser(
                id = owner.id,
                email = owner.email,
                displayName = owner.displayName,
                memberships =
                    memberships.map {
                        MembershipSummary(
                            organizationId = it.organizationId,
                            teamId = it.teamId,
                            projectId = it.projectId,
                            role = it.role,
                        )
                    },
            )
        val health = healthProbe.current()
        memberships
            .filter { it.role == "admin" }
            .map { it.organizationId }
            .distinct()
            .forEach { organizationId ->
                try {
                    val settings = retentionSettingsService.read(currentUser, organizationId)
                    val decision = policy().evaluate(settings, health, Instant.now())
                    if (decision.shouldRun) {
                        retentionCleanupService.run(
                            currentUser,
                            EvidenceRetentionCleanupRequest(
                                organizationId = organizationId,
                                batchSize = batchSize,
                            ),
                        )
                    }
                } catch (ex: Exception) {
                    logger.warn("Scheduled retention cleanup skipped for organization {}", organizationId, ex)
                }
            }
    }

    private fun policy(): EvidenceRetentionSchedulePolicy =
        EvidenceRetentionSchedulePolicy(
            scheduleInterval = Duration.ofMillis(scheduleIntervalMs.coerceAtLeast(0)),
            startupJitterMax = Duration.ofMillis(startupJitterMs.coerceAtLeast(0)),
            recurringJitterMax = Duration.ofMillis(recurringJitterMs.coerceAtLeast(0)),
            appStartedAt = appStartedAt,
        )
}
