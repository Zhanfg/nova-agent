package fuck.andes.agent.workspace

import android.content.Context
import fuck.andes.agent.model.AgentModelClient

/** Resolves a persisted workspace and injects its engineering context into one Runtime request. */
internal object AgentWorkspaceContextBuilder {
    fun augment(
        context: Context,
        workspaceId: String?,
        config: AgentModelClient.ModelConfig,
    ): AgentModelClient.ModelConfig {
        val id = workspaceId?.trim().takeIf { !it.isNullOrEmpty() } ?: return config
        val workspace = AgentWorkspaceRuntimeRegistry.get(context).get(id)
            ?: error("Workspace $id 不存在或尚未恢复；请重新选择项目")
        val validation = AgentWorkspacePathPolicy.validateReadableWorkspace(workspace.effectivePath)
        if (!validation.ok) {
            error(validation.error ?: "Workspace $id 当前不可访问")
        }
        return augment(workspace, config)
    }

    internal fun augment(
        workspace: AgentWorkspace,
        config: AgentModelClient.ModelConfig,
    ): AgentModelClient.ModelConfig {
        val instructionRoot = workspace.repositoryRoot ?: workspace.rootPath
        val instructions = AgentProjectInstructions.load(
            repositoryRoot = instructionRoot,
            workingDirectory = workspace.effectivePath,
        )
        instructions.error?.let { error("无法加载 Workspace 项目指令：$it") }

        val block = buildString {
            appendLine("<nova_workspace_context>")
            appendLine("workspace_id: ${workspace.workspaceId}")
            appendLine("working_directory: ${workspace.effectivePath}")
            workspace.repositoryRoot?.let { appendLine("repository_root: $it") }
            workspace.branch?.let { appendLine("branch: $it") }
            workspace.baseRef?.let { appendLine("base_ref: $it") }
            workspace.baseSha?.let { appendLine("base_sha: $it") }
            if (workspace.dirtyBaseline.isDirty) {
                appendLine("baseline_dirty: true")
            }
            val rendered = instructions.renderForModel()
            if (rendered.isNotBlank()) {
                appendLine()
                appendLine(rendered)
            }
            append("</nova_workspace_context>")
        }
        val existing = config.systemPrompt.trim()
        return config.copy(
            systemPrompt = if (existing.isBlank()) block else "$existing\n\n$block",
        )
    }
}
