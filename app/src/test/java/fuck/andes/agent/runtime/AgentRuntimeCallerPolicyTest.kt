package fuck.andes.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeCallerPolicyTest {
    private val breeno = "com.heytap.speechassist"
    private val xiaoai = "com.miui.voiceassist"
    private val pinnedBreeno = "ab".repeat(32)
    private val pinnedXiaoAi = "cd".repeat(32)
    private val pins = mapOf(
        breeno to setOf(pinnedBreeno),
        xiaoai to setOf(pinnedXiaoAi.uppercase().chunked(2).joinToString(":")),
    )
    private val selfUid = 10_123
    private val modulePackage = "fuck.andes"

    @Test
    fun transactionUidIsAuthorizedIndependentlyOfAnyEarlierCaller() {
        val trusted = decide(
            callingUid = 10_001,
            packages = listOf(signed(breeno, uid = 10_001, digest = pinnedBreeno)),
        )
        val forwarded = decide(
            callingUid = 10_099,
            packages = listOf(signed("com.evil", uid = 10_099, digest = pinnedBreeno)),
        )

        assertTrue(trusted.allowed)
        assertFalse(forwarded.allowed)
        assertEquals("untrusted_package", forwarded.reason)
    }

    @Test
    fun samePackageNameWithADifferentCertificateIsRejected() {
        val decision = decide(
            callingUid = 10_001,
            packages = listOf(signed(breeno, uid = 10_001, digest = "11".repeat(32))),
        )

        assertFalse(decision.allowed)
        assertEquals(0, decision.userId)
    }

    @Test
    fun sharedUidIsRejectedUnlessEveryPackageMatchesThePin() {
        val mixed = decide(
            callingUid = 10_001,
            packages = listOf(
                signed(breeno, uid = 10_001, digest = pinnedBreeno),
                signed("com.heytap.other", uid = 10_001, digest = pinnedBreeno),
            ),
        )
        val bothPinned = decide(
            callingUid = 10_001,
            packages = listOf(
                signed(breeno, uid = 10_001, digest = pinnedBreeno),
                signed(xiaoai, uid = 10_001, digest = pinnedXiaoAi),
            ),
        )

        assertFalse(mixed.allowed)
        assertTrue(bothPinned.allowed)
        assertEquals("pinned_signer", bothPinned.reason)
    }

    @Test
    fun workProfilePackageDoesNotInheritTheMainUserCertificate() {
        val mainUser = signed(breeno, uid = 10_001, digest = pinnedBreeno)
        val workProfileUid = 10 * AgentRuntimeCallerPolicy.PER_USER_RANGE + 10_001
        val spoofedWorkProfile = CallerPackageIdentity(
            packageName = breeno,
            packageUid = mainUser.packageUid,
            userId = mainUser.userId,
            currentSignerDigests = mainUser.currentSignerDigests,
            historySignerDigests = mainUser.historySignerDigests,
            hasMultipleSigners = false,
        )
        val uncertifiedWorkProfile = signed(
            packageName = breeno,
            uid = workProfileUid,
            digest = "22".repeat(32),
        )
        val certifiedWorkProfile = signed(
            packageName = breeno,
            uid = workProfileUid,
            digest = pinnedBreeno,
        )

        assertTrue(decide(callingUid = mainUser.packageUid, packages = listOf(mainUser)).allowed)
        assertFalse(decide(callingUid = workProfileUid, packages = listOf(spoofedWorkProfile)).allowed)
        assertFalse(decide(callingUid = workProfileUid, packages = listOf(uncertifiedWorkProfile)).allowed)
        assertTrue(decide(callingUid = workProfileUid, packages = listOf(certifiedWorkProfile)).allowed)
        assertEquals(10, certifiedWorkProfile.userId)
    }

    @Test
    fun certificateRotationIsAcceptedOnlyThroughTheSignedHistory() {
        val rotated = CallerPackageIdentity(
            packageName = breeno,
            packageUid = 10_001,
            userId = 0,
            currentSignerDigests = setOf("33".repeat(32)),
            historySignerDigests = setOf(pinnedBreeno, "33".repeat(32)),
            hasMultipleSigners = false,
        )
        val unrelatedHistory = rotated.copy(
            historySignerDigests = setOf("44".repeat(32)),
        )

        assertTrue(
            AgentRuntimeCallerPolicy.packageMatchesPinnedSigner(rotated, 10_001, pins),
        )
        assertFalse(
            AgentRuntimeCallerPolicy.packageMatchesPinnedSigner(unrelatedHistory, 10_001, pins),
        )
    }

    @Test
    fun multipleSignersRequireEveryCurrentCertificate() {
        val partial = CallerPackageIdentity(
            packageName = breeno,
            packageUid = 10_001,
            userId = 0,
            currentSignerDigests = setOf(pinnedBreeno, "55".repeat(32)),
            historySignerDigests = setOf(pinnedBreeno),
            hasMultipleSigners = true,
        )

        assertFalse(AgentRuntimeCallerPolicy.packageMatchesPinnedSigner(partial, 10_001, pins))
    }

    @Test
    fun selfUidIsAllowedUntilAnotherPackageSharesIt() {
        assertTrue(
            decide(callingUid = selfUid, packages = null).allowed,
        )
        assertTrue(
            decide(
                callingUid = selfUid,
                packages = listOf(signed(modulePackage, uid = selfUid, digest = "66".repeat(32))),
            ).allowed,
        )
        assertFalse(
            decide(
                callingUid = selfUid,
                packages = listOf(
                    signed(modulePackage, uid = selfUid, digest = "66".repeat(32)),
                    signed(breeno, uid = selfUid, digest = pinnedBreeno),
                ),
            ).allowed,
        )
    }

    @Test
    fun missingPackagesAndInvalidUidsFailClosed() {
        assertEquals("invalid_uid", decide(callingUid = -1, packages = emptyList()).reason)
        assertEquals("package_lookup_failed", decide(callingUid = 10_001, packages = null).reason)
        assertEquals("no_packages", decide(callingUid = 10_001, packages = emptyList()).reason)
    }

    @Test
    fun nonHexPinsNeverMatch() {
        val decision = decide(
            callingUid = 10_001,
            packages = listOf(signed(breeno, uid = 10_001, digest = pinnedBreeno)),
            pins = mapOf(breeno to setOf("PLACEHOLDER_NOT_A_REAL_CERTIFICATE_DIGEST")),
        )

        assertFalse(decision.allowed)
        assertNull(AgentRuntimeCallerPolicy.normalizeCertificateDigest("zz".repeat(32)))
        assertEquals(
            pinnedBreeno,
            AgentRuntimeCallerPolicy.normalizeCertificateDigest(pinnedBreeno.chunked(2).joinToString(":").uppercase()),
        )
    }

    @Test
    fun shippedPinsArePlaceholdersAndMatchTheManifestAllowlist() {
        val xml = sourceFile("src/main/res/values/agent_runtime_security.xml").readText()
        val manifest = sourceFile("src/main/AndroidManifest.xml").readText()

        assertTrue(xml.contains("PLACEHOLDER"))
        assertTrue(xml.contains("apksigner verify print-certs"))
        assertTrue(
            sourceFile("src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeCallerAllowlist.kt")
                .readText()
                .contains("apksigner verify --print-certs"),
        )
        assertTrue(xml.contains(AgentRuntimeCallerAllowlist.PLACEHOLDER_BREENO_CERT_SHA256))
        assertTrue(xml.contains(AgentRuntimeCallerAllowlist.PLACEHOLDER_XIAOAI_CERT_SHA256))
        assertTrue(manifest.contains("android:protectionLevel=\"signature|knownSigner\""))
        assertTrue(manifest.contains("android:permission=\"${AgentRuntimeCallerAllowlist.PERMISSION}\""))
        assertTrue(manifest.contains("android:knownCerts=\"@array/agent_runtime_known_signers\""))

        val shipped = AgentRuntimeCallerPolicy.decide(
            callingUid = 10_001,
            callingPid = 42,
            selfUid = selfUid,
            modulePackage = modulePackage,
            packages = listOf(signed(breeno, uid = 10_001, digest = pinnedBreeno)),
            pins = AgentRuntimeCallerAllowlist.pins,
        )
        assertFalse(shipped.allowed)
    }

    @Test
    fun highRiskToolsIncludeRootTerminalFileMutationAndInstall() {
        assertTrue("terminal" in AgentRuntimeCallerPolicy.HIGH_RISK_TOOLS)
        assertTrue("write_file" in AgentRuntimeCallerPolicy.HIGH_RISK_TOOLS)
        assertTrue("skills_install_from_github" in AgentRuntimeCallerPolicy.HIGH_RISK_TOOLS)
    }

    @Test
    fun packageReplacementWatchesOnlyTheModuleAndPinnedPackages() {
        assertTrue(
            AgentRuntimeCallerPolicy.isRevocationPackage(breeno, pins, modulePackage),
        )
        assertTrue(
            AgentRuntimeCallerPolicy.isRevocationPackage(modulePackage, pins, modulePackage),
        )
        assertFalse(
            AgentRuntimeCallerPolicy.isRevocationPackage("com.example.other", pins, modulePackage),
        )
    }

    private fun decide(
        callingUid: Int,
        packages: List<CallerPackageIdentity>?,
        pins: Map<String, Set<String>> = this.pins,
    ) = AgentRuntimeCallerPolicy.decide(
        callingUid = callingUid,
        callingPid = 4242,
        selfUid = selfUid,
        modulePackage = modulePackage,
        packages = packages,
        pins = pins,
    )

    private fun signed(
        packageName: String,
        uid: Int,
        digest: String,
    ) = CallerPackageIdentity(
        packageName = packageName,
        packageUid = uid,
        userId = AgentRuntimeCallerPolicy.userIdOf(uid),
        currentSignerDigests = setOf(digest),
        historySignerDigests = setOf(digest),
        hasMultipleSigners = false,
    )
}

internal fun sourceFile(relativeToModule: String): java.io.File {
    val cwd = java.io.File(System.getProperty("user.dir") ?: error("user.dir is unset"))
    val candidates = listOf(
        java.io.File(cwd, relativeToModule),
        java.io.File(cwd, "app/$relativeToModule"),
        cwd.parentFile?.let { java.io.File(it, "app/$relativeToModule") },
    )
    return candidates.filterNotNull().firstOrNull { it.isFile }
        ?: error("Missing $relativeToModule from $cwd")
}
