package com.aicodelearning.runner

import com.aicodelearning.learning.PracticeContract
import org.springframework.stereotype.Service
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

fun interface RunnerExecutor {
    fun run(request: ValidatedRunnerRunRequest): NormalizedRunnerResult
}

@Service
class DockerRunnerExecutor(
    private val properties: RunnerProperties,
    private val workspaceService: RunnerWorkspaceService,
    private val commandBuilder: DockerRunCommandBuilder,
    private val resultNormalizer: DockerRunResultNormalizer,
) : RunnerExecutor {
    override fun run(request: ValidatedRunnerRunRequest): NormalizedRunnerResult {
        if (!properties.enabled) {
            return runnerUnavailable("Runner is disabled")
        }

        return workspaceService.withWorkspace(request) { workspace ->
            val plan = commandBuilder.build(request, workspace)
            execute(plan.command, request.timeoutMs)
        }
    }

    private fun execute(
        command: List<String>,
        timeoutMs: Long,
    ): NormalizedRunnerResult {
        val started = System.nanoTime()
        val process =
            try {
                ProcessBuilder(command).start()
            } catch (exception: IOException) {
                return runnerUnavailable(exception.message ?: "Runner command failed to start")
            }

        val stdout = CompletableFuture.supplyAsync { readOutput(process.inputStream) }
        val stderr = CompletableFuture.supplyAsync { readOutput(process.errorStream) }
        val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
        }

        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        val exitCode = if (completed) process.exitValue() else TIMEOUT_EXIT_CODE

        return resultNormalizer.normalize(
            RawDockerRunResult(
                exitCode = exitCode,
                timedOut = !completed,
                resourceLimited = exitCode == RESOURCE_LIMIT_EXIT_CODE,
                stdout = awaitOutput(stdout),
                stderr = awaitOutput(stderr),
                durationMs = durationMs,
            ),
        )
    }

    private fun runnerUnavailable(reason: String): NormalizedRunnerResult =
        NormalizedRunnerResult(
            status = PracticeContract.RUN_STATUS_RUNNER_UNAVAILABLE,
            stdoutExcerpt = "",
            stderrExcerpt = workspaceService.truncateOutput(reason),
            durationMs = 0,
        )

    private fun readOutput(input: InputStream): String =
        input.use { stream ->
            val reader = InputStreamReader(stream, Charsets.UTF_8)
            val buffer = CharArray(OUTPUT_BUFFER_CHARS)
            val builder = StringBuilder()
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                val remaining = MAX_CAPTURE_CHARS - builder.length
                if (remaining > 0) {
                    builder.append(buffer, 0, minOf(read, remaining))
                }
            }
            builder.toString()
        }

    private fun awaitOutput(output: CompletableFuture<String>): String =
        try {
            output.get(STREAM_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            ""
        }

    private companion object {
        const val TIMEOUT_EXIT_CODE = 124
        const val RESOURCE_LIMIT_EXIT_CODE = 137
        const val OUTPUT_BUFFER_CHARS = 4096
        const val MAX_CAPTURE_CHARS = PracticeContract.MAX_STDIO_EXCERPT_BYTES * 2
        const val STREAM_DRAIN_TIMEOUT_SECONDS = 2L
    }
}
