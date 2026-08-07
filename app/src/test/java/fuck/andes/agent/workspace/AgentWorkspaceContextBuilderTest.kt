package fuck.andes.agent.workspace

import fuck.andes.agent.model.AgentModelClient
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkspaceContextBuilderTest {
    @Test
    fun isolatedWorktreeLoadsAgentsFromWorktreeRatherThanSourceCheckout() {
        val sourceRoot = Files.createTempDirectory("nova-source-repo").toFile()
        val worktreeRoot = Files.createTempDirectory("nova-worktree").toFile()
        try {
            File(sourceRoot, "AGENTS.md").writeText("source checkout instruction should not leak")
            File(worktreeRoot, "AGENTS.md").writeText("worktree root instruction")
            val workspace = AgentWorkspace(
                workspaceId = "ws-1",
                rootPath = sourceRoot.absolutePath,
                repositoryRoot = sourceRoot.absolutePath,
                baseRef = "main",
                baseSha = "abc123",
                branch = "feat/mobile",
                worktreePath = worktreeRoot.absolutePath,
                sharedOriginalWorkspace = false,
                dirtyBaseline = AgentWorkspace.DirtyBaseline(" M app/Main.kt", 1L),
            )
            val original = AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid/v1",
                apiKey = "test",
                model = "test-model",
                systemPrompt = "base system prompt",
            )

            val augmented = AgentWorkspaceContextBuilder.augment(workspace, original)

            assertEquals("base system prompt", original.systemPrompt)
            assertTrue(augmented.systemPrompt.contains("base system prompt"))
            assertTrue(augmented.systemPrompt.contains("<nova_workspace_context>"))
            assertTrue(augmented.systemPrompt.contains("workspace_id: ws-1"))
            assertTrue(augmented.systemPrompt.contains("working_directory: ${worktreeRoot.absolutePath}"))
            assertTrue(augmented.systemPrompt.contains("repository_root: ${sourceRoot.absolutePath}"))
            assertTrue(augmented.systemPrompt.contains("branch: feat/mobile"))
            assertTrue(augmented.systemPrompt.contains("base_sha: abc123"))
            assertTrue(augmented.systemPrompt.contains("baseline_dirty: true"))
            assertTrue(augmented.systemPrompt.contains("worktree root instruction"))
            assertTrue(!augmented.systemPrompt.contains("source checkout instruction should not leak"))
            assertTrue(augmented.systemPrompt.contains("</nova_workspace_context>"))
        } finally {
            sourceRoot.deleteRecursively()
            worktreeRoot.deleteRecursively()
        }
    }

    @Test
    fun cleanWorkspaceWithoutAgentsStillInjectsStableWorkingContext() {
        val root = Files.createTempDirectory("nova-workspace-clean").toFile()
        try {
            val workspace = AgentWorkspace(
                workspaceId = "ws-clean",
                rootPath = root.absolutePath,
                repositoryRoot = null,
                baseRef = null,
                baseSha = null,
                branch = null,
                worktreePath = null,
                sharedOriginalWorkspace = true,
                dirtyBaseline = AgentWorkspace.DirtyBaseline("", 1L),
            )
            val config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid/v1",
                apiKey = "test",
                model = "test-model",
                systemPrompt = "",
            )

            val augmented = AgentWorkspaceContextBuilder.augment(workspace, config)

            assertTrue(augmented.systemPrompt.contains("workspace_id: ws-clean"))
            assertTrue(augmented.systemPrompt.contains("working_directory: ${root.absolutePath}"))
            assertTrue(!augmented.systemPrompt.contains("baseline_dirty: true"))
        } finally {
            root.deleteRecursively()
        }
    }
}
