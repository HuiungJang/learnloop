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

    @Test
    fun `build uses docker build with image tag and context args`() {
        val argsFile = tempDir.resolve("args.txt")
        val context = tempDir.resolve("runner").also { Files.createDirectories(it) }
        Files.writeString(context.resolve("Dockerfile"), "FROM scratch\n")
        val docker = executable(
            """
            #!/usr/bin/env sh
            printf '%s\n' "${'$'}*" > "${argsFile.toAbsolutePath()}"
            """.trimIndent(),
        )
        val client = ProcessRunnerImageClient(RunnerProperties(dockerCommand = docker.toString(), commandTimeout = Duration.ofSeconds(5)))

        val result = client.build("learnloop-runner-rust:latest", context.toString())

        assertTrue(result.success)
        assertEquals("build -t learnloop-runner-rust:latest ${context}", Files.readString(argsFile).trim())
    }

    @Test
    fun `build fails before docker when context is missing`() {
        val docker = executable(
            """
            #!/usr/bin/env sh
            exit 0
            """.trimIndent(),
        )
        val client = ProcessRunnerImageClient(RunnerProperties(dockerCommand = docker.toString(), commandTimeout = Duration.ofSeconds(5)))

        val result = client.build("learnloop-runner-rust:latest", tempDir.resolve("missing").toString())

        assertFalse(result.success)
        assertEquals(RunnerImageErrorCodes.LOCAL_BUILD_CONTEXT_MISSING, result.errorCode)
    }

    private fun executable(content: String): Path {
        val path = tempDir.resolve("docker")
        Files.writeString(path, content)
        path.toFile().setExecutable(true)
        return path
    }
}
