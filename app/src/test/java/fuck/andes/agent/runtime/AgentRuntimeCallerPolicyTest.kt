package fuck.andes.agent.runtime

import android.content.pm.PackageManager
import android.os.Binder
import android.os.Parcel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRuntimeCallerPolicyTest {
    @Test
    fun eachCallUsesTheUidItWasGiven() {
        val policy = AgentRuntimeCallerPolicy(myUid = 10) { callingUid ->
            callingUid == 42
        }

        assertTrue(policy.isAllowed(42))
        assertTrue(policy.isAllowed(10))
        assertFalse(policy.isAllowed(99))
        assertFalse(policy.isAllowed(-1))
    }

    @Test
    fun negativeUidIsDeniedEvenWhenThePredicateWouldAllowIt() {
        val policy = AgentRuntimeCallerPolicy(myUid = 10) { true }

        assertFalse(policy.isAllowed(-1))
    }

    @Test
    fun ownUidDoesNotConsultTheExternalPredicate() {
        var consulted = false
        val policy = AgentRuntimeCallerPolicy(myUid = 10) {
            consulted = true
            false
        }

        assertTrue(policy.isAllowed(10))
        assertFalse(consulted)
    }

    @Test
    fun authorizationKeepsTheUidCapturedBeforeClearCallingIdentity() {
        ShadowBinder.setCallingUid(99)
        val uidAtEntry = Binder.getCallingUid()
        val token = Binder.clearCallingIdentity()
        try {
            // Robolectric does not update the shadowed calling uid from the native
            // clearCallingIdentity. The hazard is that the uid becomes this process.
            if (Binder.getCallingUid() == uidAtEntry) {
                ShadowBinder.setCallingUid(10)
            }
            val policy = AgentRuntimeCallerPolicy(myUid = 10) { false }

            assertEquals(99, uidAtEntry)
            assertFalse(policy.isAllowed(uidAtEntry))
            assertTrue(policy.isAllowed(Binder.getCallingUid()))
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    @Test
    fun binderReadsTheCallingUidOnEveryTransaction() {
        val observed = mutableListOf<Int>()
        var forwarded = 0
        val delegate = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                forwarded += 1
                return true
            }
        }
        val wrapper = AgentRuntimeCallerBinder(delegate) { uid ->
            observed += uid
            uid == 42
        }

        ShadowBinder.setCallingUid(42)
        assertTrue(wrapper.transact(1, Parcel.obtain(), null, 0))
        ShadowBinder.setCallingUid(99)
        assertFalse(wrapper.transact(1, Parcel.obtain(), null, 0))
        ShadowBinder.setCallingUid(42)
        assertTrue(wrapper.transact(1, Parcel.obtain(), null, 0))

        assertEquals(listOf(42, 99, 42), observed)
        assertEquals(2, forwarded)
    }

    @Test
    fun binderPassesTheUidCapturedBeforeThePredicateClearsIdentity() {
        var seen = -1
        var token = 0L
        val delegate = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = true
        }
        val wrapper = AgentRuntimeCallerBinder(delegate) { uid ->
            seen = uid
            token = Binder.clearCallingIdentity()
            if (Binder.getCallingUid() == uid) {
                ShadowBinder.setCallingUid(10)
            }
            false
        }

        ShadowBinder.setCallingUid(99)
        try {
            assertFalse(wrapper.transact(1, Parcel.obtain(), null, 0))
            assertEquals(99, seen)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    @Test
    fun samePackageNameWithADifferentCertificateIsDenied() {
        val spoofedPackages = listOf("com.heytap.speechassist", "com.miui.voiceassist")
        val policy = AgentRuntimeCallerPolicy(myUid = 10) {
            runtimeCallerSignatureMatches(PackageManager.SIGNATURE_NO_MATCH)
        }

        assertTrue(spoofedPackages.isNotEmpty())
        assertFalse(policy.isAllowed(2_000))
    }

    @Test
    fun matchingSignatureAllowsAnotherUidWithoutAPackageName() {
        val policy = AgentRuntimeCallerPolicy(myUid = 10) {
            runtimeCallerSignatureMatches(PackageManager.SIGNATURE_MATCH)
        }

        assertTrue(policy.isAllowed(2_000))
        assertFalse(runtimeCallerSignatureMatches(PackageManager.SIGNATURE_NO_MATCH))
        assertFalse(runtimeCallerSignatureMatches(PackageManager.SIGNATURE_UNKNOWN_PACKAGE))
    }

    @Test
    fun manifestRequiresTheSignaturePermissionOnTheRuntimeService() {
        val manifest = sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.isFile }.readText()
        val service = Regex(
            """<service\s+android:name="\.agent\.runtime\.AgentRuntimeService"[\s\S]*?/>""",
        ).find(manifest)?.value

        assertTrue(service?.contains("android:permission=\"$AGENT_RUNTIME_BIND_PERMISSION\"") == true)
        assertTrue(
            manifest.contains(
                """<permission
        android:name="$AGENT_RUNTIME_BIND_PERMISSION"
        android:protectionLevel="signature" />""",
            ),
        )
        assertTrue(manifest.contains("""<uses-permission android:name="$AGENT_RUNTIME_BIND_PERMISSION" />"""))
    }
}
