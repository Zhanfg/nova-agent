package fuck.andes.agent.workspace

import java.io.File
import java.util.UUID

/**
 * Git-backed workspace lifecycle used by Mobile Codex tasks.
 *
 * The manager never mutates a dirty original workspace when creating an isolated worktree. Worktree
 * paths are generated under [worktreeBaseDir] and all shell arguments are quoted before execution.
 */
internal class AgentGitWorkspaceManager(
    private val git: AgentGitExecutor,
    private val worktreeBaseDir: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString().replace("-", "").take(12) },
) {
    data class InspectResult(
        val workspace: AgentWorkspace?,
        val error: String? = null,
    )

    data class WorktreeResult(
        val workspace: AgentWorkspace?,
        val error: String? = null,
    )

    fun inspect(path: String): InspectResult {
        val root = git.execute("git rev-parse --show-toplevel", path, DEFAULT_TIMEOUT_MS)
        if (!root.ok) {
            return InspectResult(
                workspace = AgentWorkspace(
                    workspaceId = "local-${idFactory()}",
                    rootPath = path,
                    repositoryRoot = null,
                    baseRef = null,
                    baseSha = null,
                    branch = null,
                    worktreePath = null,
                    sharedOriginalWorkspace = true,
                    dirtyBaseline = AgentWorkspace.DirtyBaseline("", clock()),
                ),
                error = root.stderr.ifBlank { "当前目录不是 Git 仓库" },
            )
        }

        val repositoryRoot = root.stdout.trim().ifBlank { path }
        val head = requiredGit("git rev-parse HEAD", repositoryRoot)
            ?: return InspectResult(null, "无法读取 Git HEAD")
        val branchResult = git.execute(
            "git symbolic-ref --quiet --short HEAD",
            repositoryRoot,
            DEFAULT_TIMEOUT_MS,
        )
        val branch = branchResult.stdout.trim().takeIf { branchResult.ok && it.isNotBlank() }
        val status = git.execute(
            "git status --porcelain=v1 --untracked-files=all",
            repositoryRoot,
            DEFAULT_TIMEOUT_MS,
        )
        if (!status.ok) return InspectResult(null, status.stderr.ifBlank { "无法读取 Git 状态" })

        return InspectResult(
            workspace = AgentWorkspace(
                workspaceId = "repo-${idFactory()}",
                rootPath = repositoryRoot,
                repositoryRoot = repositoryRoot,
                baseRef = branch ?: head,
                baseSha = head,
                branch = branch,
                worktreePath = null,
                sharedOriginalWorkspace = true,
                dirtyBaseline = AgentWorkspace.DirtyBaseline(status.stdout, clock()),
            )
        )
    }

    /**
     * Creates an isolated branch+worktree for a writing task. Dirty files in the source workspace are
     * intentionally not copied; the isolated worktree starts from [baseRef] (or the inspected HEAD).
     */
    fun createIsolatedWorktree(
        source: AgentWorkspace,
        taskId: String,
        baseRef: String? = null,
        branchName: String? = null,
    ): WorktreeResult {
        val repositoryRoot = source.repositoryRoot
            ?: return WorktreeResult(null, "只有 Git workspace 可以创建 worktree")
        val base = baseRef?.takeIf(String::isNotBlank)
            ?: source.baseSha?.takeIf(String::isNotBlank)
            ?: return WorktreeResult(null, "缺少 worktree base ref")

        val safeTask = sanitizeSlug(taskId).ifBlank { "task" }
        val suffix = idFactory()
        val branch = branchName?.takeIf(String::isNotBlank)
            ?: "nova/$safeTask-$suffix"
        if (!isSafeGitRef(branch)) return WorktreeResult(null, "非法 Git branch 名称")

        val target = File(worktreeBaseDir, "$safeTask-$suffix").absolutePath
        val mkdir = git.execute(
            "mkdir -p ${AgentShellQuote.quote(worktreeBaseDir)}",
            repositoryRoot,
            DEFAULT_TIMEOUT_MS,
        )
        if (!mkdir.ok) return WorktreeResult(null, mkdir.stderr.ifBlank { "无法创建 worktree 目录" })

        val command = buildString {
            append("git worktree add --no-track -b ")
            append(AgentShellQuote.quote(branch))
            append(' ')
            append(AgentShellQuote.quote(target))
            append(' ')
            append(AgentShellQuote.quote(base))
        }
        val add = git.execute(command, repositoryRoot, WORKTREE_TIMEOUT_MS)
        if (!add.ok) {
            return WorktreeResult(null, add.stderr.ifBlank { "git worktree add 失败" })
        }

        val head = requiredGit("git rev-parse HEAD", target)
        if (head == null) {
            git.execute(
                "git worktree remove --force ${AgentShellQuote.quote(target)}",
                repositoryRoot,
                WORKTREE_TIMEOUT_MS,
            )
            return WorktreeResult(null, "worktree 创建后无法读取 HEAD")
        }

        return WorktreeResult(
            workspace = AgentWorkspace(
                workspaceId = "wt-$suffix",
                rootPath = repositoryRoot,
                repositoryRoot = repositoryRoot,
                baseRef = base,
                baseSha = head,
                branch = branch,
                worktreePath = target,
                sharedOriginalWorkspace = false,
                dirtyBaseline = AgentWorkspace.DirtyBaseline("", clock()),
            )
        )
    }

    fun removeIsolatedWorktree(
        workspace: AgentWorkspace,
        force: Boolean = false,
    ): String? {
        val repositoryRoot = workspace.repositoryRoot ?: return "不是 Git workspace"
        val worktree = workspace.worktreePath ?: return "不能通过 worktree API 删除原始 workspace"
        if (workspace.sharedOriginalWorkspace) return "拒绝删除共享原始 workspace"

        if (!force) {
            val status = git.execute(
                "git status --porcelain=v1 --untracked-files=all",
                worktree,
                DEFAULT_TIMEOUT_MS,
            )
            if (!status.ok) return status.stderr.ifBlank { "无法检查 worktree 状态" }
            if (status.stdout.isNotBlank()) return "worktree 含未提交修改；请先审查、提交或显式 force"
        }

        val forceFlag = if (force) " --force" else ""
        val remove = git.execute(
            "git worktree remove$forceFlag ${AgentShellQuote.quote(worktree)}",
            repositoryRoot,
            WORKTREE_TIMEOUT_MS,
        )
        return if (remove.ok) null else remove.stderr.ifBlank { "git worktree remove 失败" }
    }

    fun status(workspace: AgentWorkspace): AgentGitExecutor.Result =
        git.execute(
            "git status --porcelain=v1 --branch --untracked-files=all",
            workspace.effectivePath,
            DEFAULT_TIMEOUT_MS,
        )

    fun diff(workspace: AgentWorkspace, staged: Boolean = false): AgentGitExecutor.Result {
        val cached = if (staged) " --cached" else ""
        return git.execute(
            "git -c core.quotePath=false diff$cached --no-ext-diff --no-color --submodule=diff",
            workspace.effectivePath,
            DIFF_TIMEOUT_MS,
        )
    }

    fun log(workspace: AgentWorkspace, maxCount: Int = 30): AgentGitExecutor.Result =
        git.execute(
            "git log --no-color --decorate=short --date=iso-strict --pretty=format:%H%x09%ad%x09%D%x09%s -n ${maxCount.coerceIn(1, 200)}",
            workspace.effectivePath,
            DEFAULT_TIMEOUT_MS,
        )

    private fun requiredGit(command: String, cwd: String): String? {
        val result = git.execute(command, cwd, DEFAULT_TIMEOUT_MS)
        return result.stdout.trim().takeIf { result.ok && it.isNotBlank() }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 30_000
        private const val DIFF_TIMEOUT_MS = 60_000
        private const val WORKTREE_TIMEOUT_MS = 90_000
        private val INVALID_REF_CHARS = setOf('~', '^', ':', '?', '*', '[', '\\')

        internal fun sanitizeSlug(value: String): String =
            value.lowercase()
                .map { ch -> if (ch.isLetterOrDigit() || ch in "-_.") ch else '-' }
                .joinToString("")
                .trim('-', '.', '_')
                .take(48)

        internal fun isSafeGitRef(value: String): Boolean {
            if (value.isBlank() || value.length > 180) return false
            if (value.startsWith('-') || value.endsWith('/') || value.endsWith('.')) return false
            if (".." in value || "//" in value || "@{" in value) return false
            if (value.any { it.isWhitespace() || it.code < 0x20 || it in INVALID_REF_CHARS }) return false
            return value.split('/').all { part ->
                part.isNotBlank() &&
                    part != "." &&
                    part != ".." &&
                    !part.startsWith('.') &&
                    !part.endsWith(".lock")
            }
        }
    }
}
