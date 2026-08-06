package fuck.andes.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    /** 视觉模型分流的 Provider（默认跟随主模型 Provider）。 */
    val visionProviderId: String? = null,
    /** 视觉模型分流的 Model（用于处理截图/图像输入）。 */
    val visionModelId: String? = null,
    /** 自动路由：主模型不支持视觉时，截图自动交给视觉模型处理后回注主会话。 */
    val autoVisionRouting: Boolean = true,
    val memoryEnabled: Boolean = true,
)
