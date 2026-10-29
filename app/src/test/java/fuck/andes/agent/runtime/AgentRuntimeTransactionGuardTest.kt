package fuck.andes.agent.runtime

import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeTransactionGuardTest {
    @Test
    fun authorizationUsesTheUidCapturedBeforeClearCallingIdentity() {
        var cleared = false
        var uidAfterClear = -1
        val captured = withCapturedBinderCaller(
            readUid = { if (cleared) SELF_UID else ATTACKER_UID },
            readPid = { if (cleared) 1 else ATTACKER_PID },
            clearCallingIdentity = {
                cleared = true
                7L
            },
            restoreCallingIdentity = { token ->
                assertEquals(7L, token)
                cleared = false
            },
        ) { snapshot ->
            uidAfterClear = if (cleared) SELF_UID else ATTACKER_UID
            AgentRuntimeCallerPolicy.decide(
                callingUid = snapshot.uid,
                callingPid = snapshot.pid,
                selfUid = SELF_UID,
                modulePackage = "fuck.andes",
                packages = null,
                pins = emptyMap(),
            ).allowed
        }

        assertFalse(cleared)
        assertEquals(SELF_UID, uidAfterClear)
        assertEquals(ATTACKER_UID, captured.snapshot.uid)
        assertEquals(ATTACKER_PID, captured.snapshot.pid)
        assertFalse(captured.value)
    }

    @Test
    fun capabilityTransactionsRequireAuthorizationAndMetaTransactionsDoNot() {
        assertTrue(requiresBinderCallerAuthorization(IBinder.FIRST_CALL_TRANSACTION))
        assertTrue(requiresBinderCallerAuthorization(IBinder.LAST_CALL_TRANSACTION))
        assertFalse(requiresBinderCallerAuthorization(IBinder.INTERFACE_TRANSACTION))
        assertFalse(requiresBinderCallerAuthorization(IBinder.PING_TRANSACTION))
        assertFalse(requiresBinderCallerAuthorization(IBinder.DUMP_TRANSACTION))
        assertFalse(requiresBinderCallerAuthorization(0))
    }

    @Test
    fun runtimeBinderEntriesCaptureCallingIdentityInsideTheTransactionGuard() {
        val sources = runtimeSources()
        val guard = sources.single { it.name == "AgentRuntimeTransactionGuard.kt" }.readText()
        val service = sources.single { it.name == "AgentRuntimeService.kt" }.readText()
        val capture = guard.substringAfter("fun <T> withCapturedBinderCaller")
        val onTransact = guard.substringAfter("override fun onTransact")

        assertTrue(capture.indexOf("readUid()") in 0 until capture.indexOf("clearCallingIdentity()"))
        assertTrue(onTransact.contains("Binder.getCallingUid()"))
        assertTrue(onTransact.contains("Binder.getCallingPid()"))
        assertTrue(onTransact.contains("withCapturedBinderCaller"))
        val uidAt = onTransact.indexOf("Binder.getCallingUid()")
        assertTrue(uidAt >= 0)
        assertTrue(onTransact.indexOf("delegate.transact", uidAt) > uidAt)
        assertFalse(service.contains("getCallingUid"))
        assertFalse(service.contains("clearCallingIdentity"))
        assertFalse(service.contains("senderUid"))
        assertFalse(service.contains("AGENT_RUNTIME_ENTRY_PACKAGES"))

        val onBind = service.substringAfter("override fun onBind")
            .substringBefore("override fun onStartCommand")
        assertTrue(onBind.contains("transactionGuard"))
        assertFalse(onBind.contains("sendingUid"))

        val offenders = sources.filter { file ->
            val text = file.readText()
            val isBinderEntry = text.contains("override fun onBind(") ||
                text.contains("override fun onTransact(")
            isBinderEntry && "AgentRuntimeTransactionGuard" !in text
        }
        assertEquals(emptyList<String>(), offenders.map { it.name })
    }

    private fun runtimeSources(): List<java.io.File> {
        val service = sourceFile(
            "src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeService.kt",
        )
        val directory = service.parentFile ?: error("Runtime source directory is missing")
        return directory.listFiles { file -> file.extension == "kt" }?.toList().orEmpty()
    }

    private companion object {
        const val SELF_UID = 10_123
        const val ATTACKER_UID = 10_999
        const val ATTACKER_PID = 50
    }
}
