package fuck.andes.agent.goal

import fuck.andes.agent.goal.AgentGoalTracker.CriterionStatus
import fuck.andes.agent.goal.AgentGoalTracker.Evidence
import fuck.andes.agent.goal.AgentGoalTracker.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGoalTrackerTest {
    @Test
    fun writingCodeAloneCannotCompleteGoalWithoutVerificationEvidence() {
        val tracker = tracker()
        tracker.updateUsage(steps = 12, tokens = 5_000, costMicros = 1_000)

        assertEquals(State.Running, tracker.evaluate(nowMillis = 100L))
    }

    @Test
    fun allCriteriaMustHavePassingEvidence() {
        val tracker = tracker()
        tracker.recordEvidence(evidence("build", CriterionStatus.PASSED))

        assertEquals(State.Running, tracker.evaluate(nowMillis = 100L))

        tracker.recordEvidence(evidence("tests", CriterionStatus.PASSED))
        val state = tracker.evaluate(nowMillis = 100L)

        assertTrue(state is State.Succeeded)
        assertEquals(listOf("build", "tests"), (state as State.Succeeded).evidence.map { it.criterionId })
    }

    @Test
    fun verifiedSuccessWinsWhenBookkeepingReachesBudgetBoundaryNextRound() {
        val tracker = tracker(
            budget = AgentGoal.Budget(
                maxSteps = 2,
                maxDurationMillis = 100,
                maxTokens = 1_000,
            )
        )
        tracker.recordEvidence(evidence("build", CriterionStatus.PASSED))
        tracker.recordEvidence(evidence("tests", CriterionStatus.PASSED))
        tracker.updateUsage(steps = 2, tokens = 1_000, costMicros = 0)

        val state = tracker.evaluate(nowMillis = 100L)

        assertTrue(state is State.Succeeded)
    }

    @Test
    fun failedVerificationDoesNotPretendTaskIsComplete() {
        val tracker = tracker()
        tracker.recordEvidence(evidence("build", CriterionStatus.PASSED))
        tracker.recordEvidence(evidence("tests", CriterionStatus.FAILED))

        assertEquals(State.VerificationFailed(listOf("tests")), tracker.evaluate(nowMillis = 100L))
    }

    @Test
    fun newerEvidenceCanRepairFailedCriterionBeforeSuccess() {
        val tracker = tracker()
        tracker.recordEvidence(evidence("build", CriterionStatus.PASSED))
        tracker.recordEvidence(evidence("tests", CriterionStatus.FAILED))
        assertEquals(State.VerificationFailed(listOf("tests")), tracker.evaluate(nowMillis = 100L))

        tracker.recordEvidence(evidence("tests", CriterionStatus.PASSED, summary = "rerun passed"))
        assertTrue(tracker.evaluate(nowMillis = 101L) is State.Succeeded)
    }

    @Test
    fun tokenBudgetStopsGoalBeforeUnverifiedContinuation() {
        val tracker = tracker(
            budget = AgentGoal.Budget(
                maxSteps = 100,
                maxDurationMillis = 10_000,
                maxTokens = 1_000,
            )
        )
        tracker.updateUsage(steps = 5, tokens = 1_000, costMicros = 0)

        assertEquals(
            State.BudgetExceeded("token budget exceeded"),
            tracker.evaluate(nowMillis = 100L),
        )
    }

    @Test
    fun terminalSuccessCannotBeMutatedAfterCompletion() {
        val tracker = tracker()
        tracker.recordEvidence(evidence("build", CriterionStatus.PASSED))
        tracker.recordEvidence(evidence("tests", CriterionStatus.PASSED))
        assertTrue(tracker.evaluate(nowMillis = 100L) is State.Succeeded)

        val thrown = runCatching {
            tracker.recordEvidence(evidence("tests", CriterionStatus.FAILED))
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }

    private fun tracker(
        budget: AgentGoal.Budget = AgentGoal.Budget(
            maxSteps = 100,
            maxDurationMillis = 10_000,
            maxTokens = 100_000,
            maxCostMicros = 1_000_000,
        ),
    ) = AgentGoalTracker(
        goal = AgentGoal(
            goalId = "goal-1",
            goal = "修复并验证功能",
            successCriteria = listOf(
                AgentGoal.Criterion("build", "Debug APK 可构建"),
                AgentGoal.Criterion("tests", "相关测试通过"),
            ),
            constraints = listOf("不得删除测试"),
            verificationPlan = "构建并执行测试",
            budget = budget,
        ),
        startedAtMillis = 0L,
    )

    private fun evidence(
        id: String,
        status: CriterionStatus,
        summary: String = "verified",
    ) = Evidence(
        criterionId = id,
        status = status,
        summary = summary,
        source = "test",
        capturedAtMillis = 10L,
    )
}
