package fuck.andes.agent.model

import fuck.andes.agent.runtime.AgentRunController
import org.json.JSONArray

/**
 * 视觉路由：主模型不支持图像输入时，把工具返回的观察截图交给专用视觉模型处理，
 * 生成文字描述后回注主会话，让文本模型仍能"看见"屏幕内容。
 */
internal fun interface VisionRouter {
    fun describe(images: List<AgentModelClient.ModelImage>, toolNames: String): String
}

internal class ProviderVisionRouter(
    private val visionConfig: AgentModelClient.ModelConfig,
) : VisionRouter {

    override fun describe(
        images: List<AgentModelClient.ModelImage>,
        toolNames: String,
    ): String {
        if (images.isEmpty()) return "（无图片可描述）"
        val client = ProviderClientFactory.getClient(visionConfig)
        val message = AgentConversationCodec.userMessage(
            text = "以下截图来自工具（$toolNames），是对设备屏幕的观察结果。请用中文详细描述画面中的关键信息：" +
                "可见文字、控件名称、弹窗、内容与布局要点，供不具备视觉能力的文本模型继续执行任务。" +
                "不要编造画面中没有的内容。",
            images = images,
        )
        val messages = JSONArray().put(message)
        return try {
            val response = client.complete(
                request = ProviderRequest(
                    config = visionConfig,
                    messages = messages,
                    tools = JSONArray(),
                ),
                runController = AgentRunController(),
            ) { }
            response.assistantMessage.optString("content").trim()
                .ifBlank { "（视觉模型未返回描述内容）" }
        } catch (throwable: Throwable) {
            "（视觉模型处理截图失败：${throwable.message ?: throwable.javaClass.simpleName}）"
        }
    }
}
