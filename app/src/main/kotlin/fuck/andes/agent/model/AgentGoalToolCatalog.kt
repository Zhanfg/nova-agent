package fuck.andes.agent.model

import fuck.andes.agent.goal.AgentGoalSession
import org.json.JSONArray
import org.json.JSONObject

/** Model-visible controls for verified Codex-style Goal mode. */
internal object AgentGoalToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            function(
                AgentGoalSession.TOOL_BEGIN,
                "开启 Goal mode。适用于需要持续执行直到明确验收的工程任务；开启后，在所有 success criteria 都有 passed 证据前不能正常结束。",
                JSONObject()
                    .put("goal_id", string("可选稳定 Goal ID；为空时自动生成"))
                    .put("goal", string("任务最终目标"))
                    .put(
                        "success_criteria",
                        JSONObject()
                            .put("type", "array")
                            .put("minItems", 1)
                            .put(
                                "items",
                                JSONObject()
                                    .put("type", "object")
                                    .put(
                                        "properties",
                                        JSONObject()
                                            .put("id", string("稳定且唯一的 criterion ID"))
                                            .put("description", string("可验证的成功条件")),
                                    )
                                    .put("required", JSONArray().put("id").put("description"))
                                    .put("additionalProperties", false),
                            ),
                    )
                    .put(
                        "constraints",
                        JSONObject()
                            .put("type", "array")
                            .put("items", JSONObject().put("type", "string")),
                    )
                    .put("verification_plan", string("完成后如何验证每一项 success criterion"))
                    .put("max_steps", integer("最多允许的 Agent 轮次/步骤，默认 200", 1, 1000))
                    .put("max_duration_ms", integer("最长 Goal 时长（毫秒），默认 1 小时", 1000, 86_400_000)),
                required = listOf("goal", "success_criteria", "verification_plan"),
            )
        )
        tools.put(
            function(
                AgentGoalSession.TOOL_EVIDENCE,
                "记录某个 success criterion 的验证证据。只有 passed 证据会满足完成门禁；failed 证据必须修复并重新验证。",
                JSONObject()
                    .put("criterion_id", string("goal_begin 中定义的 criterion ID"))
                    .put(
                        "status",
                        JSONObject()
                            .put("type", "string")
                            .put("enum", JSONArray().put("passed").put("failed")),
                    )
                    .put("summary", string("实际验证结果摘要，不得只写计划或猜测"))
                    .put("source", string("证据来源，例如 test、lint、build、git diff、device check")),
                required = listOf("criterion_id", "status", "summary", "source"),
            )
        )
        tools.put(
            function(
                AgentGoalSession.TOOL_STATUS,
                "读取当前 Goal、各 success criterion 的最新证据和完成状态。",
                JSONObject(),
            )
        )
        tools.put(
            function(
                AgentGoalSession.TOOL_BLOCK,
                "当存在无法在当前环境内解决的真实阻塞时终止 Goal，并记录阻塞原因；不要用它替代可执行的验证。",
                JSONObject().put("reason", string("具体、可行动的阻塞原因")),
                required = listOf("reason"),
            )
        )
    }

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

    private fun string(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    private fun integer(
        description: String,
        minimum: Int,
        maximum: Int,
    ): JSONObject = JSONObject()
        .put("type", "integer")
        .put("minimum", minimum)
        .put("maximum", maximum)
        .put("description", description)
}
