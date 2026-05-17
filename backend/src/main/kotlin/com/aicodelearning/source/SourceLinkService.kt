package com.aicodelearning.source

import com.aicodelearning.audit.AuditService
import com.aicodelearning.auth.CurrentUser
import com.aicodelearning.evidence.EvidenceItemRepository
import com.aicodelearning.evidence.SourceBundleEntity
import com.aicodelearning.evidence.SourceBundleRepository
import com.aicodelearning.organization.AuthorizationService
import com.aicodelearning.platform.BadRequestException
import com.aicodelearning.platform.NotFoundException
import com.aicodelearning.platform.prefixedId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

@Service
class SourceLinkService(
    private val sourceBundleRepository: SourceBundleRepository,
    private val evidenceItemRepository: EvidenceItemRepository,
    private val sourceLinkRepository: SourceLinkRepository,
    private val authorizationService: AuthorizationService,
    private val auditService: AuditService,
) {
    @Transactional
    fun suggest(
        currentUser: CurrentUser,
        conversationBundleId: String,
        codeBundleId: String,
    ): SourceLinkEntity {
        val conversation = sourceBundleRepository.findById(conversationBundleId).orElseThrow { NotFoundException("Conversation bundle not found") }
        val code = sourceBundleRepository.findById(codeBundleId).orElseThrow { NotFoundException("Code bundle not found") }
        if (conversation.organizationId != code.organizationId) {
            throw BadRequestException("Source bundles must belong to the same organization")
        }
        authorizationService.requireRole(currentUser, conversation.organizationId, "contributor", conversation.teamId, conversation.projectId)

        val confidence = similarity(bundleText(conversation), bundleText(code))
        val link =
            sourceLinkRepository.save(
                SourceLinkEntity(
                    id = prefixedId("link"),
                    organizationId = conversation.organizationId,
                    conversationBundleId = conversation.id,
                    codeBundleId = code.id,
                    status = "suggested",
                    confidence = confidence,
                    createdByUserId = currentUser.id,
                    createdAt = Instant.now(),
                ),
            )
        auditService.append(currentUser, link.organizationId, "source_link.suggested", "source_link", link.id)
        return link
    }

    @Transactional
    fun decide(
        currentUser: CurrentUser,
        linkId: String,
        status: String,
    ): SourceLinkEntity {
        val link = sourceLinkRepository.findById(linkId).orElseThrow { NotFoundException("Source link not found") }
        authorizationService.requireRole(currentUser, link.organizationId, "contributor")
        link.status = status
        link.decidedByUserId = currentUser.id
        link.decidedAt = Instant.now()
        auditService.append(currentUser, link.organizationId, "source_link.$status", "source_link", link.id)
        return link
    }

    private fun bundleText(bundle: SourceBundleEntity): String =
        evidenceItemRepository
            .findByBundleId(bundle.id)
            .mapNotNull { it.contentText }
            .joinToString("\n")

    private fun similarity(
        left: String,
        right: String,
    ): BigDecimal {
        val leftTokens = tokens(left)
        val rightTokens = tokens(right)
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return BigDecimal.ZERO
        }
        val overlap = leftTokens.count { it in rightTokens }
        return BigDecimal(overlap)
            .divide(BigDecimal(maxOf(leftTokens.size, rightTokens.size)), 5, RoundingMode.HALF_UP)
    }

    private fun tokens(value: String): Set<String> =
        Regex("[a-zA-Z0-9_.$/-]{3,}")
            .findAll(value.lowercase())
            .map { it.value }
            .toSet()
}
