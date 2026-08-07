package fuck.andes.agent.workspace

import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime-level registry for Codex workspaces.
 *
 * AgentLocalTools is recreated for every run, so workspace identity cannot live inside a single
 * tool executor instance. This registry keeps inspected repositories and isolated worktrees usable
 * across subsequent turns in the same Runtime process. Durable process-death restoration can be
 * layered behind this narrow API without changing tool semantics.
 */
internal class AgentWorkspaceRegistry {
    private val workspaces = ConcurrentHashMap<String, AgentWorkspace>()

    fun register(workspace: AgentWorkspace): AgentWorkspace {
        workspaces[workspace.workspaceId] = workspace
        return workspace
    }

    fun get(workspaceId: String): AgentWorkspace? =
        workspaceId.takeIf(String::isNotBlank)?.let(workspaces::get)

    fun remove(workspaceId: String): AgentWorkspace? =
        workspaceId.takeIf(String::isNotBlank)?.let(workspaces::remove)

    fun snapshot(): List<AgentWorkspace> = workspaces.values
        .sortedWith(compareBy<AgentWorkspace> { it.repositoryRoot.orEmpty() }.thenBy { it.workspaceId })

    fun clearForTests() {
        workspaces.clear()
    }
}

/** Shared process registry used by all per-run AgentLocalTools instances. */
internal object AgentWorkspaceRuntimeRegistry {
    val shared = AgentWorkspaceRegistry()
}
