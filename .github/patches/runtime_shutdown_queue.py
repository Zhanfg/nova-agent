from pathlib import Path

path = Path('app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeService.kt')
text = path.read_text()
anchor = '''        activeSession?.cancel("Agent Runtime 服务已停止")
        activeSession = null
'''
replacement = '''        AgentRunQueuePolicy.drain(runQueue).forEach { queued ->
            sendResultTo(
                queued.replyTo,
                AgentRuntimeWire.RunResult(
                    runId = queued.request.runId,
                    ok = false,
                    content = "",
                    error = "Agent Runtime 服务已停止",
                ),
            )
        }
        activeSession?.cancel("Agent Runtime 服务已停止")
        activeSession = null
'''
if anchor not in text:
    raise SystemExit('runtime onDestroy anchor not found')
path.write_text(text.replace(anchor, replacement, 1))
