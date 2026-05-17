package com.aicodelearning.auth

import org.springframework.data.jpa.repository.JpaRepository

interface SessionTokenRepository : JpaRepository<SessionTokenEntity, String> {
    fun findByTokenHash(tokenHash: String): SessionTokenEntity?
}
