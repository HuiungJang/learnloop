package com.aicodelearning.runner

import com.aicodelearning.learning.PracticeContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class DockerRunCommandBuilderTest {
    private val validator = RunnerRequestValidator(RunnerRegistry())
    private val builder = DockerRunCommandBuilder(RunnerProperties())

    @Test
    fun `builds docker run command with sandbox constraints`() {
        val request =
            validator.validate(
                RunnerRunRequest(
                    language = PracticeContract.LANGUAGE_TYPESCRIPT,
                    testHarnessId = "typescript-node-test",
                    timeoutMs = 5_000,
                    files = listOf(RunnerRunFile(path = "src/solution.ts", content = "export const answer = 1")),
                ),
            )
        val workspace = RunnerWorkspace(runId = request.runId, root = Path.of("/tmp/learnloop-run"))

        val plan = builder.build(request, workspace)

        assertTrue(plan.command.containsAll(listOf("--network", "none")))
        assertTrue(plan.command.containsAll(listOf("--cpus", "1.0")))
        assertTrue(plan.command.containsAll(listOf("--memory", "512m")))
        assertTrue(plan.command.containsAll(listOf("--pids-limit", "128")))
        assertTrue(plan.command.containsAll(listOf("--security-opt", "no-new-privileges")))
        assertTrue(plan.command.containsAll(listOf("--cap-drop", "ALL")))
        assertTrue(plan.command.contains("--read-only"))
        assertTrue(plan.command.containsAll(listOf("--tmpfs", "/tmp:rw,noexec,nosuid,size=64m")))
        assertTrue(plan.command.containsAll(listOf("--workdir", "/workspace")))
        assertTrue(plan.command.containsAll(listOf("--volume", "/tmp/learnloop-run:/workspace:rw")))
        assertTrue(plan.command.contains("learnloop-runner-typescript:latest"))
        assertTrue(plan.command.containsAll(listOf("node", "/opt/learnloop-runner/run-tests.mjs")))
    }

    @Test
    fun `reports effective constraints for runner smoke`() {
        val request =
            validator.validate(
                RunnerRunRequest(
                    language = PracticeContract.LANGUAGE_TYPESCRIPT,
                    testHarnessId = "typescript-node-test",
                    timeoutMs = 7_000,
                    files = listOf(RunnerRunFile(path = "src/solution.ts", content = "export const answer = 1")),
                ),
            )

        val report = builder.build(request, RunnerWorkspace(runId = request.runId, root = Path.of("/tmp/learnloop-run"))).constraints

        assertTrue(report.networkDisabled)
        assertEquals("1.0", report.cpuLimit)
        assertEquals("512m", report.memoryLimit)
        assertEquals(128, report.pidsLimit)
        assertEquals(PracticeContract.MAX_STDIO_EXCERPT_BYTES, report.outputLimitBytes)
        assertEquals(7_000, report.wallClockTimeoutMs)
        assertTrue(report.noNewPrivileges)
        assertTrue(report.capabilitiesDropped)
        assertTrue(report.readOnlyRootFilesystem)
        assertTrue(report.tmpfsScratch)
        assertEquals(emptyList<String>(), report.warnings)
    }
}
