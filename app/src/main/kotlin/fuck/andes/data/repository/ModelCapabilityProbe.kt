package fuck.andes.data.repository

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import fuck.andes.agent.model.AgentConversationCodec
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.agent.model.ProviderClientFactory
import fuck.andes.agent.model.ProviderRequest
import fuck.andes.agent.runtime.AgentRunController
import fuck.andes.data.model.Model
import fuck.andes.data.model.ProviderSetting
import java.io.ByteArrayOutputStream
import org.json.JSONArray

/**
 * 模型能力实测。
 *
 * 元数据（inputModalities / attachment）可能不准：部分模型在 /models 接口声明支持图像输入，
 * 实际调用时却报错或忽略图片。因此向模型发送一张内置测试图，根据其能否正确读出图内标记文本来
 * 判定真实视觉能力，并把结果写回模型的 [Model.visionVerified]。
 */
internal object ModelCapabilityProbe {

    /** 测试图内的大字标记，模型能读出即视为具备视觉能力。 */
    private const val MARK_TEXT = "ETA-42"
    private const val MARK_ALT = "42"

    internal data class ProbeResult(
        val supported: Boolean,
        val detail: String,
    )

    /**
     * 对指定模型执行一轮视觉实测，并把结果持久化到模型记录。
     * 返回实测结果；网络异常等无法判定时返回 false 且不覆盖已有登记。
     */
    suspend fun probeVision(provider: ProviderSetting, model: Model): ProbeResult {
        val config = RuntimeConfigRepository.buildRuntimeConfig(provider, model)
        val imageData = testImagePng() ?: return ProbeResult(false, "内置测试图生成失败")
        val dataUri = "data:image/png;base64," + Base64.encodeToString(imageData, Base64.NO_WRAP)
        val message = AgentConversationCodec.userMessage(
            text = "这是一张视觉能力测试图，图中有两到四个字符。请只回答图中显示的标记文本，不要做任何其他解释或评论。",
            images = listOf(
                AgentModelClient.ModelImage(
                    reference = dataUri,
                    mimeType = "image/png",
                    bytes = imageData.size,
                    width = 320,
                    height = 96,
                    source = "capability_probe",
                )
            ),
        )
        val messages = JSONArray().put(message)
        val runController = AgentRunController()

        val result = try {
            val response = ProviderClientFactory.getClient(config).complete(
                request = ProviderRequest(
                    config = config,
                    messages = messages,
                    tools = JSONArray(),
                ),
                runController = runController,
            )
            val content = response.assistantMessage.optString("content")
            val normalized = content.uppercase()
            val supported = normalized.contains(MARK_TEXT) || normalized.contains(MARK_ALT)
            ProbeResult(
                supported = supported,
                detail = if (supported) {
                    "实测支持视觉（读出：${content.trim().take(40)}）"
                } else {
                    "未读出测试图标记（响应：${content.trim().take(60).ifBlank { "<空响应>" }}）"
                },
            )
        } catch (throwable: Throwable) {
            ProbeResult(
                supported = false,
                detail = "实测失败：${throwable.message ?: throwable.javaClass.simpleName}",
            )
        }

        // 实测失败（网络/协议异常）不覆盖已有登记结果，避免误标。
        if (result.detail.startsWith("实测失败") && model.visionVerified != null) {
            return result
        }
        if (result.supported != model.visionVerified) {
            ModelRepository.saveModel(
                providerId = provider.id,
                draft = model.copy(visionVerified = result.supported),
            )
        }
        return result
    }

    /** 生成白底黑字的 PNG 测试图（320x96），内容为 [MARK_TEXT]。 */
    private fun testImagePng(): ByteArray? = runCatching {
        val width = 320
        val height = 96
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 56f
            textAlign = Paint.Align.CENTER
        }
        val baseline = (height / 2f) + (paint.textSize / 3f)
        canvas.drawText(MARK_TEXT, width / 2f, baseline, paint)
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }.getOrNull()
}
