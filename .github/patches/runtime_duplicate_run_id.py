from pathlib import Path

path = Path('app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeService.kt')
text = path.read_text()
anchor = '''    private fun ingestRunRequest(
        incoming: AgentRuntimeWire.IncomingRunRequest,
        replyTo: Messenger?,
    ) {
        val pending = PendingStartRequest(
'''
replacement = '''    private fun ingestRunRequest(
        incoming: AgentRuntimeWire.IncomingRunRequest,
        replyTo: Messenger?,
    ) {
        val runId = incoming.request.runId
        if (
            AgentRunQueuePolicy.isDuplicateRunId(
                runId = runId,
                activeRunId = activeSession?.runId,
                ingestContainsRunId = pendingStartRequests.any {
                    it.incoming.request.runId == runId
                },
                queuedContainsRunId = runQueue.any { it.request.runId == runId },
            )
        ) {
            incoming.close()
            sendRequestIngestedTo(replyTo, runId)
            sendResultTo(
                replyTo,
                AgentRuntimeWire.RunResult(
                    runId = runId,
                    ok = false,
                    content = "",
                    error = "Agent Runtime 拒绝重复 runId",
                ),
            )
            AndroidAgentLogger.warn("Agent runtime rejected duplicate runId=$runId")
            return
        }

        val pending = PendingStartRequest(
'''
if anchor not in text:
    raise SystemExit('ingestRunRequest anchor not found')
path.write_text(text.replace(anchor, replacement, 1))
