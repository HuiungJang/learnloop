package com.aicodelearning.platform

import com.aicodelearning.auth.CurrentUser
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class LocalOwnerAccess(
    @param:Value("\${app.local-owner.email:owner@local.learnloop}")
    private val localOwnerEmail: String,
) {
    fun requireLocalOwner(currentUser: CurrentUser) {
        if (currentUser.id != LOCAL_OWNER_ID || !currentUser.email.equals(localOwnerEmail.trim(), ignoreCase = true)) {
            throw ForbiddenException("Only the local owner can perform this action")
        }
    }

    companion object {
        const val LOCAL_OWNER_ID = "u-local-owner"
    }
}
