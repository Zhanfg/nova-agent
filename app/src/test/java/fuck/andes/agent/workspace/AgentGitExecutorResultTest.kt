package fuck.andes.agent.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGitExecutorResultTest {
    @Test
    fun successfulCompleteResultIsReviewable() {
        val result = AgentGitExecutor.Result(
            exitCode = 0,
            stdout = "diff --git a/a b/a",
            stderr = "",
        )

        assertTrue(result.ok)
        assertTrue(result.complete)
    }

    @Test
    fun truncationMakesResultIncompleteWithoutPretendingCommandFailed() {
        val result = AgentGitExecutor.Result(
            exitCode = 0,
            stdout = "partial diff",
            stderr = "",
            stdoutTruncated = true,
        )

        assertTrue(result.ok)
        assertFalse(result.complete)
    }

    @Test
    fun timeoutIsNotSuccessfulEvenWhenOutputIsComplete() {
        val result = AgentGitExecutor.Result(
            exitCode = -2,
            stdout = "partial",
            stderr = "timeout",
            timedOut = true,
        )

        assertFalse(result.ok)
        assertTrue(result.complete)
    }
}
