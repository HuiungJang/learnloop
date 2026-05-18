package com.aicodelearning.runner

import com.aicodelearning.learning.PracticeContract
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

data class RunnerWorkspace(
    val runId: String,
    val root: Path,
)

@Service
class RunnerWorkspaceService {
    fun <T> withWorkspace(
        request: ValidatedRunnerRunRequest,
        action: (RunnerWorkspace) -> T,
    ): T {
        val root = Files.createTempDirectory("learnloop-${request.runId}-")
        return try {
            writeFiles(root, request)
            action(RunnerWorkspace(runId = request.runId, root = root))
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
