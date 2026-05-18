package com.aicodelearning.runner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunnerHealthServiceTest {
    @Test
    fun `reports missing when docker is unavailable`() {
        val response =
            RunnerHealthService(
                properties = RunnerProperties(),
                environmentProbe =
                    RunnerEnvironmentProbe { _, _ ->
                        RunnerEnvironmentInspection(
                            dockerAvailable = false,
                            dockerReachable = false,
                            imagePresent = false,
                            limitsSupported = false,
                            detail = "Docker command is not installed",
                        )
                    },
                runnerRegistry = RunnerRegistry(),
            ).health()

        assertEquals("missing", response.status)
        assertFalse(response.dockerAvailable)
        assertEquals("Docker command is not installed", response.detail)
    }

    @Test
    fun `reports image missing when docker is reachable without runner image`() {
        val response =
            RunnerHealthService(
                properties = RunnerProperties(),
                environmentProbe =
                    RunnerEnvironmentProbe { _, requiredImages ->
                        assertEquals(
                            listOf(
                                "learnloop-runner-java:latest",
                                "learnloop-runner-kotlin:latest",
                                "learnloop-runner-typescript:latest",
                            ),
                            requiredImages,
                        )
                        RunnerEnvironmentInspection(
                            dockerAvailable = true,
                            dockerReachable = true,
                            imagePresent = false,
                            limitsSupported = true,
                            detail = "Runner image is not available locally",
                        )
                    },
                runnerRegistry = RunnerRegistry(),
            ).health()

        assertEquals("image_missing", response.status)
        assertTrue(response.dockerAvailable)
        assertTrue(response.dockerReachable)
        assertFalse(response.imagePresent)
    }

    @Test
    fun `reports limit unsupported when limits are required but missing`() {
        val response =
            RunnerHealthService(
                properties = RunnerProperties(requireLimits = true),
                environmentProbe =
                    RunnerEnvironmentProbe { _, _ ->
                        RunnerEnvironmentInspection(
                            dockerAvailable = true,
                            dockerReachable = true,
                            imagePresent = true,
                            limitsSupported = false,
                            detail = "Docker resource limits are unsupported",
                        )
                    },
                runnerRegistry = RunnerRegistry(),
            ).health()

        assertEquals("limit_unsupported", response.status)
        assertFalse(response.limitsSupported)
    }

    @Test
    fun `reports ready when runner prerequisites are available`() {
        val response =
            RunnerHealthService(
                properties = RunnerProperties(token = "runner-token"),
                environmentProbe =
                    RunnerEnvironmentProbe { _, _ ->
                        RunnerEnvironmentInspection(
                            dockerAvailable = true,
                            dockerReachable = true,
                            imagePresent = true,
                            limitsSupported = true,
                            detail = "Runner prerequisites are available",
                        )
                    },
                runnerRegistry = RunnerRegistry(),
            ).health()

        assertEquals("ready", response.status)
        assertTrue(response.imagePresent)
        assertTrue(response.limitsSupported)
    }
}
