package com.aicodelearning.provider

import org.springframework.data.jpa.repository.JpaRepository

interface ProviderRepository : JpaRepository<ProviderEntity, String> {
    fun findByOrganizationIdAndScopeIn(
        organizationId: String,
        scopes: Collection<String>,
    ): List<ProviderEntity>

    fun findByOrganizationIdAndOwnerUserId(
        organizationId: String,
        ownerUserId: String,
    ): List<ProviderEntity>
}
