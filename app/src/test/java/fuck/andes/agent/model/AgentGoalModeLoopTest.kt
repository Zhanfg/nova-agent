package fuck.andes.agent.model

import fuck.andes.agent.goal.AgentGoalSession
import fuck.andes.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGoalModeLoopTest {
    @Test
    fun prematureFinalAnswerIsFedBackUntilExecutedVerificationEvidencePasses() {
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(
                    toolCall(
                        "goal-start",
                        AgentGoalSession.TOOL_BEGIN,
                        JSONObject()
                            .put("goal_id", "goal-1")
                            .put("goal", "修复并验证项目")
                            .put(
                                "success_criteria",
                                JSONArray()
                                    .put(JSONObject().put("id", "build").put("description", "Debug 构建通过"))
                                    .put(JSONObject().put("id", "tests").put("description", "相关测试通过")),
                            )
                            .put("verification_plan", "运行构建与测试")
                            .toString(),
                    )
                ),
            ),
            // 这轮模型错误地直接宣称完成；Goal gate 必须把它退回继续执行。
            assistant(content = "已经完成，可以结束。", finishReason = "stop"),
            // 第三轮先真实执行验证工具，再把每个 success criterion 绑定到对应 tool_call_id。
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(
                    verificationCall("build-tool", "./gradlew :app:assembleDebug"),
                    evidenceCall("build", "build-tool", "Debug assemble succeeded"),
                    verificationCall("tests-tool", "./gradlew :app:testDebugUnitTest"),
                    evidenceCall("tests", "tests-tool", "unit tests passed"),
                ),
            ),
            assistant(content = "已完成并通过构建和测试验证。", finishReason = "stop"),
        )
        val delegated = mutableListOf<String>()

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "修复项目直到验证通过",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                delegated += call.id
                AgentModelClient.ToolResult(JSONObject().put("ok", true).put("tool", call.name).toString())
            },
            provider = provider,
        )

        assertEquals("已完成并通过构建和测试验证。", result.content)
        assertEquals(listOf("build-tool", "tests-tool"), delegated)
        assertEquals(4, provider.requests.size)

        // 第三次请求必须包含 Loop 自动追加的 Goal 未完成反馈，而不是接受第二轮的空口完成。
        val thirdRequest = provider.requests[2]
        val feedback = (0 until thirdRequest.length())
            .map { thirdRequest.getJSONObject(it) }
            .last { it.optString("role") == "user" }
            .optString("content")
        assertTrue(feedback.contains("Goal mode 尚未满足完成门禁"))
        assertTrue(feedback.contains("build"))
        assertTrue(feedback.contains("tests"))

        // 第四次请求应包含两个真实 verification tool result 和随后绑定的 evidence results。
        val fourthRequest = provider.requests[3].toString()
        assertTrue(fourthRequest.contains("build-tool"))
        assertTrue(fourthRequest.contains("tests-tool"))
        assertTrue(fourthRequest.contains("run_command"))
    }

    private class ScriptedProvider(
        vararg responses: JSONObject,
    ) : AgentProviderClient {
        override val id: String = "goal-scripted"
        override val capabilities: ProviderCapabilities = ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = true,
        )

        private val responses = responses.toList()
        val requests = mutableListOf<JSONArray>()
        private var index = 0

        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse {
            requests += JSONArray(request.messages.toString())
            return ProviderResponse(
                responses.getOrNull(index++) ?: error("缺少 scripted Goal response")
            )
        }
    }

    private fun modelConfig() = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1",
        apiKey = "test-key",
        model = "test-model",
        systemPrompt = "",
        browserTools = false,
        terminalTools = true,
    )

    private fun assistant(
        content: String = "",
        finishReason: String,
        toolCalls: List<JSONObject> = emptyList(),
    ): JSONObject = JSONObject()
        .put("role", "assistant")
        .put("content", content)
        .put("finish_reason", finishReason)
        .also { message ->
            if (toolCalls.isNotEmpty()) message.put("tool_calls", JSONArray(toolCalls))
        }

    private fun verificationCall(
        id: String,
        command: String,
    ) = toolCall(
        id = id,
        name = "run_command",
        arguments = JSONObject().put("command", command).toString(),
    )

    private fun evidenceCall(
        criterionId: String,
        toolCallId: String,
        summary: String,
    ) = toolCall(
        id = "evidence-$criterionId",
        name = AgentGoalSession.TOOL_EVIDENCE,
        arguments = JSONObject()
            .put("criterion_id", criterionId)
            .put("tool_call_id", toolCallId)
            .put("summary", summary)
            .toString(),
    )

    private fun toolCall(
        id: String,
        name: String,
        arguments: String,
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("arguments", arguments),
        )
}
