package fuck.andes.agent.runtime

/**
 * 每次 Binder 事务的调用方裁决。
 *
 * 只接受这一次事务里读到的 UID。函数没有“绑定时的 UID”参数，调用方不能把旧身份传进来复用。
 * 同一 UID 上的每个包都必须单独满足钉扎策略；工作资料或其他用户是不同的 UID，不会继承主用户的授权结果。
 */
internal data class AgentRuntimeCallerSnapshot(
    val uid: Int,
    val pid: Int,
)

internal data class CallerPackageIdentity(
    val packageName: String,
    val packageUid: Int,
    val userId: Int,
    val currentSignerDigests: Set<String>,
    val historySignerDigests: Set<String>,
    val hasMultipleSigners: Boolean,
)

internal data class CallerDecision(
    val allowed: Boolean,
    val reason: String,
    val uid: Int,
    val pid: Int,
    val userId: Int,
    val packages: List<String>,
    val signerDigests: List<String>,
) {
    fun auditLine(): String =
        "uid=$uid pid=$pid user=$userId packages=${packages.joinToString("|")} " +
            "signers=${signerDigests.joinToString("|")} allowed=$allowed reason=$reason"
}

internal object AgentRuntimeCallerPolicy {
    const val PER_USER_RANGE = 100_000

    val HIGH_RISK_TOOLS: Set<String> = setOf(
        "terminal",
        "run_command",
        "read_file",
        "write_file",
        "list_directory",
        "skills_install_from_github",
    )

    fun userIdOf(uid: Int): Int = if (uid < 0) -1 else uid / PER_USER_RANGE

    fun isRevocationPackage(
        packageName: String,
        pins: Map<String, Set<String>>,
        modulePackage: String,
    ): Boolean = packageName == modulePackage || packageName in pins

    fun normalizeCertificateDigest(value: String): String? {
        val hex = value.trim().lowercase().filterNot { it == ':' || it.isWhitespace() }
        if (hex.length != 64 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return hex
    }

    fun decide(
        callingUid: Int,
        callingPid: Int,
        selfUid: Int,
        modulePackage: String,
        packages: List<CallerPackageIdentity>?,
        pins: Map<String, Set<String>>,
    ): CallerDecision {
        val userId = userIdOf(callingUid)
        val packageNames = packages.orEmpty().map { it.packageName }
        val digests = packages.orEmpty()
            .flatMap { it.currentSignerDigests }
            .mapNotNull(::normalizeCertificateDigest)
            .distinct()
        fun decision(allowed: Boolean, reason: String) = CallerDecision(
            allowed = allowed,
            reason = reason,
            uid = callingUid,
            pid = callingPid,
            userId = userId,
            packages = packageNames,
            signerDigests = digests,
        )

        if (callingUid < 0) return decision(false, "invalid_uid")
        if (callingUid == selfUid) {
            val sharesUidWithForeignPackage = packages.orEmpty().any { identity ->
                identity.packageName != modulePackage ||
                    (identity.packageUid >= 0 && identity.packageUid != callingUid)
            }
            return if (sharesUidWithForeignPackage) {
                decision(false, "shared_uid")
            } else {
                decision(true, "self_uid")
            }
        }
        if (packages == null) return decision(false, "package_lookup_failed")
        if (packages.isEmpty()) return decision(false, "no_packages")
        val untrusted = packages.any { identity ->
            !packageMatchesPinnedSigner(identity, callingUid, pins)
        }
        return if (untrusted) {
            decision(false, "untrusted_package")
        } else {
            decision(true, "pinned_signer")
        }
    }

    /**
     * 多签必须每个当前证书都在钉扎集合里。单签允许当前证书命中，或历史证书链包含钉扎证书
     * （合法轮换后的当前签名者）。没有当前证书、UID 不匹配，或钉扎值不是 SHA-256 时拒绝。
     */
    fun packageMatchesPinnedSigner(
        identity: CallerPackageIdentity,
        callingUid: Int,
        pins: Map<String, Set<String>>,
    ): Boolean {
        if (identity.packageUid != callingUid) return false
        if (identity.userId != userIdOf(callingUid)) return false
        val pinned = pins[identity.packageName]
            .orEmpty()
            .mapNotNull(::normalizeCertificateDigest)
            .toSet()
        if (pinned.isEmpty()) return false
        val current = identity.currentSignerDigests.mapNotNull(::normalizeCertificateDigest).toSet()
        if (current.isEmpty()) return false
        if (identity.hasMultipleSigners) return current.all { it in pinned }
        if (current.all { it in pinned }) return true
        val history = identity.historySignerDigests.mapNotNull(::normalizeCertificateDigest).toSet()
        return history.any { it in pinned }
    }
}
