from pathlib import Path


def replace(path: str, old: str, new: str, label: str):
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f'{label} anchor not found in {path}')
    p.write_text(text.replace(old, new, 1))

# Workspace tools must use the durable per-process registry instance bound to the app files dir.
replace(
    'app/src/main/kotlin/fuck/andes/agent/tool/AgentWorkspaceTools.kt',
    '    private val workspaceRegistry: AgentWorkspaceRegistry = AgentWorkspaceRuntimeRegistry.shared,\n',
    '    private val workspaceRegistry: AgentWorkspaceRegistry = AgentWorkspaceRuntimeRegistry.get(context),\n',
    'workspace registry default',
)

# Conversation UI state owns the workspace association so it follows the conversation across turns.
replace(
    'app/src/main/kotlin/fuck/andes/ui/model/AgentChatUiState.kt',
    '    val history: List<AgentModelClient.ConversationMessage> = emptyList(),\n    val input: String,\n',
    '    val history: List<AgentModelClient.ConversationMessage> = emptyList(),\n    val workspaceId: String? = null,\n    val input: String,\n',
    'chat workspace field',
)

# Room schema v12.
replace(
    'app/src/main/kotlin/fuck/andes/data/db/ConversationEntities.kt',
    '    @ColumnInfo(name = "reasoning_effort")\n    val reasoningEffort: String = ReasoningEffort.DEFAULT.wireValue,\n    @ColumnInfo(name = "history_json")\n',
    '    @ColumnInfo(name = "reasoning_effort")\n    val reasoningEffort: String = ReasoningEffort.DEFAULT.wireValue,\n    @ColumnInfo(name = "workspace_id")\n    val workspaceId: String? = null,\n    @ColumnInfo(name = "history_json")\n',
    'conversation workspace column',
)
replace(
    'app/src/main/kotlin/fuck/andes/data/db/FuckAndesDatabase.kt',
    '    version = 11,\n',
    '    version = 12,\n',
    'database version',
)
replace(
    'app/src/main/kotlin/fuck/andes/data/db/FuckAndesDatabase.kt',
    '                MIGRATION_9_10,\n                MIGRATION_10_11,\n',
    '                MIGRATION_9_10,\n                MIGRATION_10_11,\n                MIGRATION_11_12,\n',
    'database migration registration',
)
replace(
    'app/src/main/kotlin/fuck/andes/data/db/FuckAndesDatabase.kt',
    '''        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE conversations ADD COLUMN reasoning_effort TEXT NOT NULL DEFAULT 'default'"
                )
                connection.execSQL(
                    "UPDATE conversations SET reasoning_effort = " +
                        "CASE WHEN thinking_enabled != 0 THEN 'medium' ELSE 'off' END"
                )
            }
        }
''',
    '''        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE conversations ADD COLUMN reasoning_effort TEXT NOT NULL DEFAULT 'default'"
                )
                connection.execSQL(
                    "UPDATE conversations SET reasoning_effort = " +
                        "CASE WHEN thinking_enabled != 0 THEN 'medium' ELSE 'off' END"
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE conversations ADD COLUMN workspace_id TEXT"
                )
            }
        }
''',
    'database 11 to 12 migration',
)

# Conversation store round-trips workspace identity.
replace(
    'app/src/main/kotlin/fuck/andes/ui/app/AgentConversationStore.kt',
    '                        reasoningEffort = state.reasoningEffort.wireValue,\n                        historyJson = json.encodeToString(state.history),\n',
    '                        reasoningEffort = state.reasoningEffort.wireValue,\n                        workspaceId = state.workspaceId,\n                        historyJson = json.encodeToString(state.history),\n',
    'store workspace save',
)
replace(
    'app/src/main/kotlin/fuck/andes/ui/app/AgentConversationStore.kt',
    '                appliedRuntimeRunIds = conversation.appliedRuntimeRunIdsJson.toStringList(),\n                input = "",\n',
    '                appliedRuntimeRunIds = conversation.appliedRuntimeRunIdsJson.toStringList(),\n                workspaceId = conversation.workspaceId,\n                input = "",\n',
    'store workspace load',
)

# Runtime IPC carries only the stable workspace reference.
replace(
    'app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeWire.kt',
    '    private const val KEY_RUN_ID = "run_id"\n    private const val KEY_SUPPLEMENT_TEXT = "supplement_text"\n',
    '    private const val KEY_RUN_ID = "run_id"\n    private const val KEY_WORKSPACE_ID = "workspace_id"\n    private const val KEY_SUPPLEMENT_TEXT = "supplement_text"\n',
    'runtime workspace key',
)
replace(
    'app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeWire.kt',
    '        val history: List<AgentModelClient.ConversationMessage> = emptyList(),\n        val handoff: EntryHandoff? = null\n    )\n',
    '        val history: List<AgentModelClient.ConversationMessage> = emptyList(),\n        val handoff: EntryHandoff? = null,\n        val workspaceId: String? = null,\n    )\n',
    'runtime request workspace field',
)
replace(
    'app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeWire.kt',
    '        putString(KEY_RUN_ID, request.runId)\n        putString(KEY_PROMPT, request.prompt)\n',
    '        putString(KEY_RUN_ID, request.runId)\n        request.workspaceId?.takeIf(String::isNotBlank)?.let { putString(KEY_WORKSPACE_ID, it) }\n        putString(KEY_PROMPT, request.prompt)\n',
    'runtime workspace bundle write',
)
replace(
    'app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeWire.kt',
    '            images = images,\n            handoff = bundle.getBundle(KEY_HANDOFF)?.let(::entryHandoffFromBundle)\n        )\n',
    '            images = images,\n            handoff = bundle.getBundle(KEY_HANDOFF)?.let(::entryHandoffFromBundle),\n            workspaceId = bundle.getString(KEY_WORKSPACE_ID)?.trim()?.takeIf(String::isNotBlank),\n        )\n',
    'runtime workspace bundle read',
)

# UI conversation binding and request propagation.
replace(
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
replace(
    'app/src/main/kotlin/fuck/andes/ui/app/AgentAppState.kt',
    '        val history = homeState.history\n        val reasoningEffort = homeState.reasoningEffort\n',
    '        val history = homeState.history\n        val workspaceId = homeState.workspaceId\n        val reasoningEffort = homeState.reasoningEffort\n',
    'capture workspace for run',
)
replace(
    'app/src/main/kotlin/fuck/andes/ui/app/AgentAppState.kt',
    '                    history = history,\n                    handoff = AgentRuntimeWire.EntryHandoff(\n',
    '                    history = history,\n                    workspaceId = workspaceId,\n                    handoff = AgentRuntimeWire.EntryHandoff(\n',
    'runtime request workspace propagation',
)
replace(
    'app/src/main/kotlin/fuck/andes/ui/app/AgentAppState.kt',
    '            pendingImages = draft.pendingImages,\n        )\n',
    '            pendingImages = draft.pendingImages,\n            workspaceId = draft.workspaceId,\n        )\n',
    'draft workspace preservation',
)

# First model request gets workspace metadata and layered AGENTS without mutating provider config.
replace(
    'app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeRunExecutor.kt',
    'import fuck.andes.agent.tool.ToolExecutionDecision\n',
    'import fuck.andes.agent.tool.ToolExecutionDecision\nimport fuck.andes.agent.workspace.AgentWorkspaceContextBuilder\n',
    'runtime workspace context import',
)
replace(
    'app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeRunExecutor.kt',
    '            entrySurfaceGuard = EntrySurfaceGuard.from(request.handoff, AndroidAgentLogger)\n            val skillIndexService = SkillRuntime.createIndexService(appContext)\n',
    '            entrySurfaceGuard = EntrySurfaceGuard.from(request.handoff, AndroidAgentLogger)\n            val runConfig = AgentWorkspaceContextBuilder.augment(\n                context = appContext,\n                workspaceId = request.workspaceId,\n                config = request.config,\n            )\n            val skillIndexService = SkillRuntime.createIndexService(appContext)\n',
    'runtime workspace context resolve',
)
replace(
    'app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeRunExecutor.kt',
    '                        contextWindow = request.config.contextWindow,\n',
    '                        contextWindow = runConfig.contextWindow,\n',
    'runtime memory context window',
)
replace(
    'app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeRunExecutor.kt',
    '                    AgentMemoryContextBuilder.empty(request.config.contextWindow)\n',
    '                    AgentMemoryContextBuilder.empty(runConfig.contextWindow)\n',
    'runtime empty memory context window',
)
replace(
    'app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeRunExecutor.kt',
    '                config = request.config,\n                prompt = request.prompt,\n',
    '                config = runConfig,\n                prompt = request.prompt,\n',
    'runtime augmented model config',
)

# Migration test advances the complete chain to v12 and verifies legacy rows remain unbound.
replace(
    'app/src/test/java/fuck/andes/data/db/FuckAndesDatabaseMigrationTest.kt',
    '    fun migration6To11PreservesConversationHistoryAndPendingRuntimeIds() {\n',
    '    fun migration6To12PreservesConversationHistoryPendingRuntimeIdsAndAddsWorkspaceBinding() {\n',
    'migration test name',
)
replace(
    'app/src/test/java/fuck/andes/data/db/FuckAndesDatabaseMigrationTest.kt',
    '                FuckAndesDatabase.MIGRATION_9_10,\n                FuckAndesDatabase.MIGRATION_10_11,\n',
    '                FuckAndesDatabase.MIGRATION_9_10,\n                FuckAndesDatabase.MIGRATION_10_11,\n                FuckAndesDatabase.MIGRATION_11_12,\n',
    'migration test registration',
)
replace(
    'app/src/test/java/fuck/andes/data/db/FuckAndesDatabaseMigrationTest.kt',
    '        helper.runMigrationsAndValidate(TEST_DB, 11, true, databaseClass = FuckAndesDatabase::class.java).close()\n',
    '        helper.runMigrationsAndValidate(TEST_DB, 12, true, databaseClass = FuckAndesDatabase::class.java).close()\n',
    'migration target version',
)
replace(
    'app/src/test/java/fuck/andes/data/db/FuckAndesDatabaseMigrationTest.kt',
    '''            connection.prepare(
                "SELECT history_json, applied_runtime_run_ids_json, reasoning_effort FROM conversations WHERE id = 'conv-1'"
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("[]", statement.getText(0))
                assertEquals("[]", statement.getText(1))
                assertEquals("medium", statement.getText(2))
            }
''',
    '''            connection.prepare(
                "SELECT history_json, applied_runtime_run_ids_json, reasoning_effort, workspace_id FROM conversations WHERE id = 'conv-1'"
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("[]", statement.getText(0))
                assertEquals("[]", statement.getText(1))
                assertEquals("medium", statement.getText(2))
                assertTrue(statement.isNull(3))
            }
''',
    'migration workspace null assertion',
)
