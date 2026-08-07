package fuck.andes.agent.goal

/** Contract for a Codex-style goal that cannot complete without verification evidence. */
internal data class AgentGoal(
    val goalId: String,
    val goal: String,
    val successCriteria: List<Criterion>,
    val constraints: List<String> = emptyList(),
    val verificationPlan: String,
    val budget: Budget = Budget(),
) {
    init {
        require(goalId.isNotBlank())
        require(goal.isNotBlank())
        require(successCriteria.isNotEmpty())
        require(successCriteria.map { it.id }.distinct().size == successCriteria.size) {
            "success criterion id 必须唯一"
        }
        require(verificationPlan.isNotBlank())
    }

    data class Criterion(
        val id: String,
        val description: String,
    ) {
        init {
            require(id.isNotBlank())
            require(description.isNotBlank())
        }
    }

    data class Budget(
        val maxSteps: Int = 200,
        val maxDurationMillis: Long = 60 * 60 * 1000L,
        val maxTokens: Long? = null,
        val maxCostMicros: Long? = null,
    ) {
        init {
            require(maxSteps > 0)
            require(maxDurationMillis > 0)
            require(maxTokens == null || maxTokens > 0)
            require(maxCostMicros == null || maxCostMicros > 0)
        }
    }
}

internal class AgentGoalTracker(
    val goal: AgentGoal,
    private val startedAtMillis: Long,
) {
    enum class CriterionStatus {
        PENDING,
        PASSED,
        FAILED,
    }

    data class Evidence(
        val criterionId: String,
        val status: CriterionStatus,
        val summary: String,
        val source: String,
        val capturedAtMillis: Long,
    ) {
        init {
            require(summary.isNotBlank())
            require(source.isNotBlank())
        }
    }

    data class Usage(
        val steps: Int = 0,
        val tokens: Long = 0,
        val costMicros: Long = 0,
    )

    sealed interface State {
        data object Running : State
        data class Succeeded(val evidence: List<Evidence>) : State
        data class VerificationFailed(val failedCriteria: List<String>) : State
        data class BudgetExceeded(val reason: String) : State
        data class Blocked(val reason: String) : State
    }

    private val latestEvidenceByCriterion = linkedMapOf<String, Evidence>()
    private var usage = Usage()
    private var terminalState: State? = null

    fun recordEvidence(evidence: Evidence) {
        check(terminalState == null) { "goal 已终止，不能继续写入证据" }
        require(goal.successCriteria.any { it.id == evidence.criterionId }) {
            "未知 success criterion：${evidence.criterionId}"
        }
        latestEvidenceByCriterion[evidence.criterionId] = evidence
    }

    fun updateUsage(steps: Int, tokens: Long, costMicros: Long) {
        require(steps >= usage.steps) { "steps 不能回退" }
        require(tokens >= usage.tokens) { "tokens 不能回退" }
        require(costMicros >= usage.costMicros) { "cost 不能回退" }
        usage = Usage(steps, tokens, costMicros)
    }

    fun evaluate(nowMillis: Long): State {
        terminalState?.let { return it }

        // Once every success criterion has concrete PASSED evidence, the work was completed within
        // the previous execution step. Do not let the bookkeeping check performed at the start of
        // the next model round retroactively turn that verified success into BudgetExceeded.
        val allPassed = goal.successCriteria.all {
            latestEvidenceByCriterion[it.id]?.status == CriterionStatus.PASSED
        }
        if (allPassed) {
            return State.Succeeded(
                evidence = goal.successCriteria.map { criterion ->
                    latestEvidenceByCriterion.getValue(criterion.id)
                }
            ).also { terminalState = it }
        }

        budgetExceeded(nowMillis)?.let { reason ->
            return State.BudgetExceeded(reason).also { terminalState = it }
        }

        val failed = goal.successCriteria
            .filter { latestEvidenceByCriterion[it.id]?.status == CriterionStatus.FAILED }
            .map { it.id }
        if (failed.isNotEmpty()) return State.VerificationFailed(failed)

        return State.Running
    }

    fun block(reason: String): State.Blocked {
        require(reason.isNotBlank())
        check(terminalState == null) { "goal 已终止" }
        return State.Blocked(reason).also { terminalState = it }
    }

    fun usage(): Usage = usage

    fun evidence(): List<Evidence> = latestEvidenceByCriterion.values.toList()

    private fun budgetExceeded(nowMillis: Long): String? {
        val budget = goal.budget
        if (usage.steps >= budget.maxSteps) return "step budget exceeded"
        if (nowMillis - startedAtMillis >= budget.maxDurationMillis) return "time budget exceeded"
        if (budget.maxTokens != null && usage.tokens >= budget.maxTokens) return "token budget exceeded"
        if (budget.maxCostMicros != null && usage.costMicros >= budget.maxCostMicros) {
            return "cost budget exceeded"
        }
        return null
    }
}
