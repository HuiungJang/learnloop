package com.aicodelearning.runner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class ProcessRunnerImageClientTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `docker output is drained redacted and truncated`() {
        val docker = executable(
            """
            #!/usr/bin/env sh
            printf 'token=super-secret\n'
            head -c 70000 /dev/zero | tr '\0' x
            exit 1
            """.trimIndent(),
        )
        val client = ProcessRunnerImageClient(RunnerProperties(dockerCommand = docker.toString(), commandTimeout = Duration.ofSeconds(5)))

        val result = client.inspect("learnloop-runner-rust:test")

        assertFalse(result.success)
        assertEquals("docker_command_failed", result.errorCode)
        assertFalse(result.detail.contains("super-secret"))
        assertTrue(result.detail.contains("token=[redacted]"))
        assertTrue(result.detail.length <= 4_000)
    }

    @Test
    fun `timeout kills the docker command`() {
        val docker = executable(
            """
            #!/usr/bin/env sh
            sleep 5
            """.trimIndent(),
        )
        val client = ProcessRunnerImageClient(RunnerProperties(dockerCommand = docker.toString(), commandTimeout = Duration.ofMillis(100)))

        val result = client.inspect("learnloop-runner-rust:test")

        assertFalse(result.success)
        assertEquals("timeout", result.errorCode)
    }

    private fun executable(content: String): Path {
        val path = tempDir.resolve("docker")
        Files.writeString(path, content)
        path.toFile().setExecutable(true)
        return path
    }
}
