package fuck.andes.ui.app

import android.content.Context
import fuck.andes.data.db.FuckAndesDatabase
import fuck.andes.ui.model.AgentChatHomeUiState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentConversationWorkspaceStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        FuckAndesDatabase.closeForTests()
        context.deleteDatabase("fuck_andes.db")
    }

    @After
    fun tearDown() {
        FuckAndesDatabase.closeForTests()
        context.deleteDatabase("fuck_andes.db")
    }

    @Test
    fun workspaceBindingSurvivesConversationPersistenceRoundTrip() {
        val state = AgentChatHomeUiState(
            messages = emptyList(),
            workspaceId = "ws-project-1",
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        )

        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-1",
                conversationsById = mapOf("conv-1" to state),
                titles = mapOf("conv-1" to "Project"),
                updatedAt = mapOf("conv-1" to 1L),
            )
        }

        val restored = AgentConversationStore.load(context)
            .conversationsById
            .getValue("conv-1")

        assertEquals("ws-project-1", restored.workspaceId)
    }
}
