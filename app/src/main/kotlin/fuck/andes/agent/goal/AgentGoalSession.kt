package fuck.andes.agent.goal

import fuck.andes.agent.model.AgentModelClient
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Mutable per-run Goal mode state shared by the Goal tools and AgentLoop completion gate. */
internal class AgentGoalSession(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface CompletionDecision {
        data object Allow : CompletionDecision
        data class Continue(val feedback: String) : CompletionDecision
        data class Fail(val message: String) : CompletionDecision
    }

    private data class ExecutedToolEvidence(
        val toolCallId: String,
        val toolName: String,
        val ok: Boolean,
    )

    private var tracker: AgentGoalTracker? = null
    private var goalRoundCount: Int = 0
    private val executedTools = linkedMapOf<String, ExecutedToolEvidence>()

    fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult? {
        val args = runCatching { JSONObject(toolCall.argumentsJson.ifBlank { "{}" }) }
            .getOrElse {
                return AgentModelClient.ToolResult(error("INVALID_ARGUMENT", "Goal 工具参数不是有效 JSON"))
            }
        val payload = when (toolCall.name) {
            TOOL_BEGIN -> begin(args)
            TOOL_EVIDENCE -> reportEvidence(args)
            TOOL_STATUS -> status()
            TOOL_BLOCK -> block(args)
            else -> return null
        }
        return AgentModelClient.ToolResult(payload)
    }

    /**
     * Records provenance only for normal tools executed after Goal activation. A result is eligible
     * as verification evidence only when it exposes an explicit structured `ok` boolean. This
     * prevents the model from satisfying a criterion by inventing a `passed` status with no tool
     * execution behind it.
     */
    fun recordToolExecution(
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ) {
        if (tracker == null || toolCall.id.isBlank()) return
        val ok = structuredOk(result.content) ?: return
        while (executedTools.size >= MAX_TOOL_EVIDENCE_RECORDS) {
            val oldest = executedTools.keys.firstOrNull() ?: break
            executedTools.remove(oldest)
        }
        executedTools[toolCall.id] = ExecutedToolEvidence(
            toolCallId = toolCall.id,
            toolName = toolCall.name,
            ok = ok,
        )
    }

    fun onRound(): CompletionDecision {
        val active = tracker ?: return CompletionDecision.Allow
        goalRoundCount += 1
        val previous = active.usage()
        active.updateUsage(
            steps = maxOf(previous.steps, goalRoundCount),
            tokens = previous.tokens,
            costMicros = previous.costMicros,
        )
        return stateToDecision(active.evaluate(clock()))
    }

    fun completionDecision(): CompletionDecision {
        val active = tracker ?: return CompletionDecision.Allow
        return stateToDecision(active.evaluate(clock()))
    }

    fun isActive(): Boolean = tracker != null

    private fun begin(args: JSONObject): String {
        if (tracker != null) {
            return error("GOAL_ALREADY_ACTIVE", "本轮已经存在 Goal；请继续验证现有 Goal，而不是覆盖它")
        }
        val goalText = args.optString("goal").trim()
        val verificationPlan = args.optString("verification_plan").trim()
        val criteriaJson = args.optJSONArray("success_criteria")
            ?: return error("INVALID_ARGUMENT", "success_criteria 必须是非空数组")
        if (goalText.isBlank() || verificationPlan.isBlank() || criteriaJson.length() == 0) {
            return error("INVALID_ARGUMENT", "goal、success_criteria、verification_plan 不能为空")
        }

        val criteria = buildList {
            repeat(criteriaJson.length()) { index ->
                val item = criteriaJson.optJSONObject(index)
                    ?: return error("INVALID_ARGUMENT", "success_criteria[$index] 必须是对象")
                val id = item.optString("id").trim()
                val description = item.optString("description").trim()
                if (id.isBlank() || description.isBlank()) {
                    return error("INVALID_ARGUMENT", "success_criteria[$index] 缺少 id/description")
                }
                add(AgentGoal.Criterion(id = id, description = description))
            }
        }
        val constraints = args.optJSONArray("constraints").toStringList()
        val budget = runCatching {
            AgentGoal.Budget(
                maxSteps = args.optInt("max_steps", 200),
                maxDurationMillis = args.optLong("max_duration_ms", 60 * 60 * 1000L),
            )
        }.getOrElse { failure ->
            return error("INVALID_ARGUMENT", failure.message ?: "Goal budget 无效")
        }
        val goal = runCatching {
            AgentGoal(
                goalId = args.optString("goal_id").trim().ifBlank { "goal-${UUID.randomUUID()}" },
                goal = goalText,
                successCriteria = criteria,
                constraints = constraints,
                verificationPlan = verificationPlan,
                budget = budget,
            )
        }.getOrElse { failure ->
            return error("INVALID_ARGUMENT", failure.message ?: "Goal 定义无效")
        }
        goalRoundCount = 0
        executedTools.clear()
        tracker = AgentGoalTracker(goal = goal, startedAtMillis = clock())
        return status()
    }

    private fun reportEvidence(args: JSONObject): String {
        val active = tracker ?: return error("GOAL_NOT_ACTIVE", "请先调用 goal_begin")
        val criterionId = args.optString("criterion_id").trim()
        val summary = args.optString("summary").trim()
        val toolCallId = args.optString("tool_call_id").trim()
        if (criterionId.isBlank() || summary.isBlank() || toolCallId.isBlank()) {
            return error("INVALID_ARGUMENT", "criterion_id、tool_call_id、summary 不能为空")
        }
        val executed = executedTools[toolCallId]
            ?: return error(
                "GOAL_EVIDENCE_NOT_EXECUTED",
                "tool_call_id=$toolCallId 没有可验证的本轮工具执行结果；不能凭空记录 evidence",
            )
        val derivedStatus = if (executed.ok) {
            AgentGoalTracker.CriterionStatus.PASSED
        } else {
            AgentGoalTracker.CriterionStatus.FAILED
        }
        val failure = runCatching {
            active.recordEvidence(
                AgentGoalTracker.Evidence(
                    criterionId = criterionId,
                    status = derivedStatus,
                    summary = summary,
                    source = "${executed.toolName}:${executed.toolCallId}",
                    capturedAtMillis = clock(),
                )
            )
        }.exceptionOrNull()
        if (failure != null) {
            return error("INVALID_ARGUMENT", failure.message ?: "无法记录 Goal evidence")
        }
        return status()
    }

    private fun block(args: JSONObject): String {
        val active = tracker ?: return error("GOAL_NOT_ACTIVE", "请先调用 goal_begin")
        val reason = args.optString("reason").trim()
        if (reason.isBlank()) return error("INVALID_ARGUMENT", "reason 不能为空")
        return runCatching { stateJson(active.block(reason)) }
            .getOrElse { failure -> error("GOAL_TERMINATED", failure.message ?: "Goal 已终止") }
    }

    private fun status(): String {
        val active = tracker ?: return JSONObject()
            .put("ok", true)
            .put("active", false)
            .toString()
        return JSONObject()
            .put("ok", true)
            .put("active", true)
            .put("goal_id", active.goal.goalId)
            .put("goal", active.goal.goal)
            .put("state", stateJson(active.evaluate(clock())))
            .put("criteria", JSONArray().also { array ->
                val evidence = active.evidence().associateBy { it.criterionId }
                active.goal.successCriteria.forEach { criterion ->
                    val latest = evidence[criterion.id]
                    array.put(
                        JSONObject()
                            .put("id", criterion.id)
                            .put("description", criterion.description)
                            .put("status", latest?.status?.name?.lowercase() ?: "pending")
                            .put("summary", latest?.summary ?: JSONObject.NULL)
                            .put("source", latest?.source ?: JSONObject.NULL)
                    )
                }
            })
            .toString()
    }

    private fun stateToDecision(state: AgentGoalTracker.State): CompletionDecision = when (state) {
        AgentGoalTracker.State.Running -> {
            val active = requireNotNull(tracker)
            val evidence = active.evidence().associateBy { it.criterionId }
            val pending = active.goal.successCriteria.filter {
                evidence[it.id]?.status != AgentGoalTracker.CriterionStatus.PASSED
            }
            CompletionDecision.Continue(
                buildString {
                    append("Goal mode 尚未满足完成门禁。不要宣称任务完成。\n")
                    append("仍需通过验证的 success criteria：\n")
                    pending.forEach { criterion ->
                        val latest = evidence[criterion.id]
                        append("- ${criterion.id}: ${criterion.description}")
                        if (latest?.status == AgentGoalTracker.CriterionStatus.FAILED) {
                            append("（最近验证失败：${latest.summary}）")
                        }
                        append('\n')
                    }
                    append(
                        "请继续执行修复/验证；goal_report_evidence 必须引用一个已经真实执行且返回结构化 ok 的 tool_call_id。"
                    )
                }
            )
        }
        is AgentGoalTracker.State.VerificationFailed -> CompletionDecision.Continue(
            "Goal verification 仍有失败项：${state.failedCriteria.joinToString()}。" +
                "请修复后重新执行验证工具，并用新的 tool_call_id 调用 goal_report_evidence，不能直接结束。"
        )
        is AgentGoalTracker.State.Succeeded -> CompletionDecision.Allow
        is AgentGoalTracker.State.BudgetExceeded -> CompletionDecision.Fail(
            "Goal mode 预算已耗尽：${state.reason}"
        )
        is AgentGoalTracker.State.Blocked -> CompletionDecision.Fail(
            "Goal mode 被阻塞：${state.reason}"
        )
    }

    private fun stateJson(state: AgentGoalTracker.State): String = when (state) {
        AgentGoalTracker.State.Running -> "running"
        is AgentGoalTracker.State.Succeeded -> "succeeded"
        is AgentGoalTracker.State.VerificationFailed -> "verification_failed"
        is AgentGoalTracker.State.BudgetExceeded -> "budget_exceeded"
        is AgentGoalTracker.State.Blocked -> "blocked"
    }

    private fun structuredOk(content: String): Boolean? = runCatching {
        val json = JSONObject(content)
        if (!json.has("ok") || json.isNull("ok")) null else json.getBoolean("ok")
    }.getOrNull()

    private fun JSONArray?.toStringList(): List<String> = buildList {
        val array = this@toStringList ?: return@buildList
        repeat(array.length()) { index ->
            array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun error(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("message", message)
        .toString()

    companion object {
        const val TOOL_BEGIN = "goal_begin"
        const val TOOL_EVIDENCE = "goal_report_evidence"
        const val TOOL_STATUS = "goal_status"
        const val TOOL_BLOCK = "goal_block"
        private const val MAX_TOOL_EVIDENCE_RECORDS = 256
    }
}

/** Delegates normal tools unchanged, records their verification provenance, and intercepts Goal tools. */
internal class AgentGoalToolExecutor(
    private val goalSession: AgentGoalSession,
    private val delegate: AgentModelClient.ToolExecutor,
) : AgentModelClient.ToolExecutor {
    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        goalSession.execute(toolCall)?.let { return it }
        val result = delegate.execute(toolCall)
        goalSession.recordToolExecution(toolCall, result)
        return result
    }
}
