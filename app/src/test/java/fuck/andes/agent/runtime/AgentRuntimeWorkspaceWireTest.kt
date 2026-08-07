package fuck.andes.agent.runtime

import fuck.andes.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRuntimeWorkspaceWireTest {
    @Test
    fun legacyBundleRoundTripPreservesWorkspaceId() {
        val request = request(workspaceId = "ws-project-1")

        val restored = AgentRuntimeWire.runRequestFromBundle(
            AgentRuntimeWire.toLegacyBundle(request)
        )

        assertEquals("ws-project-1", restored.workspaceId)
    }

    @Test
    fun missingWorkspaceIdRemainsNullForBackwardCompatibility() {
        val request = request(workspaceId = null)

        val restored = AgentRuntimeWire.runRequestFromBundle(
            AgentRuntimeWire.toLegacyBundle(request)
        )

        assertNull(restored.workspaceId)
    }

    private fun request(workspaceId: String?) = AgentRuntimeWire.RunRequest(
        runId = "run-workspace-wire",
        prompt = "inspect project",
        config = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "",
        ),
        images = emptyList(),
        workspaceId = workspaceId,
    )
}
