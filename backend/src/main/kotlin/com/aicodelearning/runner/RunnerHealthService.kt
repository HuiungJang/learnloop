package com.aicodelearning.runner

import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

enum class RunnerHealthState(val value: String) {
    MISSING("missing"),
    UNREACHABLE("unreachable"),
    IMAGE_MISSING("image_missing"),
    LIMIT_UNSUPPORTED("limit_unsupported"),
    READY("ready"),
}

data class RunnerHealthResponse(
    val status: String,
    val dockerAvailable: Boolean,
    val dockerReachable: Boolean,
    val imagePresent: Boolean,
    val limitsSupported: Boolean,
    val detail: String,
    val checkedAt: Instant,
)

data class RunnerEnvironmentInspection(
    val dockerAvailable: Boolean,
    val dockerReachable: Boolean,
    val imagePresent: Boolean,
    val limitsSupported: Boolean,
    val detail: String,
)

fun interface RunnerEnvironmentProbe {
    fun inspect(properties: RunnerProperties): RunnerEnvironmentInspection
}

@Service
class RunnerHealthService(
    private val properties: RunnerProperties,
    private val environmentProbe: RunnerEnvironmentProbe,
) {
    fun health(): RunnerHealthResponse {
        if (!properties.enabled) {
            return response(
                state = RunnerHealthState.MISSING,
                inspection =
                    RunnerEnvironmentInspection(
                        dockerAvailable = false,
                        dockerReachable = false,
                        imagePresent = false,
                        limitsSupported = false,
                        detail = "Runner is disabled",
                    ),
            )
        }

        val inspection = environmentProbe.inspect(properties)
        val state =
            when {
                !inspection.dockerAvailable -> RunnerHealthState.MISSING
                !inspection.dockerReachable -> RunnerHealthState.UNREACHABLE
                !inspection.imagePresent -> RunnerHealthState.IMAGE_MISSING
                properties.requireLimits && !inspection.limitsSupported -> RunnerHealthState.LIMIT_UNSUPPORTED
                else -> RunnerHealthState.READY
            }
        return response(state, inspection)
    }

    private fun response(
        state: RunnerHealthState,
        inspection: RunnerEnvironmentInspection,
    ): RunnerHealthResponse =
        RunnerHealthResponse(
            status = state.value,
            dockerAvailable = inspection.dockerAvailable,
            dockerReachable = inspection.dockerReachable,
            imagePresent = inspection.imagePresent,
            limitsSupported = inspection.limitsSupported,
            detail = inspection.detail,
            checkedAt = Instant.now(),
        )
}

@Component
class ProcessRunnerEnvironmentProbe : RunnerEnvironmentProbe {
    override fun inspect(properties: RunnerProperties): RunnerEnvironmentInspection {
        val version = runDocker(properties, "version", "--format", "{{.Server.Version}}")
        if (version.missing) {
            return RunnerEnvironmentInspection(
                dockerAvailable = false,
                dockerReachable = false,
                imagePresent = false,
                limitsSupported = false,
                detail = "Docker command is not installed",
            )
        }
        if (!version.success) {
            return RunnerEnvironmentInspection(
                dockerAvailable = true,
                dockerReachable = false,
                imagePresent = false,
                limitsSupported = false,
                detail = version.output.ifBlank { "Docker daemon is unreachable" },
            )
        }

        val image = runDocker(properties, "image", "inspect", properties.image)
        if (!image.success) {
            return RunnerEnvironmentInspection(
                dockerAvailable = true,
                dockerReachable = true,
                imagePresent = false,
                limitsSupported = true,
                detail = "Runner image is not available locally",
            )
        }

        return RunnerEnvironmentInspection(
            dockerAvailable = true,
            dockerReachable = true,
            imagePresent = true,
            limitsSupported = true,
            detail = "Runner prerequisites are available",
        )
    }

    private fun runDocker(
        properties: RunnerProperties,
        vararg args: String,
    ): CommandResult {
        val process =
            try {
                ProcessBuilder(listOf(properties.dockerCommand) + args)
                    .redirectErrorStream(true)
                    .start()
            } catch (_: IOException) {
                return CommandResult(exitCode = 127, output = "", missing = true)
            }

        val completed = process.waitFor(properties.commandTimeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!completed) {
            process.destroyForcibly()
            return CommandResult(exitCode = 124, output = "Docker command timed out")
        }

        val output = process.inputStream.bufferedReader().readText().trim()
        return CommandResult(exitCode = process.exitValue(), output = output)
    }
}

private data class CommandResult(
    val exitCode: Int,
    val output: String,
    val missing: Boolean = false,
) {
    val success: Boolean = exitCode == 0
}
