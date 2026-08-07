package fuck.andes.agent.workspace

/** Mutating Git operations kept separate from workspace creation for tighter validation/auditing. */
internal class AgentGitMutationService(
    private val git: AgentGitExecutor,
) {
    fun stage(workspace: AgentWorkspace, paths: List<String>): AgentGitExecutor.Result =
        executePaths(workspace, "git add --", paths)

    fun unstage(workspace: AgentWorkspace, paths: List<String>): AgentGitExecutor.Result =
        executePaths(workspace, "git restore --staged --", paths)

    fun restoreWorkingTree(workspace: AgentWorkspace, paths: List<String>): AgentGitExecutor.Result =
        executePaths(workspace, "git restore --worktree --", paths)

    fun commit(workspace: AgentWorkspace, message: String): AgentGitExecutor.Result {
        require(message.isNotBlank()) { "commit message 不能为空" }
        require(message.length <= 8_000) { "commit message 过长" }
        return git.execute(
            "git commit -m ${AgentShellQuote.quote(message)}",
            workspace.effectivePath,
            90_000,
        )
    }

    fun createAndSwitchBranch(
        workspace: AgentWorkspace,
        branch: String,
    ): AgentGitExecutor.Result {
        require(AgentGitWorkspaceManager.isSafeGitRef(branch)) { "非法 Git branch 名称" }
        return git.execute(
            "git switch -c ${AgentShellQuote.quote(branch)}",
            workspace.effectivePath,
            60_000,
        )
    }

    private fun executePaths(
        workspace: AgentWorkspace,
        prefix: String,
        paths: List<String>,
    ): AgentGitExecutor.Result {
        require(paths.isNotEmpty()) { "paths 不能为空" }
        require(paths.size <= 200) { "一次最多处理 200 个 path" }
        val normalized = paths.map(::validateRelativePath)
        val command = prefix + " " + normalized.joinToString(" ") { AgentShellQuote.quote(it) }
        return git.execute(command, workspace.effectivePath, 60_000)
    }

    companion object {
        internal fun validateRelativePath(path: String): String {
            val value = path.trim()
            require(value.isNotBlank()) { "path 不能为空" }
            require(value.length <= 2_048) { "path 过长" }
            require(!value.startsWith('/')) { "Git path 必须是 workspace 相对路径" }
            require('\\' !in value) { "Git path 使用 / 分隔" }
            val segments = value.split('/')
            require(segments.none { it.isBlank() || it == "." || it == ".." }) {
                "Git path 不允许空段、. 或 .."
            }
            require(value.none { it.code == 0 || it.code < 0x20 }) { "Git path 含控制字符" }
            return value
        }
    }
}
