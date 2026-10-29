package fuck.andes.agent.runtime

import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/**
 * 在 Binder 事务内部读取调用方，再把事务转给 Messenger。
 *
 * [onBind] 不能缓存 UID：句柄转交后，下一次 transact 的 [Binder.getCallingUid] 属于
 * 真正发起调用的进程。授权使用的 UID/PID 必须在 [Binder.clearCallingIdentity] 之前取走；
 * 清理身份只发生在随后读取本进程才可见的包信息时，并且在进入下游 Messenger 之前恢复，
 * 这样平台仍会把真实调用方写入 [android.os.Message.sendingUid]。
 */
internal object AgentRuntimeBinderCall {
    val captured: ThreadLocal<AgentRuntimeCallerSnapshot?> = ThreadLocal()
}

internal data class CapturedBinderCall<T>(
    val snapshot: AgentRuntimeCallerSnapshot,
    val value: T,
)

internal fun <T> withCapturedBinderCaller(
    readUid: () -> Int,
    readPid: () -> Int,
    clearCallingIdentity: () -> Long,
    restoreCallingIdentity: (Long) -> Unit,
    block: (AgentRuntimeCallerSnapshot) -> T,
): CapturedBinderCall<T> {
    val snapshot = AgentRuntimeCallerSnapshot(uid = readUid(), pid = readPid())
    val token = clearCallingIdentity()
    return try {
        CapturedBinderCall(snapshot, block(snapshot))
    } finally {
        restoreCallingIdentity(token)
    }
}

internal fun requiresBinderCallerAuthorization(code: Int): Boolean =
    code in IBinder.FIRST_CALL_TRANSACTION..IBinder.LAST_CALL_TRANSACTION

internal class AgentRuntimeTransactionGuard(
    private val delegate: IBinder,
    private val authorize: (AgentRuntimeCallerSnapshot) -> Boolean,
) : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (!requiresBinderCallerAuthorization(code)) {
            return delegate.transact(code, data, reply, flags)
        }
        val captured = withCapturedBinderCaller(
            readUid = { Binder.getCallingUid() },
            readPid = { Binder.getCallingPid() },
            clearCallingIdentity = { Binder.clearCallingIdentity() },
            restoreCallingIdentity = { token -> Binder.restoreCallingIdentity(token) },
        ) { snapshot ->
            authorize(snapshot)
        }
        if (!captured.value) {
            throw SecurityException("Agent runtime binder caller is not trusted")
        }
        AgentRuntimeBinderCall.captured.set(captured.snapshot)
        return try {
            delegate.transact(code, data, reply, flags)
        } finally {
            AgentRuntimeBinderCall.captured.remove()
        }
    }
}
