package fuck.andes.agent.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGitWorkspaceManagerTest {
    @Test
    fun inspectCapturesRepositoryHeadBranchAndDirtyBaseline() {
        val git = FakeGit().apply {
            answer("git rev-parse --show-toplevel", "/project", ok("/project\n"))
            answer("git rev-parse HEAD", "/project", ok("abc123\n"))
            answer("git symbolic-ref --quiet --short HEAD", "/project", ok("main\n"))
            answer(
                "git status --porcelain=v1 --untracked-files=all",
                "/project",
                ok(" M app.kt\n?? scratch.txt\n"),
            )
        }
        val manager = manager(git)

        val result = manager.inspect("/project/subdir")
        val workspace = result.workspace ?: error("expected workspace")

        assertNull(result.error)
        assertEquals("/project", workspace.repositoryRoot)
        assertEquals("abc123", workspace.baseSha)
        assertEquals("main", workspace.branch)
        assertTrue(workspace.dirtyBaseline.isDirty)
        assertTrue(workspace.sharedOriginalWorkspace)
    }

    @Test
    fun isolatedWorktreeStartsFromCapturedHeadAndDoesNotCopyDirtyFiles() {
        val git = FakeGit().apply {
            default = ok()
            answer("git rev-parse HEAD", "/tmp/nova/task-fixed", ok("abc123\n"))
        }
        val manager = manager(git)
        val source = workspace(dirty = " M local-only.txt\n")

        val result = manager.createIsolatedWorktree(
            source = source,
            taskId = "Fix login race",
        )
        val created = result.workspace ?: error("expected isolated worktree")

        assertNull(result.error)
        assertEquals("nova/fix-login-race-fixed", created.branch)
        assertEquals("/tmp/nova/fix-login-race-fixed", created.worktreePath)
        assertFalse(created.sharedOriginalWorkspace)
        assertFalse(created.dirtyBaseline.isDirty)
        assertTrue(
            git.calls.any { call ->
                call.command.contains("git worktree add --no-track -b") &&
                    call.command.contains("'abc123'") &&
                    call.cwd == "/project"
            }
        )
        assertFalse(git.calls.any { it.command.contains("local-only.txt") })
    }

    @Test
    fun dirtyWorktreeCannotBeRemovedWithoutForce() {
        val git = FakeGit().apply {
            answer(
                "git status --porcelain=v1 --untracked-files=all",
                "/tmp/nova/wt",
                ok(" M src/Main.kt\n"),
            )
        }
        val manager = manager(git)
        val isolated = workspace().copy(
            workspaceId = "wt-1",
            worktreePath = "/tmp/nova/wt",
            sharedOriginalWorkspace = false,
        )

        val error = manager.removeIsolatedWorktree(isolated, force = false)

        assertNotNull(error)
        assertTrue(error!!.contains("未提交修改"))
        assertFalse(git.calls.any { it.command.startsWith("git worktree remove") })
    }

    @Test
    fun forceRemovalUsesExplicitGitForceFlag() {
        val git = FakeGit().apply { default = ok() }
        val manager = manager(git)
        val isolated = workspace().copy(
            workspaceId = "wt-1",
            worktreePath = "/tmp/nova/wt",
            sharedOriginalWorkspace = false,
        )

        assertNull(manager.removeIsolatedWorktree(isolated, force = true))
        assertTrue(
            git.calls.any {
                it.command == "git worktree remove --force '/tmp/nova/wt'" && it.cwd == "/project"
            }
        )
    }

    @Test
    fun shellQuoteProtectsPathsWithSingleQuotes() {
        assertEquals("'/tmp/a'\"'\"'b'", AgentShellQuote.quote("/tmp/a'b"))
    }

    @Test
    fun gitRefValidationRejectsTraversalAndLockLikeNames() {
        assertTrue(AgentGitWorkspaceManager.isSafeGitRef("nova/task-123"))
        assertFalse(AgentGitWorkspaceManager.isSafeGitRef("../escape"))
        assertFalse(AgentGitWorkspaceManager.isSafeGitRef("nova//task"))
        assertFalse(AgentGitWorkspaceManager.isSafeGitRef("nova/task.lock"))
        assertFalse(AgentGitWorkspaceManager.isSafeGitRef("nova/bad:name"))
    }

    private fun manager(git: FakeGit) = AgentGitWorkspaceManager(
        git = git,
        worktreeBaseDir = "/tmp/nova",
        clock = { 100L },
        idFactory = { "fixed" },
    )

    private fun workspace(dirty: String = "") = AgentWorkspace(
        workspaceId = "repo-1",
        rootPath = "/project",
        repositoryRoot = "/project",
        baseRef = "main",
        baseSha = "abc123",
        branch = "main",
        worktreePath = null,
        sharedOriginalWorkspace = true,
        dirtyBaseline = AgentWorkspace.DirtyBaseline(dirty, 1L),
    )

    private fun ok(stdout: String = "") = AgentGitExecutor.Result(
        exitCode = 0,
        stdout = stdout,
        stderr = "",
    )

    private class FakeGit : AgentGitExecutor {
        data class Call(val command: String, val cwd: String, val timeoutMs: Int)
        val calls = mutableListOf<Call>()
        var default: AgentGitExecutor.Result = AgentGitExecutor.Result(1, "", "unexpected command")
        private val answers = mutableMapOf<Pair<String, String>, AgentGitExecutor.Result>()

        fun answer(command: String, cwd: String, result: AgentGitExecutor.Result) {
            answers[command to cwd] = result
        }

        override fun execute(command: String, cwd: String, timeoutMs: Int): AgentGitExecutor.Result {
            calls += Call(command, cwd, timeoutMs)
            return answers[command to cwd] ?: default
        }
    }
}
