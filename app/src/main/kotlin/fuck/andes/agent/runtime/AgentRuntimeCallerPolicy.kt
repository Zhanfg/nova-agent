package fuck.andes.agent.runtime

import android.content.pm.PackageManager

internal const val AGENT_RUNTIME_BIND_PERMISSION = "fuck.andes.permission.BIND_AGENT_RUNTIME"

/**
 * 包名不能证明调用者身份。只有 [PackageManager.checkSignatures] 的
 * [PackageManager.SIGNATURE_MATCH] 可以放行其他 UID；该结果覆盖该 UID 下的全部分包。
 */
internal fun runtimeCallerSignatureMatches(signatureCompareResult: Int): Boolean =
    signatureCompareResult == PackageManager.SIGNATURE_MATCH

/**
 * 单次 Binder transaction 的调用者判断。
 *
 * 这里不保存 onBind 时的 UID。调用方必须传入这一次 [android.os.Binder.getCallingUid]，
 * 或 Messenger 在同一次 transaction 内写进 [android.os.Message.sendingUid] 的值。
 * [android.os.Binder.clearCallingIdentity] 之后的 UID 不能拿来授权。
 */
internal class AgentRuntimeCallerPolicy(
    private val myUid: Int,
    private val otherUidAllowed: (callingUid: Int) -> Boolean,
) {
    fun isAllowed(callingUid: Int): Boolean {
        if (callingUid < 0) return false
        if (callingUid == myUid) return true
        return otherUidAllowed(callingUid)
    }
}
