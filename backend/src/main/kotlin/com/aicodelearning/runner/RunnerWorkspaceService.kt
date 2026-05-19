package com.aicodelearning.runner

import com.aicodelearning.learning.PracticeContract
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

data class RunnerWorkspace(
    val runId: String,
    val root: Path,
    val dockerRoot: Path,
)

@Service
class RunnerWorkspaceService(
    private val properties: RunnerProperties = RunnerProperties(),
) {
    fun <T> withWorkspace(
        request: ValidatedRunnerRunRequest,
        action: (RunnerWorkspace) -> T,
    ): T {
        val root = createWorkspaceRoot(request.runId)
        return try {
            writeFiles(root, request)
            action(RunnerWorkspace(runId = request.runId, root = root, dockerRoot = dockerRootFor(root)))
        } finally {
            deleteRecursively(root)
        }
    }

    fun truncateOutput(output: String): String = truncateUtf8(output, PracticeContract.MAX_STDIO_EXCERPT_BYTES)

    private fun writeFiles(
        root: Path,
        request: ValidatedRunnerRunRequest,
    ) {
        request.harness.files.forEach { file ->
            writeFile(root, file.path, file.content)
        }
        request.files.forEach { file ->
            writeFile(root, file.path, file.content)
        }
    }

    private fun writeFile(
        root: Path,
        relativePath: String,
        content: String,
    ) {
        val target = root.resolve(relativePath).normalize()
        if (!target.startsWith(root)) {
            error("validated runner file escaped workspace")
        }
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
    }

    private fun createWorkspaceRoot(runId: String): Path {
        val configuredRoot = properties.workspaceContainerRoot.trim()
        if (configuredRoot.isBlank()) {
            return Files.createTempDirectory("learnloop-$runId-")
        }

        val rootDirectory = Path.of(configuredRoot).toAbsolutePath().normalize()
        Files.createDirectories(rootDirectory)
        return Files.createTempDirectory(rootDirectory, "learnloop-$runId-")
    }

    private fun dockerRootFor(root: Path): Path {
        val containerRoot = properties.workspaceContainerRoot.trim()
        val hostRoot = properties.workspaceHostRoot.trim()
        if (containerRoot.isBlank() || hostRoot.isBlank()) return root

        val normalizedContainerRoot = Path.of(containerRoot).toAbsolutePath().normalize()
        val normalizedRoot = root.toAbsolutePath().normalize()
        if (!normalizedRoot.startsWith(normalizedContainerRoot)) return root

        val relativeRoot = normalizedContainerRoot.relativize(normalizedRoot)
        return Path.of(hostRoot).toAbsolutePath().normalize().resolve(relativeRoot).normalize()
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun truncateUtf8(
        output: String,
        maxBytes: Int,
    ): String {
        var usedBytes = 0
        val builder = StringBuilder()
        var index = 0
        while (index < output.length) {
            val codePoint = output.codePointAt(index)
            val text = String(Character.toChars(codePoint))
            val charBytes = text.toByteArray(Charsets.UTF_8).size
            if (usedBytes + charBytes > maxBytes) {
                return builder.toString()
            }
            builder.append(text)
            usedBytes += charBytes
            index += Character.charCount(codePoint)
        }
        return builder.toString()
    }
}
