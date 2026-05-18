package com.aicodelearning.learning

import com.aicodelearning.auth.sha256Hex

object PracticeAssetRevision {
    fun compute(
        problem: ProblemEntity,
        files: List<ProblemFileEntity>,
        hints: List<ProblemHintEntity>,
        provenance: List<ProblemProvenanceLinkEntity>,
    ): String =
        "rev-" +
            sha256Hex(
                buildString {
                    append(problem.id).append('\n')
                    append(problem.prompt).append('\n')
                    append(problem.difficulty).append('\n')
                    files.forEach { append(it.path).append('|').append(it.fileRole).append('|').append(it.content).append('\n') }
                    hints.forEach { append(it.id).append('|').append(it.revealOrder).append('|').append(it.content).append('\n') }
                    provenance.forEach { append(it.id).append('|').append(it.redactedExcerpt).append('\n') }
                },
            ).take(16)
}
