package fuck.andes.agent.goal

import fuck.andes.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGoalSessionTest {
    @Test
    fun ordinaryRunHasNoCompletionGateUntilGoalBegins() {
        val session = AgentGoalSession(clock = { 100L })

        assertEquals(AgentGoalSession.CompletionDecision.Allow, session.completionDecision())
        assertFalse(session.isActive())
    }

    @Test
    fun finalAnswerIsRejectedUntilEveryCriterionHasPassingEvidence() {
        val session = AgentGoalSession(clock = { 100L })
        begin(session)

        assertTrue(session.completionDecision() is AgentGoalSession.CompletionDecision.Continue)

        evidence(session, "build", ok = true)
        assertTrue(session.completionDecision() is AgentGoalSession.CompletionDecision.Continue)

        evidence(session, "tests", ok = true)
        assertEquals(AgentGoalSession.CompletionDecision.Allow, session.completionDecision())
    }

    @Test
    fun modelCannotInventPassedEvidenceWithoutExecutedToolCall() {
        val session = AgentGoalSession(clock = { 100L })
        begin(session)

        val result = session.execute(
            AgentModelClient.ToolCall(
                id = "fake-evidence",
                name = AgentGoalSession.TOOL_EVIDENCE,
                argumentsJson = JSONObject()
                    .put("criterion_id", "build")
                    .put("tool_call_id", "never-executed")
                    .put("summary", "声称构建通过")
                    .toString(),
            )
        ) ?: error("goal evidence was not handled")

        val json = JSONObject(result.content)
        assertFalse(json.getBoolean("ok"))
        assertEquals("GOAL_EVIDENCE_NOT_EXECUTED", json.getString("code"))
        assertTrue(session.completionDecision() is AgentGoalSession.CompletionDecision.Continue)
    }

    @Test
    fun unstructuredToolResultCannotBecomeVerificationEvidence() {
        val session = AgentGoalSession(clock = { 100L })
        begin(session)
        session.recordToolExecution(
            AgentModelClient.ToolCall("tool-plain", "run_command", "{}"),
            AgentModelClient.ToolResult("command finished"),
        )

        val result = session.execute(
            AgentModelClient.ToolCall(
                id = "evidence-build",
                name = AgentGoalSession.TOOL_EVIDENCE,
                argumentsJson = JSONObject()
                    .put("criterion_id", "build")
                    .put("tool_call_id", "tool-plain")
                    .put("summary", "没有结构化结果")
                    .toString(),
            )
        ) ?: error("goal evidence was not handled")

        assertEquals("GOAL_EVIDENCE_NOT_EXECUTED", JSONObject(result.content).getString("code"))
    }

    @Test
    fun failedToolEvidenceMustBeSupersededBySuccessfulRerun() {
        val session = AgentGoalSession(clock = { 100L })
        begin(session)
        evidence(session, "build", ok = true)
        evidence(session, "tests", ok = false)

        assertTrue(session.completionDecision() is AgentGoalSession.CompletionDecision.Continue)

        evidence(session, "tests", ok = true, summary = "rerun green")
        assertEquals(AgentGoalSession.CompletionDecision.Allow, session.completionDecision())
    }

    @Test
    fun normalToolsAreDelegatedWithoutGoalInterference() {
        val session = AgentGoalSession(clock = { 100L })
        var delegated = 0
        val wrapper = AgentGoalToolExecutor(
            goalSession = session,
            delegate = AgentModelClient.ToolExecutor { call ->
                delegated += 1
                AgentModelClient.ToolResult("delegated:${call.name}")
            },
        )

        val result = wrapper.execute(
            AgentModelClient.ToolCall(
                id = "tool-1",
                name = "git_status",
                argumentsJson = "{}",
            )
        )

        assertEquals("delegated:git_status", result.content)
        assertEquals(1, delegated)
        assertFalse(session.isActive())
    }

    @Test
    fun goalToolsNeverReachNormalToolExecutor() {
        val session = AgentGoalSession(clock = { 100L })
        var delegated = 0
        val wrapper = AgentGoalToolExecutor(
            goalSession = session,
            delegate = AgentModelClient.ToolExecutor {
                delegated += 1
                AgentModelClient.ToolResult("unexpected")
            },
        )

        val result = wrapper.execute(beginCall())

        assertTrue(JSONObject(result.content).getBoolean("ok"))
        assertEquals(0, delegated)
        assertTrue(session.isActive())
    }

    @Test
    fun wrapperRecordsSuccessfulNormalToolForLaterEvidenceBinding() {
        val session = AgentGoalSession(clock = { 100L })
        val wrapper = AgentGoalToolExecutor(
            goalSession = session,
            delegate = AgentModelClient.ToolExecutor {
                AgentModelClient.ToolResult(JSONObject().put("ok", true).toString())
            },
        )
        wrapper.execute(beginCall())
        wrapper.execute(AgentModelClient.ToolCall("build-tool", "run_command", "{}"))

        val evidence = wrapper.execute(
            AgentModelClient.ToolCall(
                id = "bind-build",
                name = AgentGoalSession.TOOL_EVIDENCE,
                argumentsJson = JSONObject()
                    .put("criterion_id", "build")
                    .put("tool_call_id", "build-tool")
                    .put("summary", "真实构建命令成功")
                    .toString(),
            )
        )

        assertTrue(JSONObject(evidence.content).getBoolean("ok"))
        assertTrue(session.completionDecision() is AgentGoalSession.CompletionDecision.Continue)
    }

    @Test
    fun stepBudgetStopsUnverifiedContinuation() {
        val session = AgentGoalSession(clock = { 100L })
        val beginArgs = beginArgs().put("max_steps", 2)
        session.execute(
            AgentModelClient.ToolCall("begin", AgentGoalSession.TOOL_BEGIN, beginArgs.toString())
        )

        assertTrue(session.onRound() is AgentGoalSession.CompletionDecision.Continue)
        val second = session.onRound()
        assertTrue(second is AgentGoalSession.CompletionDecision.Fail)
    }

    private fun begin(session: AgentGoalSession) {
        val result = session.execute(beginCall()) ?: error("goal_begin was not handled")
        assertTrue(JSONObject(result.content).getBoolean("ok"))
    }

    private fun beginCall() = AgentModelClient.ToolCall(
        id = "goal-begin",
        name = AgentGoalSession.TOOL_BEGIN,
        argumentsJson = beginArgs().toString(),
    )

    private fun beginArgs() = JSONObject()
        .put("goal_id", "goal-1")
        .put("goal", "修复并验证功能")
        .put(
            "success_criteria",
            JSONArray()
                .put(JSONObject().put("id", "build").put("description", "Debug 构建通过"))
                .put(JSONObject().put("id", "tests").put("description", "相关测试通过")),
        )
        .put("verification_plan", "执行 Debug 构建和单元测试")

    private fun evidence(
        session: AgentGoalSession,
        criterionId: String,
        ok: Boolean,
        summary: String = "verified",
    ) {
        val toolCallId = "verify-$criterionId-${if (ok) "pass" else "fail"}"
        session.recordToolExecution(
            AgentModelClient.ToolCall(
                id = toolCallId,
                name = "run_command",
                argumentsJson = JSONObject().put("command", "verify-$criterionId").toString(),
            ),
            AgentModelClient.ToolResult(JSONObject().put("ok", ok).toString()),
        )
        val result = session.execute(
            AgentModelClient.ToolCall(
                id = "evidence-$criterionId-${if (ok) "pass" else "fail"}",
                name = AgentGoalSession.TOOL_EVIDENCE,
                argumentsJson = JSONObject()
                    .put("criterion_id", criterionId)
                    .put("tool_call_id", toolCallId)
                    .put("summary", summary)
                    .toString(),
            )
        ) ?: error("goal evidence was not handled")
        assertTrue(JSONObject(result.content).getBoolean("ok"))
    }
}
