package fuck.andes.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Model(
    val id: String,
    val modelId: String,
    val displayName: String,
    val ownedBy: String? = null,
    val isEnabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val sortOrder: Int = 0,
    val contextWindow: Int? = null,
    val inputModalities: List<String> = listOf(TEXT_MODALITY),
    val outputModalities: List<String> = listOf(TEXT_MODALITY),
    val attachment: Boolean? = null,
    /**
     * 视觉能力实测结果（null=未检测，true=实测支持，false=实测不支持）。
     * 实测结果优先于元数据声明：实测为 false 时即使 inputModalities 声称支持视觉也不附加图片，
     * 避免"不支持图片识别的模型被当作支持视觉使用"。
     */
    val visionVerified: Boolean? = null,
    val toolCall: Boolean? = null,
    val reasoning: Boolean? = null,
    val reasoningCapabilities: ModelReasoningCapabilities? = null,
    val structuredOutput: Boolean? = null,
    val supportsTemperature: Boolean? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
    val source: ModelSource = ModelSource.MANUAL,
    val createdAt: Long = System.currentTimeMillis()
) {
    val supportsVision: Boolean
        get() = when {
            visionVerified == false -> false
            attachment == true || inputModalities.any { it.equals(IMAGE_MODALITY, ignoreCase = true) } -> true
            visionVerified == true -> true
            else -> false
        }

    val supportsTools: Boolean
        get() = toolCall == true

    val supportsReasoning: Boolean
        get() = reasoning == true

    companion object {
        const val TEXT_MODALITY = "text"
        const val IMAGE_MODALITY = "image"
    }
}

@Serializable
enum class ModelSource {
    MANUAL,
    REMOTE,
    CATALOG,
}
