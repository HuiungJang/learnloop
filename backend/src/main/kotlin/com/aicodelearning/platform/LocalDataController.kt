package com.aicodelearning.platform

import com.aicodelearning.auth.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class LocalDataController(
    private val localDataService: LocalDataService,
) {
    @PostMapping("/api/local-data/delete-all")
    fun deleteAll(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: LocalDataDeleteRequest,
    ): LocalDataDeleteResponse = localDataService.deleteAll(currentUser, request.confirmation)
}

data class LocalDataDeleteRequest(
    @field:NotBlank
    val confirmation: String = "",
)
