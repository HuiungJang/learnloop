package com.aicodelearning.runner

import com.aicodelearning.learning.PracticeContract
import org.springframework.stereotype.Component

data class DockerRunPlan(
    val command: List<String>,
    val constraints: RunnerConstraintReport,
)

data class RunnerConstraintReport(
    val networkDisabled: Boolean,
    val cpuLimit: String,
    val memoryLimit: String,
    val pidsLimit: Int,
    val outputLimitBytes: Int,
    val wallClockTimeoutMs: Long,
    val noNewPrivileges: Boolean,
    val capabilitiesDropped: Boolean,
    val readOnlyRootFilesystem: Boolean,
    val tmpfsScratch: Boolean,
    val warnings: List<String>,
)

@Component
class DockerRunCommandBuilder(
    private val properties: RunnerProperties,
) {
    fun build(
        request: ValidatedRunnerRunRequest,
        workspace: RunnerWorkspace,
    ): DockerRunPlan {
        val constraints =
            RunnerConstraintReport(
                networkDisabled = true,
                cpuLimit = CPU_LIMIT,
                memoryLimit = MEMORY_LIMIT,
                pidsLimit = PIDS_LIMIT,
                outputLimitBytes = PracticeContract.MAX_STDIO_EXCERPT_BYTES,
                wallClockTimeoutMs = request.timeoutMs,
                noNewPrivileges = true,
                capabilitiesDropped = true,
                readOnlyRootFilesystem = true,
                tmpfsScratch = true,
                warnings = emptyList(),
            )
        val command =
            listOf(
                properties.dockerCommand,
                "run",
                "--rm",
                "--network",
                "none",
                "--cpus",
                constraints.cpuLimit,
                "--memory",
                constraints.memoryLimit,
                "--pids-limit",
                constraints.pidsLimit.toString(),
                "--security-opt",
                "no-new-privileges",
                "--cap-drop",
                "ALL",
                "--read-only",
                "--tmpfs",
                "/tmp:rw,noexec,nosuid,size=$TMPFS_SIZE",
                "--workdir",
                "/workspace",
                "--volume",
                "${workspace.root}:/workspace:rw",
                request.harness.image,
            ) + request.harness.command

        return DockerRunPlan(command = command, constraints = constraints)
    }

    private companion object {
        const val CPU_LIMIT = "1.0"
        const val MEMORY_LIMIT = "512m"
        const val PIDS_LIMIT = 128
        const val TMPFS_SIZE = "64m"
    }
}
