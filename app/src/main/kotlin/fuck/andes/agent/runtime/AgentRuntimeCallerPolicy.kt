package fuck.andes.agent.runtime

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
