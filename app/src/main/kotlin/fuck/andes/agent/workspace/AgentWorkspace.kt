package fuck.andes.agent.workspace

/**
 * Stable identity and provenance for one Codex-style engineering workspace.
 *
 * A task must never infer its repository/base state from the process-wide current directory. The
 * workspace record travels with the task so concurrent agents can safely operate on independent
 * worktrees of the same repository.
 */
internal data class AgentWorkspace(
    val workspaceId: String,
    val rootPath: String,
    val repositoryRoot: String?,
    val baseRef: String?,
    val baseSha: String?,
    val branch: String?,
    val worktreePath: String?,
    val sharedOriginalWorkspace: Boolean,
    val dirtyBaseline: DirtyBaseline,
) {
    data class DirtyBaseline(
        val porcelain: String,
        val capturedAtMillis: Long,
    ) {
        val isDirty: Boolean
            get() = porcelain.isNotBlank()
    }

    val effectivePath: String
        get() = worktreePath ?: rootPath
}
