package fuck.andes.agent.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGitMutationServiceTest {
    @Test
    fun stageQuotesEveryValidatedRelativePath() {
        val git = FakeGit()
        val service = AgentGitMutationService(git)

        service.stage(workspace(), listOf("app/Main.kt", "docs/a'b.md"))

        assertEquals(
            "git add -- 'app/Main.kt' 'docs/a'\"'\"'b.md'",
            git.lastCommand,
        )
    }

    @Test
    fun traversalAndAbsolutePathsAreRejectedBeforeGitRuns() {
        val git = FakeGit()
        val service = AgentGitMutationService(git)

        assertTrue(runCatching { service.stage(workspace(), listOf("../secret")) }.isFailure)
        assertTrue(runCatching { service.stage(workspace(), listOf("/etc/passwd")) }.isFailure)
        assertEquals(null, git.lastCommand)
    }

    @Test
    fun commitMessageIsShellQuoted() {
        val git = FakeGit()
        val service = AgentGitMutationService(git)

        service.commit(workspace(), "fix user's state")

        assertEquals("git commit -m 'fix user'\"'\"'s state'", git.lastCommand)
    }

    @Test
    fun unsafeBranchIsRejected() {
        val git = FakeGit()
        val service = AgentGitMutationService(git)

        assertTrue(runCatching { service.createAndSwitchBranch(workspace(), "../bad") }.isFailure)
        assertEquals(null, git.lastCommand)
    }

    private fun workspace() = AgentWorkspace(
        workspaceId = "wt-1",
        rootPath = "/repo",
        repositoryRoot = "/repo",
        baseRef = "main",
        baseSha = "abc",
        branch = "nova/task",
        worktreePath = "/repo-wt",
        sharedOriginalWorkspace = false,
        dirtyBaseline = AgentWorkspace.DirtyBaseline("", 0L),
    )

    private class FakeGit : AgentGitExecutor {
        var lastCommand: String? = null
        override fun execute(command: String, cwd: String, timeoutMs: Int): AgentGitExecutor.Result {
            lastCommand = command
            return AgentGitExecutor.Result(0, "", "")
        }
    }
}
