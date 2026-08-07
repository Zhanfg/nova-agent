package fuck.andes.agent.tool

import android.content.Context
import fuck.andes.agent.terminal.RootShellTerminalController
import fuck.andes.agent.workspace.AgentGitChangeSet
import fuck.andes.agent.workspace.AgentGitMutationService
import fuck.andes.agent.workspace.AgentGitWorkspaceManager
import fuck.andes.agent.workspace.AgentProjectInstructions
import fuck.andes.agent.workspace.AgentUnifiedDiffParser
import fuck.andes.agent.workspace.AgentWorkspace
import fuck.andes.agent.workspace.AgentWorkspacePathPolicy
import fuck.andes.agent.workspace.AgentWorkspaceRegistry
import fuck.andes.agent.workspace.AgentWorkspaceRuntimeRegistry
import fuck.andes.agent.workspace.RootShellGitExecutor
import org.json.JSONArray
import org.json.JSONObject

/** Runtime facade for Mobile Codex workspace/Git tools. */
internal class AgentWorkspaceTools(
    context: Context,
    terminalController: RootShellTerminalController,
    private val workspaceRegistry: AgentWorkspaceRegistry = AgentWorkspaceRuntimeRegistry.get(context),
) {
    private val git = RootShellGitExecutor(terminalController)
    private val manager = AgentGitWorkspaceManager(
        git = git,
        worktreeBaseDir = AgentWorkspacePathPolicy.generatedWorktreeBase(context),
    )
    private val mutations = AgentGitMutationService(git)

    fun execute(name: String, args: JSONObject): String? = when (name) {
        "workspace_inspect" -> inspect(args)
        "workspace_create_worktree" -> createWorktree(args)
        "workspace_remove_worktree" -> removeWorktree(args)
        "git_status" -> gitStatus(args)
        "git_diff" -> gitDiff(args)
        "git_log" -> gitLog(args)
        "git_stage" -> gitStage(args)
        "git_unstage" -> gitUnstage(args)
        "git_restore" -> gitRestore(args)
        "git_commit" -> gitCommit(args)
        "git_create_branch" -> gitCreateBranch(args)
        "project_instructions" -> projectInstructions(args)
        else -> null
    }

    private fun inspect(args: JSONObject): String {
        val validation = AgentWorkspacePathPolicy.validateReadableWorkspace(args.getString("path"))
        val path = validation.canonicalPath
            ?: return error("WORKSPACE_PATH_UNAVAILABLE", validation.error ?: "workspace 路径不可用")
        val result = manager.inspect(path)
        val workspace = result.workspace ?: return error("WORKSPACE_INSPECT_FAILED", result.error ?: "检查失败")
        runCatching { workspaceRegistry.register(workspace) }.getOrElse { failure ->
            return error(
                "WORKSPACE_REGISTRY_PERSIST_FAILED",
                failure.message ?: "无法持久化 workspace identity",
            )
        }
        return attachProjectInstructions(
            workspaceJson(workspace)
                .put("ok", true)
                .put("warning", result.error ?: JSONObject.NULL),
            workspace,
        ).toString()
    }

    private fun createWorktree(args: JSONObject): String {
        val source = workspace(args) ?: return missingWorkspace(args)
        val result = manager.createIsolatedWorktree(
            source = source,
            taskId = args.getString("task_id"),
            baseRef = args.optString("base_ref").takeIf(String::isNotBlank),
            branchName = args.optString("branch").takeIf(String::isNotBlank),
        )
        val workspace = result.workspace
            ?: return error("WORKTREE_CREATE_FAILED", result.error ?: "创建 worktree 失败")
        runCatching { workspaceRegistry.register(workspace) }.getOrElse { failure ->
            val cleanupFailure = manager.removeIsolatedWorktree(workspace, force = true)
            return error(
                "WORKSPACE_REGISTRY_PERSIST_FAILED",
                buildString {
                    append(failure.message ?: "worktree 已创建但 workspace identity 无法持久化")
                    if (cleanupFailure != null) {
                        append("；回滚 worktree 也失败：")
                        append(cleanupFailure)
                    }
                },
            )
        }
        return attachProjectInstructions(
            workspaceJson(workspace).put("ok", true),
            workspace,
        ).toString()
    }

    private fun removeWorktree(args: JSONObject): String {
        val workspace = workspace(args) ?: return missingWorkspace(args)
        val failure = manager.removeIsolatedWorktree(
            workspace = workspace,
            force = args.optBoolean("force", false),
        )
        if (failure != null) return error("WORKTREE_REMOVE_FAILED", failure)
        runCatching { workspaceRegistry.remove(workspace.workspaceId) }.getOrElse { persistFailure ->
            return error(
                "WORKSPACE_REGISTRY_PERSIST_FAILED",
                "worktree 已删除，但 registry 清理未持久化：${persistFailure.message ?: persistFailure.javaClass.simpleName}",
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("workspace_id", workspace.workspaceId)
            .toString()
    }

    private fun gitStatus(args: JSONObject): String {
        val workspace = workspace(args) ?: return missingWorkspace(args)
        return commandResult("GIT_STATUS_FAILED", manager.status(workspace))
    }

    private fun gitDiff(args: JSONObject): String {
        val workspace = workspace(args) ?: return missingWorkspace(args)
        val result = manager.diff(workspace, staged = args.optBoolean("staged", false))
        if (!result.ok) return commandResult("GIT_DIFF_FAILED", result)
        if (!result.complete) {
            return error(
                "GIT_DIFF_TRUNCATED",
                "Git diff 超出结构化 Review 输出上限；禁止把截断内容当作完整 Review，请缩小 diff 范围或使用后续分页接口。",
            )
        }
        val changes = AgentUnifiedDiffParser.parse(result.stdout)
        return JSONObject()
            .put("ok", true)
            .put("raw_diff", result.stdout)
            .put("files", changeSetJson(changes))
            .toString()
    }

    private fun gitLog(args: JSONObject): String {
        val workspace = workspace(args) ?: return missingWorkspace(args)
        val result = manager.log(workspace, args.optInt("max_count", 30))
        return commandResult("GIT_LOG_FAILED", result)
    }

    private fun gitStage(args: JSONObject): String = mutatePaths(args, "GIT_STAGE_FAILED") {
        workspace, paths -> mutations.stage(workspace, paths)
    }

    private fun gitUnstage(args: JSONObject): String = mutatePaths(args, "GIT_UNSTAGE_FAILED") {
        workspace, paths -> mutations.unstage(workspace, paths)
    }

    private fun gitRestore(args: JSONObject): String = mutatePaths(args, "GIT_RESTORE_FAILED") {
        workspace, paths -> mutations.restoreWorkingTree(workspace, paths)
    }

    private fun gitCommit(args: JSONObject): String {
        val workspace = workspace(args) ?: return missingWorkspace(args)
        return commandResult(
            "GIT_COMMIT_FAILED",
            mutations.commit(workspace, args.getString("message")),
        )
    }

    private fun gitCreateBranch(args: JSONObject): String {
        val workspace = workspace(args) ?: return missingWorkspace(args)
        return commandResult(
            "GIT_BRANCH_FAILED",
            mutations.createAndSwitchBranch(workspace, args.getString("branch")),
        )
    }

    private fun projectInstructions(args: JSONObject): String {
        val workspace = workspace(args) ?: return missingWorkspace(args)
        val instructionRoot = workspace.effectivePath
        val workingDirectory = args.optString("working_directory")
            .takeIf(String::isNotBlank)
            ?: instructionRoot
        val result = AgentProjectInstructions.load(instructionRoot, workingDirectory)
        result.error?.let { return error("PROJECT_INSTRUCTIONS_FAILED", it) }
        return JSONObject()
            .put("ok", true)
            .put("truncated", result.truncated)
            .put("rendered", result.renderForModel())
            .put("layers", JSONArray().also { layers ->
                result.layers.forEach { layer ->
                    layers.put(
                        JSONObject()
                            .put("directory", layer.directory)
                            .put("file", layer.file)
                            .put("content", layer.content)
                    )
                }
            })
            .toString()
    }

    /** Opening a Codex workspace must surface instructions from the active worktree file tree. */
    private fun attachProjectInstructions(
        target: JSONObject,
        workspace: AgentWorkspace,
    ): JSONObject {
        val instructionRoot = workspace.effectivePath
        val result = AgentProjectInstructions.load(instructionRoot, instructionRoot)
        if (result.error != null) {
            return target.put("project_instructions_error", result.error)
        }
        return target
            .put("project_instructions", result.renderForModel())
            .put("project_instructions_truncated", result.truncated)
            .put("project_instruction_layers", result.layers.size)
    }

    private inline fun mutatePaths(
        args: JSONObject,
        errorCode: String,
        operation: (AgentWorkspace, List<String>) -> fuck.andes.agent.workspace.AgentGitExecutor.Result,
    ): String {
        val workspace = workspace(args) ?: return missingWorkspace(args)
        val pathsJson = args.getJSONArray("paths")
        val paths = buildList(pathsJson.length()) {
            repeat(pathsJson.length()) { add(pathsJson.getString(it)) }
        }
        return commandResult(errorCode, operation(workspace, paths))
    }

    private fun workspace(args: JSONObject): AgentWorkspace? =
        workspaceRegistry.get(args.optString("workspace_id"))

    private fun missingWorkspace(args: JSONObject): String = error(
        "WORKSPACE_NOT_FOUND",
        "未知 workspace_id：${args.optString("workspace_id")}",
    )

    private fun commandResult(
        code: String,
        result: fuck.andes.agent.workspace.AgentGitExecutor.Result,
    ): String {
        if (!result.ok) {
            return error(code, result.stderr.ifBlank { "Git command failed (exit=${result.exitCode})" })
        }
        return JSONObject()
            .put("ok", true)
            .put("exit_code", result.exitCode)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr)
            .put("stdout_truncated", result.stdoutTruncated)
            .put("stderr_truncated", result.stderrTruncated)
            .toString()
    }

    private fun workspaceJson(workspace: AgentWorkspace): JSONObject = JSONObject()
        .put("workspace_id", workspace.workspaceId)
        .put("root_path", workspace.rootPath)
        .put("repository_root", workspace.repositoryRoot ?: JSONObject.NULL)
        .put("base_ref", workspace.baseRef ?: JSONObject.NULL)
        .put("base_sha", workspace.baseSha ?: JSONObject.NULL)
        .put("branch", workspace.branch ?: JSONObject.NULL)
        .put("worktree_path", workspace.worktreePath ?: JSONObject.NULL)
        .put("effective_path", workspace.effectivePath)
        .put("shared_original_workspace", workspace.sharedOriginalWorkspace)
        .put("dirty_baseline", workspace.dirtyBaseline.porcelain)
        .put("dirty", workspace.dirtyBaseline.isDirty)

    private fun changeSetJson(changeSet: AgentGitChangeSet): JSONArray =
        JSONArray().also { files ->
            changeSet.files.forEach { file ->
                files.put(
                    JSONObject()
                        .put("old_path", file.oldPath ?: JSONObject.NULL)
                        .put("new_path", file.newPath ?: JSONObject.NULL)
                        .put("status", file.status.name.lowercase())
                        .put("hunks", JSONArray().also { hunks ->
                            file.hunks.forEach { hunk ->
                                hunks.put(
                                    JSONObject()
                                        .put("id", hunk.id)
                                        .put("old_start", hunk.oldStart)
                                        .put("old_count", hunk.oldCount)
                                        .put("new_start", hunk.newStart)
                                        .put("new_count", hunk.newCount)
                                        .put("heading", hunk.heading)
                                        .put("lines", JSONArray().also { lines ->
                                            hunk.lines.forEach { line ->
                                                lines.put(
                                                    JSONObject()
                                                        .put("kind", line.kind.name.lowercase())
                                                        .put("content", line.content)
                                                        .put("old_line", line.oldLine ?: JSONObject.NULL)
                                                        .put("new_line", line.newLine ?: JSONObject.NULL)
                                                )
                                            }
                                        })
                                )
                            }
                        })
                )
            }
        }

    private fun error(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("message", message)
        .toString()
}
