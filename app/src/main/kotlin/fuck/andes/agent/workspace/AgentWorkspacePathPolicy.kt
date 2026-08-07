package fuck.andes.agent.workspace

import android.content.Context
import java.io.File

/**
 * Path contract shared by Android-side project instruction loading and the Alpine Git environment.
 *
 * The Linux rootfs only bind-mounts shared storage and /data/local/tmp. For first-class Codex
 * workspaces we additionally require Android app-process readability, because AGENTS.md is loaded
 * before the first model request on the Android side. App-owned external storage satisfies both
 * constraints and is therefore used for generated worktrees.
 */
internal object AgentWorkspacePathPolicy {
    private const val SHARED_STORAGE_ROOT = "/storage/emulated/0"
    private const val LOCAL_TMP_ROOT = "/data/local/tmp"

    data class Validation(
        val canonicalPath: String?,
        val error: String? = null,
    ) {
        val ok: Boolean
            get() = canonicalPath != null && error == null
    }

    fun validateReadableWorkspace(path: String): Validation {
        val canonical = runCatching { File(path).canonicalFile }.getOrElse {
            return Validation(null, "无法规范化 workspace 路径")
        }
        if (!isLinuxVisible(canonical.path)) {
            return Validation(
                null,
                "workspace 必须位于 /storage/emulated/0 或 /data/local/tmp，确保 Android 与 Linux 工具环境看到同一份文件",
            )
        }
        if (!canonical.exists() || !canonical.isDirectory) {
            return Validation(null, "workspace 目录不存在")
        }
        if (!canonical.canRead()) {
            return Validation(
                null,
                "Android 进程无法读取该 workspace；AGENTS.md 与 Review 上下文无法可靠加载，请使用应用可访问的共享存储目录",
            )
        }
        return Validation(canonical.path)
    }

    fun generatedWorktreeBase(context: Context): String {
        val externalFiles = context.getExternalFilesDir(null)
            ?: File(SHARED_STORAGE_ROOT, "Android/data/${context.packageName}/files")
        return File(externalFiles, "agent/worktrees").absolutePath
    }

    internal fun isLinuxVisible(path: String): Boolean {
        val normalized = File(path).toPath().normalize().toString()
        return isWithin(normalized, SHARED_STORAGE_ROOT) || isWithin(normalized, LOCAL_TMP_ROOT)
    }

    private fun isWithin(path: String, root: String): Boolean =
        path == root || path.startsWith("$root/")
}
