package fuck.andes.agent.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkspacePathPolicyTest {
    @Test
    fun sharedStoragePathsAreVisibleToLinuxWorkspaceTools() {
        assertTrue(AgentWorkspacePathPolicy.isLinuxVisible("/storage/emulated/0/Download/project"))
        assertTrue(AgentWorkspacePathPolicy.isLinuxVisible("/data/local/tmp/fuck_andes/project"))
    }

    @Test
    fun appPrivateDataPathIsRejectedBecauseLinuxRootfsDoesNotBindIt() {
        assertFalse(AgentWorkspacePathPolicy.isLinuxVisible("/data/user/0/fuck.andes/files/agent/worktrees"))
    }

    @Test
    fun prefixLookalikesDoNotEscapeSharedRootCheck() {
        assertFalse(AgentWorkspacePathPolicy.isLinuxVisible("/storage/emulated/01/project"))
        assertFalse(AgentWorkspacePathPolicy.isLinuxVisible("/data/local/tmp-evil/project"))
    }
}
