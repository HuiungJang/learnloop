package com.aicodelearning.source

import org.springframework.data.jpa.repository.JpaRepository

interface SourceLinkRepository : JpaRepository<SourceLinkEntity, String> {
    fun findByIdIn(ids: Collection<String>): List<SourceLinkEntity>
}
