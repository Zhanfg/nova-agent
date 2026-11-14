package fuck.andes.agent.workspace

import java.io.File

/** Loads Codex-compatible AGENTS.md instructions from repository root to the active directory. */
internal object AgentProjectInstructions {
    private const val MAX_FILE_BYTES = 64 * 1024
    private const val MAX_TOTAL_BYTES = 192 * 1024

    data class Layer(
        val directory: String,
        val file: String,
        val content: String,
    )

    data class LoadResult(
        val layers: List<Layer>,
        val truncated: Boolean,
        val error: String? = null,
    ) {
        fun renderForModel(): String = buildString {
            layers.forEachIndexed { index, layer ->
                if (index > 0) append("\n\n")
                append("<agents_instructions path=\"")
                append(layer.file)
                append("\">\n")
                append(layer.content.trimEnd())
                append("\n</agents_instructions>")
            }
            if (truncated) {
                append("\n\n[部分 AGENTS.md 内容因大小上限被截断]")
            }
        }
    }

    fun load(repositoryRoot: String, workingDirectory: String): LoadResult {
        val root = runCatching { File(repositoryRoot).canonicalFile }.getOrElse {
            return LoadResult(emptyList(), truncated = false, error = "无法解析 repository root")
        }
        val workdir = runCatching { File(workingDirectory).canonicalFile }.getOrElse {
            return LoadResult(emptyList(), truncated = false, error = "无法解析 working directory")
        }

        if (!isWithin(root, workdir)) {
            return LoadResult(
                emptyList(),
                truncated = false,
                error = "working directory 不在 repository root 内",
            )
        }

        val directories = buildList {
            var current: File? = workdir
            while (current != null && isWithin(root, current)) {
                add(current)
                if (current == root) break
                current = current.parentFile
            }
        }.asReversed()

        val layers = mutableListOf<Layer>()
        var remaining = MAX_TOTAL_BYTES
        var truncated = false

        for (directory in directories) {
            if (remaining <= 0) {
                truncated = true
                break
            }
            val candidate = File(directory, "AGENTS.md")
            if (!candidate.isFile) continue
            val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: continue
            if (!isWithin(root, canonical)) continue

            val allowed = minOf(MAX_FILE_BYTES, remaining)
            val bytes = runCatching { canonical.inputStream().use { it.readNBytes(allowed + 1) } }
                .getOrNull() ?: continue
            val clipped = bytes.size > allowed
            val safeBytes = if (clipped) bytes.copyOf(allowed) else bytes
            val content = safeBytes.toString(Charsets.UTF_8)
            remaining -= safeBytes.size
            truncated = truncated || clipped
            layers += Layer(
                directory = directory.path,
                file = canonical.path,
                content = content,
            )
        }

        return LoadResult(layers, truncated)
    }

    private fun isWithin(root: File, child: File): Boolean {
        val rootPath = root.toPath().normalize()
        val childPath = child.toPath().normalize()
        return childPath == rootPath || childPath.startsWith(rootPath)
    }
}
