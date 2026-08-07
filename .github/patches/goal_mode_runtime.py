from pathlib import Path

# Goal session: count only rounds after goal activation, not the whole Agent run.
goal = Path('app/src/main/kotlin/fuck/andes/agent/goal/AgentGoalSession.kt')
text = goal.read_text()
anchor = '    private var tracker: AgentGoalTracker? = null\n'
replacement = anchor + '    private var goalRoundCount: Int = 0\n'
if anchor not in text:
    raise SystemExit('goal tracker anchor not found')
text = text.replace(anchor, replacement, 1)
old = '''    fun onRound(round: Int): CompletionDecision {
        val active = tracker ?: return CompletionDecision.Allow
        val previous = active.usage()
        active.updateUsage(
            steps = maxOf(previous.steps, round),
            tokens = previous.tokens,
            costMicros = previous.costMicros,
        )
        return stateToDecision(active.evaluate(clock()))
    }
'''
new = '''    fun onRound(): CompletionDecision {
        val active = tracker ?: return CompletionDecision.Allow
        goalRoundCount += 1
        val previous = active.usage()
        active.updateUsage(
            steps = maxOf(previous.steps, goalRoundCount),
            tokens = previous.tokens,
            costMicros = previous.costMicros,
        )
        return stateToDecision(active.evaluate(clock()))
    }
'''
if old not in text:
    raise SystemExit('goal onRound anchor not found')
text = text.replace(old, new, 1)
text = text.replace('        tracker = AgentGoalTracker(goal = goal, startedAtMillis = clock())\n', '        goalRoundCount = 0\n        tracker = AgentGoalTracker(goal = goal, startedAtMillis = clock())\n', 1)
goal.write_text(text)

# AgentModelClient: create one goal session per run and wrap normal tools.
client = Path('app/src/main/kotlin/fuck/andes/agent/model/AgentModelClient.kt')
text = client.read_text()
import_anchor = 'import fuck.andes.agent.runtime.AgentEvent\n'
imports = 'import fuck.andes.agent.goal.AgentGoalSession\nimport fuck.andes.agent.goal.AgentGoalToolExecutor\n' + import_anchor
if import_anchor not in text:
    raise SystemExit('model client import anchor not found')
text = text.replace(import_anchor, imports, 1)
loop_anchor = '''        val loop = AgentLoop(
            config = config,
            messages = messages,
            tools = tools,
            provider = provider,
            toolExecutor = toolExecutor,
'''
loop_replacement = '''        val goalSession = AgentGoalSession()
        val effectiveToolExecutor = AgentGoalToolExecutor(goalSession, toolExecutor)
        val loop = AgentLoop(
            config = config,
            messages = messages,
            tools = tools,
            provider = provider,
            toolExecutor = effectiveToolExecutor,
'''
if loop_anchor not in text:
    raise SystemExit('model client loop anchor not found')
text = text.replace(loop_anchor, loop_replacement, 1)
vision_anchor = '''            visionRouter = visionConfig
                ?.takeIf { it.modelSupportsVision && it.baseUrl.isNotBlank() && it.model.isNotBlank() }
                ?.let { ProviderVisionRouter(it) },
        )
'''
vision_replacement = '''            visionRouter = visionConfig
                ?.takeIf { it.modelSupportsVision && it.baseUrl.isNotBlank() && it.model.isNotBlank() }
                ?.let { ProviderVisionRouter(it) },
            goalSession = goalSession,
        )
'''
if vision_anchor not in text:
    raise SystemExit('model client vision anchor not found')
text = text.replace(vision_anchor, vision_replacement, 1)
client.write_text(text)

# AgentLoop: enforce budget at round boundaries and evidence gate before natural completion.
loop = Path('app/src/main/kotlin/fuck/andes/agent/model/AgentLoop.kt')
text = loop.read_text()
import_anchor = 'package fuck.andes.agent.model\n\n'
if import_anchor not in text:
    raise SystemExit('loop package anchor not found')
text = text.replace(import_anchor, import_anchor + 'import fuck.andes.agent.goal.AgentGoalSession\n', 1)
ctor_anchor = '''    private val modelSupportsVision: Boolean = false,
    private val visionRouter: VisionRouter? = null,
    private val limits: Limits = Limits(),
) {
'''
ctor_replacement = '''    private val modelSupportsVision: Boolean = false,
    private val visionRouter: VisionRouter? = null,
    private val goalSession: AgentGoalSession? = null,
    private val limits: Limits = Limits(),
) {
'''
if ctor_anchor not in text:
    raise SystemExit('loop constructor anchor not found')
text = text.replace(ctor_anchor, ctor_replacement, 1)
round_anchor = '''            checkRoundLimit(round)
            runController.throwIfCancelled()
            appendPendingSteeringMessage()
'''
round_replacement = '''            checkRoundLimit(round)
            runController.throwIfCancelled()
            when (val goalDecision = goalSession?.onRound()) {
                is AgentGoalSession.CompletionDecision.Fail -> error(goalDecision.message)
                else -> Unit
            }
            appendPendingSteeringMessage()
'''
if round_anchor not in text:
    raise SystemExit('loop round anchor not found')
text = text.replace(round_anchor, round_replacement, 1)
finish_anchor = '''            onEvent(AgentEvent.RunFinished(round = round, contentChars = content.length))
            return Result(
'''
finish_replacement = '''            when (val goalDecision = goalSession?.completionDecision()) {
                null, AgentGoalSession.CompletionDecision.Allow -> Unit
                is AgentGoalSession.CompletionDecision.Continue -> {
                    messages.put(AgentConversationCodec.userTextMessage(goalDecision.feedback))
                    round += 1
                    continue
                }
                is AgentGoalSession.CompletionDecision.Fail -> error(goalDecision.message)
            }

            onEvent(AgentEvent.RunFinished(round = round, contentChars = content.length))
            return Result(
'''
if finish_anchor not in text:
    raise SystemExit('loop finish anchor not found')
text = text.replace(finish_anchor, finish_replacement, 1)
loop.write_text(text)
