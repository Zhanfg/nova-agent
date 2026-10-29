package fuck.andes.agent.runtime

import android.content.pm.PackageManager
import java.security.GeneralSecurityException
import java.security.MessageDigest

/**
 * 把一次事务的 UID 解析成该 UID 自己的包和签名证书。
 *
 * [PackageManager.getPackageInfo] 返回的是本进程用户中的包。若调用方属于其他用户，
 * 解析到的 application uid 与事务 UID 不一致，此时丢弃证书，交给策略拒绝。
 * 不能用主用户的证书给工作资料里的同名包授权。
 */
internal class AgentRuntimePackageIdentityResolver(
    private val packageManager: PackageManager,
) {
    fun resolve(uid: Int): List<CallerPackageIdentity>? {
        val names = try {
            packageManager.getPackagesForUid(uid) ?: return emptyList()
        } catch (_: RuntimeException) {
            return null
        }
        return names.map { name -> resolvePackage(uid, name) }
    }

    private fun resolvePackage(requestedUid: Int, packageName: String): CallerPackageIdentity {
        val info = try {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()),
            )
        } catch (_: PackageManager.NameNotFoundException) {
            return unresolved(packageName)
        } catch (_: RuntimeException) {
            return unresolved(packageName)
        }
        val packageUid = info.applicationInfo?.uid ?: return unresolved(packageName)
        if (packageUid != requestedUid) {
            return CallerPackageIdentity(
                packageName = packageName,
                packageUid = packageUid,
                userId = AgentRuntimeCallerPolicy.userIdOf(packageUid),
                currentSignerDigests = emptySet(),
                historySignerDigests = emptySet(),
                hasMultipleSigners = false,
            )
        }
        val signingInfo = info.signingInfo ?: return unresolved(packageName, packageUid)
        val current = signerDigests(signingInfo.apkContentsSigners)
        val history = if (signingInfo.hasMultipleSigners()) {
            current
        } else {
            signerDigests(signingInfo.signingCertificateHistory).ifEmpty { current }
        }
        return CallerPackageIdentity(
            packageName = packageName,
            packageUid = packageUid,
            userId = AgentRuntimeCallerPolicy.userIdOf(packageUid),
            currentSignerDigests = current,
            historySignerDigests = history,
            hasMultipleSigners = signingInfo.hasMultipleSigners(),
        )
    }

    private fun unresolved(
        packageName: String,
        packageUid: Int = -1,
    ): CallerPackageIdentity = CallerPackageIdentity(
        packageName = packageName,
        packageUid = packageUid,
        userId = if (packageUid >= 0) AgentRuntimeCallerPolicy.userIdOf(packageUid) else -1,
        currentSignerDigests = emptySet(),
        historySignerDigests = emptySet(),
        hasMultipleSigners = false,
    )

    private fun signerDigests(signatures: Array<android.content.pm.Signature>?): Set<String> {
        if (signatures.isNullOrEmpty()) return emptySet()
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            signatures.mapTo(linkedSetOf()) { signature ->
                digest.digest(signature.toByteArray()).toHex()
            }
        } catch (_: GeneralSecurityException) {
            emptySet()
        }
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        return buildString(size * 2) {
            this@toHex.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(digits[value ushr 4])
                append(digits[value and 0x0f])
            }
        }
    }

    private companion object {
        const val PACKAGE_FLAGS =
            PackageManager.MATCH_DIRECT_BOOT_AWARE or
                PackageManager.MATCH_DIRECT_BOOT_UNAWARE or
                PackageManager.GET_SIGNING_CERTIFICATES
    }
}
