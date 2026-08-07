package fuck.andes.ui.app

import android.content.Context
import fuck.andes.data.db.FuckAndesDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentDraftIsolationDiagnosticTest {
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
    fun appStateConstructionLeavesNoBackgroundChildren() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            newState(scope)
            assertTrue(requireNotNull(scope.coroutineContext[Job]).children.none())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun draftStateOperationsRemainEphemeralInMemory() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val state = newState(scope)
            state.createConversation()
            state.updateInput("尚未发送的草稿")
            state.createConversation()

            assertEquals(null, state.conversationPaneState.selectedConversationId)
            assertTrue(state.conversationPaneState.conversations.isEmpty())
            assertTrue(requireNotNull(scope.coroutineContext[Job]).children.none())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun roomRemainsReadableAfterStateConstruction() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            newState(scope)
            val conversations = runBlocking {
                FuckAndesDatabase.get(context).conversationDao().conversations()
            }
            assertTrue(conversations.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    private fun newState(scope: CoroutineScope): AgentAppState = AgentAppState(
        context = context,
        scope = scope,
        startBackgroundWork = false,
        initialConversationSnapshot = AgentConversationStore.Snapshot(
            selectedConversationId = null,
            conversationsById = emptyMap(),
            titles = emptyMap(),
            updatedAt = emptyMap(),
        ),
    )
}
