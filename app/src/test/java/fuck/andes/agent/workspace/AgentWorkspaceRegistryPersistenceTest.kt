package fuck.andes.agent.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentWorkspaceRegistryPersistenceTest {
    @Test
    fun registeredWorkspaceReloadsIntoNewRegistryInstance() {
        val persistence = FakePersistence()
        val first = AgentWorkspaceRegistry(persistence)
        val workspace = workspace("ws-1")

        first.register(workspace)
        val recreated = AgentWorkspaceRegistry(persistence)

        assertEquals(workspace, recreated.get("ws-1"))
    }

    @Test
    fun removingWorkspacePersistsItsRemoval() {
        val persistence = FakePersistence()
        val first = AgentWorkspaceRegistry(persistence)
        first.register(workspace("ws-1"))
        first.remove("ws-1")

        val recreated = AgentWorkspaceRegistry(persistence)

        assertNull(recreated.get("ws-1"))
        assertEquals(2, persistence.saveCount)
    }

    @Test
    fun failedRegisterPersistenceRollsBackInMemoryMutation() {
        val persistence = FakePersistence()
        val registry = AgentWorkspaceRegistry(persistence)
        registry.register(workspace("existing"))
        persistence.failNextSave = true

        assertThrows(IllegalStateException::class.java) {
            registry.register(workspace("new"))
        }

        assertEquals("existing", registry.get("existing")?.workspaceId)
        assertNull(registry.get("new"))
    }

    @Test
    fun failedRemovePersistenceRestoresWorkspaceInMemory() {
        val persistence = FakePersistence()
        val registry = AgentWorkspaceRegistry(persistence)
        val workspace = workspace("existing")
        registry.register(workspace)
        persistence.failNextSave = true

        assertThrows(IllegalStateException::class.java) {
            registry.remove("existing")
        }

        assertEquals(workspace, registry.get("existing"))
    }

    private class FakePersistence : AgentWorkspaceRegistry.Persistence {
        var stored: List<AgentWorkspace> = emptyList()
        var saveCount: Int = 0
        var failNextSave: Boolean = false

        override fun load(): List<AgentWorkspace> = stored

        override fun save(workspaces: List<AgentWorkspace>) {
            if (failNextSave) {
                failNextSave = false
                throw IllegalStateException("disk unavailable")
            }
            stored = workspaces.toList()
            saveCount += 1
        }
    }

    private fun workspace(id: String) = AgentWorkspace(
        workspaceId = id,
        rootPath = "/storage/emulated/0/Download/project-$id",
        repositoryRoot = "/storage/emulated/0/Download/project-$id",
        baseRef = "main",
        baseSha = "abc123",
        branch = "main",
        worktreePath = null,
        sharedOriginalWorkspace = true,
        dirtyBaseline = AgentWorkspace.DirtyBaseline("", 1L),
    )
}
