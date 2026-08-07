from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str):
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f'{label} anchor not found in {path}')
    p.write_text(text.replace(old, new, 1))

# Durable workspace registry in every per-run tool facade.
replace_once(
    'app/src/main/kotlin/fuck/andes/agent/tool/AgentWorkspaceTools.kt',
    '    private val workspaceRegistry: AgentWorkspaceRegistry = AgentWorkspaceRuntimeRegistry.shared,\n',
    '    private val workspaceRegistry: AgentWorkspaceRegistry = AgentWorkspaceRuntimeRegistry.get(context),\n',
    'workspace registry default',
)

# Conversation state owns a stable workspace binding.
replace_once(
    'app/src/main/kotlin/fuck/andes/ui/model/AgentChatUiState.kt',
    '    val history: List<AgentModelClient.ConversationMessage> = emptyList(),\n    val input: String,\n',
    '    val history: List<AgentModelClient.ConversationMessage> = emptyList(),\n    val workspaceId: String? = null,\n    val input: String,\n',
    'chat workspace field',
)

# Room schema v12.
replace_once(
    'app/src/main/kotlin/fuck/andes/data/db/ConversationEntities.kt',
    '''    @ColumnInfo(name = "reasoning_effort", defaultValue = "'default'")
    val reasoningEffort: String = ReasoningEffort.DEFAULT.wireValue,
    @ColumnInfo(name = "history_json") val historyJson: String = "[]",
''',
    '''    @ColumnInfo(name = "reasoning_effort", defaultValue = "'default'")
    val reasoningEffort: String = ReasoningEffort.DEFAULT.wireValue,
    @ColumnInfo(name = "workspace_id") val workspaceId: String? = null,
    @ColumnInfo(name = "history_json") val historyJson: String = "[]",
''',
    'conversation workspace column',
)
replace_once(
    'app/src/main/kotlin/fuck/andes/data/db/FuckAndesDatabase.kt',
    '    version = 11,\n',
    '    version = 12,\n',
    'database version',
)
replace_once(
    'app/src/main/kotlin/fuck/andes/data/db/FuckAndesDatabase.kt',
    '                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)\n',
    '                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)\n',
    'database migration registration',
)
replace_once(
    'app/src/main/kotlin/fuck/andes/data/db/FuckAndesDatabase.kt',
    '''        internal val MIGRATION_10_11 = Migration(10, 11) { database ->
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN vision_verified INTEGER"
            )
        }
''',
    '''        internal val MIGRATION_10_11 = Migration(10, 11) { database ->
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN vision_verified INTEGER"
            )
        }

        internal val MIGRATION_11_12 = Migration(11, 12) { database ->
            database.execSQL("ALTER TABLE conversations ADD COLUMN workspace_id TEXT")
        }
''',
    'database 11 to 12 migration',
)

# Conversation persistence.
replace_once(
    'app/src/main/kotlin/fuck/andes/ui/app/AgentConversationStore.kt',
    '                        reasoningEffort = state.reasoningEffort.wireValue,\n                        historyJson = json.encodeToString(state.history),\n',
    '                        reasoningEffort = state.reasoningEffort.wireValue,\n                        workspaceId = state.workspaceId,\n                        historyJson = json.encodeToString(state.history),\n',
    'store workspace save',
)
replace_once(
    'app/src/main/kotlin/fuck/andes/ui/app/AgentConversationStore.kt',
    '                appliedRuntimeRunIds = conversation.appliedRuntimeRunIdsJson.toStringList(),\n                input = "",\n',
    '                appliedRuntimeRunIds = conversation.appliedRuntimeRunIdsJson.toStringList(),\n                workspaceId = conversation.workspaceId,\n                input = "",\n',
    'store workspace load',
)

# IPC carries only the stable workspace ID.
replace_once(
    'app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeWire.kt',
    '    private const val KEY_RUN_ID = "run_id"\n    private const val KEY_SUPPLEMENT_TEXT = "supplement_text"\n',
    '    private const val KEY_RUN_ID = "run_id"\n    private const val KEY_WORKSPACE_ID = "workspace_id"\n    private const val KEY_SUPPLEMENT_TEXT = "supplement_text"\n',
    'runtime workspace key',
)
replace_once(
    'app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeWire.kt',
    '        val history: List<AgentModelClient.ConversationMessage> = emptyList(),\n        val handoff: EntryHandoff? = null\n    )\n',
    '        val history: List<AgentModelClient.ConversationMessage> = emptyList(),\n        val handoff: EntryHandoff? = null,\n        val workspaceId: String? = null,\n    )\n',
    'runtime request workspace field',
)
replace_once(
    'app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeWire.kt',
    '        putString(KEY_RUN_ID, request.runId)\n        putString(KEY_PROMPT, request.prompt)\n',
    '        putString(KEY_RUN_ID, request.runId)\n        request.workspaceId?.takeIf(String::isNotBlank)?.let { putString(KEY_WORKSPACE_ID, it) }\n        putString(KEY_PROMPT, request.prompt)\n',
    'runtime workspace bundle write',
)
replace_once(
    'app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeWire.kt',
    '            images = images,\n            handoff = bundle.getBundle(KEY_HANDOFF)?.let(::entryHandoffFromBundle)\n        )\n',
    '            images = images,\n            handoff = bundle.getBundle(KEY_HANDOFF)?.let(::entryHandoffFromBundle),\n            workspaceId = bundle.getString(KEY_WORKSPACE_ID)?.trim()?.takeIf(String::isNotBlank),\n        )\n',
    'runtime workspace bundle read',
)

# Conversation API binds and propagates workspace identity.
replace_once(
    'app/src/main/kotlin/fuck/andes/ui/app/AgentAppState.kt',
    '''    fun renameConversation(conversationId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        conversationTitles = conversationTitles + (conversationId to trimmed)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        refreshConversationSummaries()
        persistConversations()
    }

    fun sendCurrentMessage() {
''',
    '''    fun renameConversation(conversationId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        conversationTitles = conversationTitles + (conversationId to trimmed)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        refreshConversationSummaries()
        persistConversations()
    }

    fun bindCurrentWorkspace(workspaceId: String?) {
        val normalized = workspaceId?.trim()?.takeIf(String::isNotBlank)
        if (homeState.workspaceId == normalized) return
        updateCurrentConversation(homeState.copy(workspaceId = normalized))
        selectedConversationId?.let {
            conversationUpdatedAt = conversationUpdatedAt + (it to System.currentTimeMillis())
            refreshConversationSummaries()
            persistConversations()
        }
    }

    fun sendCurrentMessage() {
''',
    'app state bind workspace method',
)
replace_once(
    'app/src/main/kotlin/fuck/andes/ui/app/AgentAppState.kt',
    '        val history = homeState.history\n        val reasoningEffort = homeState.reasoningEffort\n',
    '        val history = homeState.history\n        val workspaceId = homeState.workspaceId\n        val reasoningEffort = homeState.reasoningEffort\n',
    'capture workspace for run',
)
replace_once(
    'app/src/main/kotlin/fuck/andes/ui/app/AgentAppState.kt',
    '                    history = history,\n                    handoff = AgentRuntimeWire.EntryHandoff(\n',
    '                    history = history,\n                    workspaceId = workspaceId,\n                    handoff = AgentRuntimeWire.EntryHandoff(\n',
    'runtime request workspace propagation',
)
replace_once(
    'app/src/main/kotlin/fuck/andes/ui/app/AgentAppState.kt',
    '            pendingImages = draft.pendingImages,\n        )\n',
    '            pendingImages = draft.pendingImages,\n            workspaceId = draft.workspaceId,\n        )\n',
    'draft workspace preservation',
)

# Runtime resolves workspace and AGENTS before the first model request.
replace_once(
    'app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeRunExecutor.kt',
    'import fuck.andes.agent.tool.ToolExecutionDecision\n',
    'import fuck.andes.agent.tool.ToolExecutionDecision\nimport fuck.andes.agent.workspace.AgentWorkspaceContextBuilder\n',
    'runtime workspace context import',
)
replace_once(
    'app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeRunExecutor.kt',
    '            entrySurfaceGuard = EntrySurfaceGuard.from(request.handoff, AndroidAgentLogger)\n            val skillIndexService = SkillRuntime.createIndexService(appContext)\n',
    '            entrySurfaceGuard = EntrySurfaceGuard.from(request.handoff, AndroidAgentLogger)\n            val runConfig = AgentWorkspaceContextBuilder.augment(\n                context = appContext,\n                workspaceId = request.workspaceId,\n                config = request.config,\n            )\n            val skillIndexService = SkillRuntime.createIndexService(appContext)\n',
    'runtime workspace context resolve',
)
replace_once(
    'app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeRunExecutor.kt',
    '                        contextWindow = request.config.contextWindow,\n',
    '                        contextWindow = runConfig.contextWindow,\n',
    'runtime memory context window',
)
replace_once(
    'app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeRunExecutor.kt',
    '                    AgentMemoryContextBuilder.empty(request.config.contextWindow)\n',
    '                    AgentMemoryContextBuilder.empty(runConfig.contextWindow)\n',
    'runtime empty memory context window',
)
replace_once(
    'app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeRunExecutor.kt',
    '                config = request.config,\n                prompt = request.prompt,\n',
    '                config = runConfig,\n                prompt = request.prompt,\n',
    'runtime augmented model config',
)

# Existing v6 migration test now proves the full 6 -> 12 chain and null legacy binding.
replace_once(
    'app/src/test/java/fuck/andes/data/db/FuckAndesDatabaseMigrationTest.kt',
    '    fun migration6To11PreservesDataAndMigratesReasoningEffort() {\n',
    '    fun migration6To12PreservesDataMigratesReasoningEffortAndAddsWorkspaceBinding() {\n',
    'migration test name',
)
replace_once(
    'app/src/test/java/fuck/andes/data/db/FuckAndesDatabaseMigrationTest.kt',
    '                FuckAndesDatabase.MIGRATION_9_10,\n                FuckAndesDatabase.MIGRATION_10_11,\n',
    '                FuckAndesDatabase.MIGRATION_9_10,\n                FuckAndesDatabase.MIGRATION_10_11,\n                FuckAndesDatabase.MIGRATION_11_12,\n',
    'migration test registration',
)
replace_once(
    'app/src/test/java/fuck/andes/data/db/FuckAndesDatabaseMigrationTest.kt',
    '            assertEquals("[]", conversations.first { it.id == "conv-1" }.appliedRuntimeRunIdsJson)\n            assertEquals("off", conversations.first { it.id == "conv-1" }.reasoningEffort)\n',
    '            assertEquals("[]", conversations.first { it.id == "conv-1" }.appliedRuntimeRunIdsJson)\n            assertEquals(null, conversations.first { it.id == "conv-1" }.workspaceId)\n            assertEquals("off", conversations.first { it.id == "conv-1" }.reasoningEffort)\n',
    'migration workspace null assertion',
)
