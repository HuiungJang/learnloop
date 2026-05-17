package com.aicodelearning.auth

import com.aicodelearning.organization.MembershipSummary
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.security.Principal

data class CurrentUser(
    val id: String,
    val email: String,
    val displayName: String,
    val memberships: List<MembershipSummary>,
) : Principal {
    override fun getName(): String = id

    fun authorities(): List<GrantedAuthority> =
        memberships
            .map { SimpleGrantedAuthority("ROLE_${it.role.uppercase()}") }
            .distinctBy { it.authority }
}
