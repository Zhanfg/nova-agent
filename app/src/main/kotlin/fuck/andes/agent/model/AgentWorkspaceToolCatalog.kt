package fuck.andes.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Codex-style project/worktree/Git tools. Execution is provided by AgentWorkspaceTools. */
internal object AgentWorkspaceToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(function("workspace_inspect", "检查本地项目或 Git 仓库，返回稳定 workspace_id、仓库根目录、HEAD、branch 与 dirty baseline。", obj(
            "path" to string("要检查的本地目录绝对路径"),
        ), required = listOf("path")))
        tools.put(function("workspace_create_worktree", "从已检查的 Git workspace 创建隔离 branch + worktree，供并行 Agent 安全写代码。", obj(
            "workspace_id" to string("源 workspace_id"),
            "task_id" to string("任务标识，用于默认 branch/worktree 名"),
            "base_ref" to string("可选 base ref；默认使用检查时的 HEAD"),
            "branch" to string("可选显式 branch 名"),
        ), required = listOf("workspace_id", "task_id")))
        tools.put(function("workspace_remove_worktree", "删除隔离 worktree；默认拒绝含未提交修改的 worktree。", obj(
            "workspace_id" to string("隔离 worktree 的 workspace_id"),
            "force" to boolean("是否强制删除脏 worktree；默认 false"),
        ), required = listOf("workspace_id")))
        tools.put(function("git_status", "读取 workspace 的 Git branch/working tree 状态。", workspaceIdParams()))
        tools.put(function("git_diff", "读取 workspace 的 Git diff。可选择 staged diff，并返回原始 diff 与结构化 file/hunk/line review 数据。", obj(
            "workspace_id" to string("workspace_id"),
            "staged" to boolean("读取 staged/cached diff；默认 false"),
        ), required = listOf("workspace_id")))
        tools.put(function("git_log", "读取 workspace 最近 Git commit。", obj(
            "workspace_id" to string("workspace_id"),
            "max_count" to integer("最多返回多少条 commit，1-200"),
        ), required = listOf("workspace_id")))
        tools.put(function("project_instructions", "按 repository root → 当前目录层级读取 AGENTS.md，并返回可注入模型的项目指令。", obj(
            "workspace_id" to string("workspace_id"),
            "working_directory" to string("可选工作目录；默认 workspace effective path"),
        ), required = listOf("workspace_id")))
    }

    private fun workspaceIdParams(): JSONObject = obj(
        "workspace_id" to string("workspace_id"),
    ).also { it.put("required", JSONArray().put("workspace_id")) }

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String> = emptyList(),
    ): JSONObject {
        val parameters = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("additionalProperties", false)
        if (required.isNotEmpty()) parameters.put("required", JSONArray(required))
        return AgentToolSchema.function(name, description, parameters)
    }

    private fun obj(vararg values: Pair<String, JSONObject>): JSONObject =
        JSONObject().also { json -> values.forEach { (key, value) -> json.put(key, value) } }

    private fun string(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    private fun boolean(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)

    private fun integer(description: String): JSONObject =
        JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 200).put("description", description)
}
