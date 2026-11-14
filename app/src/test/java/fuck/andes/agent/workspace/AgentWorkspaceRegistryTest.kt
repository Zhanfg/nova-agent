package fuck.andes.agent.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentWorkspaceRegistryTest {
    @Test
    fun registeredWorkspaceCanBeResolvedByLaterConsumer() {
        val registry = AgentWorkspaceRegistry()
        val workspace = workspace("ws-1", "/repo")

        registry.register(workspace)

        assertEquals(workspace, registry.get("ws-1"))
        assertEquals(listOf(workspace), registry.snapshot())
    }

    @Test
    fun removingWorkspaceInvalidatesItsIdentity() {
        val registry = AgentWorkspaceRegistry()
        registry.register(workspace("ws-1", "/repo"))

        val removed = registry.remove("ws-1")

        assertEquals("ws-1", removed?.workspaceId)
        assertNull(registry.get("ws-1"))
    }

    private fun workspace(id: String, root: String) = AgentWorkspace(
        workspaceId = id,
        rootPath = root,
        repositoryRoot = root,
        baseRef = "main",
        baseSha = "abc123",
        branch = "main",
        worktreePath = null,
        sharedOriginalWorkspace = true,
        dirtyBaseline = AgentWorkspace.DirtyBaseline("", 1L),
    )
}
