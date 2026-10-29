package fuck.andes.agent.runtime

import fuck.andes.core.ModuleConfig

/**
 * 允许绑定 Agent Runtime 的 OEM 助手签名证书钉扎表。
 *
 * 包名不能作为身份。这里的摘要必须与 `res/values/agent_runtime_security.xml` 中的
 * `agent_runtime_known_signers` 逐项一致，manifest 的 `knownSigner` 权限和每次
 * Binder 事务里的证书校验才是同一套允许列表。
 *
 * 当前两个值是占位符，不是 HeyTap 或小米的真实签名证书。它们是合法的小写 SHA-256
 * 十六进制，因此 manifest 可以安装，但不会匹配任何真实 APK。在替换成真实摘要之前，
 * 小布助手和小爱同学无法绑定，也无法通过事务级校验。
 *
 * 替换方法：对目标 APK 执行 `apksigner verify --print-certs <apk>`，取
 * “Signer #1 certificate SHA-256 digest”，去掉冒号并改为小写，同时改本常量和
 * `agent_runtime_known_signers`。证书轮换后把仍受信任的当前与历史证书摘要都放进集合。
 */
internal object AgentRuntimeCallerAllowlist {
    const val PERMISSION = "fuck.andes.permission.BIND_AGENT_RUNTIME"
    const val MODULE_PACKAGE = "fuck.andes"

    const val PLACEHOLDER_BREENO_CERT_SHA256 =
        "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee0001"
    const val PLACEHOLDER_XIAOAI_CERT_SHA256 =
        "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee0002"

    val pins: Map<String, Set<String>> = mapOf(
        ModuleConfig.BREENO_PACKAGE to setOf(PLACEHOLDER_BREENO_CERT_SHA256),
        ModuleConfig.XIAOAI_PACKAGE to setOf(PLACEHOLDER_XIAOAI_CERT_SHA256),
    )
}
