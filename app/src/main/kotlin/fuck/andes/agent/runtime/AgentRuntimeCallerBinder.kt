package fuck.andes.agent.runtime

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import fuck.andes.core.AndroidAgentLogger

/**
 * 在每次 Binder transaction 入口读取当前调用者，再决定是否转发给 Runtime Messenger。
 *
 * 同一个 IBinder 可以转交给另一个 UID。授权不能依赖 onBind 时记下的 UID。
 */
internal class AgentRuntimeCallerBinder(
    private val delegate: IBinder,
    private val isUidAllowed: (callingUid: Int) -> Boolean,
) : Binder() {
    override fun queryLocalInterface(descriptor: String): IInterface? = null

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val callingUid = getCallingUid()
        val callingPid = getCallingPid()
        if (!isUidAllowed(callingUid)) {
            AndroidAgentLogger.warnThrottled("runtime_binder_caller_rejected") {
                "Agent runtime rejected binder transaction uid=$callingUid pid=$callingPid"
            }
            return false
        }
        return delegate.transact(code, data, reply, flags)
    }
}
