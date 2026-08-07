package fuck.andes.agent.workspace

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime-level registry for Codex workspaces.
 *
 * AgentLocalTools is recreated for every run, so workspace identity cannot live inside a single
 * tool executor instance. An optional persistence backend makes the identity survive Runtime
 * process death without coupling workspace logic to Room or UI conversation storage.
 */
internal class AgentWorkspaceRegistry(
    private val persistence: Persistence? = null,
) {
    interface Persistence {
        fun load(): List<AgentWorkspace>
        fun save(workspaces: List<AgentWorkspace>)
    }

    private val workspaces = ConcurrentHashMap<String, AgentWorkspace>()
    private val mutationLock = Any()

    init {
        persistence?.load().orEmpty().forEach { workspace ->
            if (workspace.workspaceId.isNotBlank()) {
                workspaces[workspace.workspaceId] = workspace
            }
        }
    }

    fun register(workspace: AgentWorkspace): AgentWorkspace = synchronized(mutationLock) {
        require(workspace.workspaceId.isNotBlank()) { "workspaceId 不能为空" }
        val previous = workspaces.put(workspace.workspaceId, workspace)
        try {
            persistLocked()
        } catch (failure: Throwable) {
            if (previous == null) {
                workspaces.remove(workspace.workspaceId, workspace)
            } else {
                workspaces[workspace.workspaceId] = previous
            }
            throw failure
        }
        workspace
    }

    fun get(workspaceId: String): AgentWorkspace? =
        workspaceId.takeIf(String::isNotBlank)?.let(workspaces::get)

    fun remove(workspaceId: String): AgentWorkspace? = synchronized(mutationLock) {
        val normalized = workspaceId.takeIf(String::isNotBlank) ?: return@synchronized null
        val removed = workspaces.remove(normalized) ?: return@synchronized null
        try {
            persistLocked()
        } catch (failure: Throwable) {
            workspaces[normalized] = removed
            throw failure
        }
        removed
    }

    fun snapshot(): List<AgentWorkspace> = workspaces.values
        .sortedWith(compareBy<AgentWorkspace> { it.repositoryRoot.orEmpty() }.thenBy { it.workspaceId })

    fun clearForTests() = synchronized(mutationLock) {
        val previous = workspaces.toMap()
        workspaces.clear()
        try {
            persistLocked()
        } catch (failure: Throwable) {
            workspaces.putAll(previous)
            throw failure
        }
    }

    private fun persistLocked() {
        persistence?.save(snapshot())
    }
}

/** Small JSON store owned by Nova; no provider/API secrets are written here. */
internal class AgentWorkspaceFilePersistence(
    private val file: File,
) : AgentWorkspaceRegistry.Persistence {
    override fun load(): List<AgentWorkspace> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            buildList {
                repeat(array.length()) { index ->
                    array.optJSONObject(index)?.toWorkspace()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun save(workspaces: List<AgentWorkspace>) {
        val parent = file.parentFile ?: error("workspace registry 缺少父目录")
        if (!parent.exists() && !parent.mkdirs()) {
            error("无法创建 workspace registry 目录")
        }
        val payload = JSONArray().also { array ->
            workspaces.forEach { workspace -> array.put(workspace.toJson()) }
        }.toString()
        val temp = File(parent, "${file.name}.tmp")
        temp.writeText(payload)
        try {
            runCatching {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun AgentWorkspace.toJson(): JSONObject = JSONObject()
        .put("workspace_id", workspaceId)
        .put("root_path", rootPath)
        .put("repository_root", repositoryRoot ?: JSONObject.NULL)
        .put("base_ref", baseRef ?: JSONObject.NULL)
        .put("base_sha", baseSha ?: JSONObject.NULL)
        .put("branch", branch ?: JSONObject.NULL)
        .put("worktree_path", worktreePath ?: JSONObject.NULL)
        .put("shared_original_workspace", sharedOriginalWorkspace)
        .put("dirty_porcelain", dirtyBaseline.porcelain)
        .put("dirty_captured_at", dirtyBaseline.capturedAtMillis)

    private fun JSONObject.toWorkspace(): AgentWorkspace? = runCatching {
        AgentWorkspace(
            workspaceId = getString("workspace_id"),
            rootPath = getString("root_path"),
            repositoryRoot = optNullableString("repository_root"),
            baseRef = optNullableString("base_ref"),
            baseSha = optNullableString("base_sha"),
            branch = optNullableString("branch"),
            worktreePath = optNullableString("worktree_path"),
            sharedOriginalWorkspace = optBoolean("shared_original_workspace", true),
            dirtyBaseline = AgentWorkspace.DirtyBaseline(
                porcelain = optString("dirty_porcelain"),
                capturedAtMillis = optLong("dirty_captured_at"),
            ),
        )
    }.getOrNull()

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf(String::isNotBlank)
}

/** Shared per-process registry backed by the app sandbox so a new Runtime process can reload it. */
internal object AgentWorkspaceRuntimeRegistry {
    @Volatile
    private var instance: AgentWorkspaceRegistry? = null

    fun get(context: Context): AgentWorkspaceRegistry =
        instance ?: synchronized(this) {
            instance ?: AgentWorkspaceRegistry(
                persistence = AgentWorkspaceFilePersistence(
                    File(context.applicationContext.filesDir, "agent/workspaces.json")
                )
            ).also { instance = it }
        }

    internal fun resetForTests() {
        synchronized(this) {
            instance = null
        }
    }
}
