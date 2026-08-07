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
        var now = 100L
        val session = AgentGoalSession(clock = { now })
        begin(session)

        val firstDecision = session.completionDecision()
        assertTrue(firstDecision is AgentGoalSession.CompletionDecision.Continue)

        evidence(session, "build", "passed")
        assertTrue(session.completionDecision() is AgentGoalSession.CompletionDecision.Continue)

        evidence(session, "tests", "passed")
        assertEquals(AgentGoalSession.CompletionDecision.Allow, session.completionDecision())
        now += 1
    }

    @Test
    fun failedEvidenceMustBeSupersededBeforeCompletion() {
        val session = AgentGoalSession(clock = { 100L })
        begin(session)
        evidence(session, "build", "passed")
        evidence(session, "tests", "failed")

        val failed = session.completionDecision()
        assertTrue(failed is AgentGoalSession.CompletionDecision.Continue)

        evidence(session, "tests", "passed", summary = "rerun green")
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
        status: String,
        summary: String = "verified",
    ) {
        val result = session.execute(
            AgentModelClient.ToolCall(
                id = "evidence-$criterionId-$status",
                name = AgentGoalSession.TOOL_EVIDENCE,
                argumentsJson = JSONObject()
                    .put("criterion_id", criterionId)
                    .put("status", status)
                    .put("summary", summary)
                    .put("source", "test")
                    .toString(),
            )
        ) ?: error("goal evidence was not handled")
        assertTrue(JSONObject(result.content).getBoolean("ok"))
    }
}
