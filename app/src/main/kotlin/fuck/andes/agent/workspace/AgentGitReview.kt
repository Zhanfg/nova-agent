package fuck.andes.agent.workspace

/** Structured change set used by the Mobile Codex review surface. */
internal data class AgentGitChangeSet(
    val files: List<FileChange>,
) {
    data class FileChange(
        val oldPath: String?,
        val newPath: String?,
        val status: Status,
        val hunks: List<Hunk>,
    )

    enum class Status {
        ADDED,
        DELETED,
        MODIFIED,
        RENAMED,
    }

    data class Hunk(
        val id: String,
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val heading: String,
        val lines: List<Line>,
    )

    data class Line(
        val kind: LineKind,
        val content: String,
        val oldLine: Int?,
        val newLine: Int?,
    )

    enum class LineKind {
        CONTEXT,
        ADDED,
        REMOVED,
        META,
    }
}

internal object AgentUnifiedDiffParser {
    private val HUNK_HEADER = Regex(
        "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$"
    )

    fun parse(diff: String): AgentGitChangeSet {
        if (diff.isBlank()) return AgentGitChangeSet(emptyList())
        val lines = diff.lineSequence().toList()
        val files = mutableListOf<AgentGitChangeSet.FileChange>()
        var index = 0
        var fileIndex = 0

        while (index < lines.size) {
            if (!lines[index].startsWith("diff --git ")) {
                index++
                continue
            }

            val header = lines[index]
            val paths = parseDiffGitPaths(header)
            var oldPath = paths.first
            var newPath = paths.second
            var added = false
            var deleted = false
            var renamed = false
            val hunks = mutableListOf<AgentGitChangeSet.Hunk>()
            index++

            while (index < lines.size && !lines[index].startsWith("diff --git ")) {
                val line = lines[index]
                when {
                    line.startsWith("new file mode ") -> added = true
                    line.startsWith("deleted file mode ") -> deleted = true
                    line.startsWith("rename from ") -> {
                        renamed = true
                        oldPath = line.removePrefix("rename from ").trim()
                    }
                    line.startsWith("rename to ") -> {
                        renamed = true
                        newPath = line.removePrefix("rename to ").trim()
                    }
                    line.startsWith("--- ") -> {
                        oldPath = parsePatchPath(line.removePrefix("--- "))
                        if (oldPath == null) added = true
                    }
                    line.startsWith("+++ ") -> {
                        newPath = parsePatchPath(line.removePrefix("+++ "))
                        if (newPath == null) deleted = true
                    }
                    line.startsWith("@@ ") -> {
                        val parsed = parseHunk(
                            lines = lines,
                            start = index,
                            fileIndex = fileIndex,
                            hunkIndex = hunks.size,
                        )
                        hunks += parsed.hunk
                        index = parsed.nextIndex
                        continue
                    }
                }
                index++
            }

            val status = when {
                renamed -> AgentGitChangeSet.Status.RENAMED
                added -> AgentGitChangeSet.Status.ADDED
                deleted -> AgentGitChangeSet.Status.DELETED
                else -> AgentGitChangeSet.Status.MODIFIED
            }
            files += AgentGitChangeSet.FileChange(
                oldPath = oldPath,
                newPath = newPath,
                status = status,
                hunks = hunks,
            )
            fileIndex++
        }

        return AgentGitChangeSet(files)
    }

    private data class ParsedHunk(
        val hunk: AgentGitChangeSet.Hunk,
        val nextIndex: Int,
    )

    private fun parseHunk(
        lines: List<String>,
        start: Int,
        fileIndex: Int,
        hunkIndex: Int,
    ): ParsedHunk {
        val match = HUNK_HEADER.matchEntire(lines[start])
            ?: return ParsedHunk(
                hunk = AgentGitChangeSet.Hunk(
                    id = "f${fileIndex}-h${hunkIndex}",
                    oldStart = 0,
                    oldCount = 0,
                    newStart = 0,
                    newCount = 0,
                    heading = lines[start],
                    lines = emptyList(),
                ),
                nextIndex = start + 1,
            )

        val oldStart = match.groupValues[1].toInt()
        val oldCount = match.groupValues[2].ifBlank { "1" }.toInt()
        val newStart = match.groupValues[3].toInt()
        val newCount = match.groupValues[4].ifBlank { "1" }.toInt()
        val heading = match.groupValues[5].trim()
        var oldLine = oldStart
        var newLine = newStart
        val body = mutableListOf<AgentGitChangeSet.Line>()
        var index = start + 1

        while (index < lines.size) {
            val raw = lines[index]
            if (raw.startsWith("diff --git ") || raw.startsWith("@@ ")) break
            when {
                raw.startsWith("+") && !raw.startsWith("+++") -> {
                    body += AgentGitChangeSet.Line(
                        kind = AgentGitChangeSet.LineKind.ADDED,
                        content = raw.drop(1),
                        oldLine = null,
                        newLine = newLine++,
                    )
                }
                raw.startsWith("-") && !raw.startsWith("---") -> {
                    body += AgentGitChangeSet.Line(
                        kind = AgentGitChangeSet.LineKind.REMOVED,
                        content = raw.drop(1),
                        oldLine = oldLine++,
                        newLine = null,
                    )
                }
                raw.startsWith(" ") -> {
                    body += AgentGitChangeSet.Line(
                        kind = AgentGitChangeSet.LineKind.CONTEXT,
                        content = raw.drop(1),
                        oldLine = oldLine++,
                        newLine = newLine++,
                    )
                }
                raw.startsWith("\\ No newline at end of file") -> {
                    body += AgentGitChangeSet.Line(
                        kind = AgentGitChangeSet.LineKind.META,
                        content = raw,
                        oldLine = null,
                        newLine = null,
                    )
                }
                else -> break
            }
            index++
        }

        return ParsedHunk(
            hunk = AgentGitChangeSet.Hunk(
                id = "f${fileIndex}-h${hunkIndex}",
                oldStart = oldStart,
                oldCount = oldCount,
                newStart = newStart,
                newCount = newCount,
                heading = heading,
                lines = body,
            ),
            nextIndex = index,
        )
    }

    private fun parsePatchPath(value: String): String? {
        val path = value.substringBefore('\t').trim()
        if (path == "/dev/null") return null
        return path.removePrefix("a/").removePrefix("b/")
    }

    private fun parseDiffGitPaths(header: String): Pair<String?, String?> {
        val payload = header.removePrefix("diff --git ")
        val marker = " b/"
        val split = payload.indexOf(marker)
        if (split < 0) return null to null
        val old = payload.substring(0, split).removePrefix("a/").trim()
        val new = payload.substring(split + 1).removePrefix("b/").trim()
        return old to new
    }
}
