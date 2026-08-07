from pathlib import Path

path = Path('app/src/main/kotlin/fuck/andes/ui/app/AgentAppState.kt')
text = path.read_text()
old_ctor = """    skillZipImportGateway: SkillZipImportGateway? = null,
    startBackgroundWork: Boolean = true,
    initialConversationSnapshot: AgentConversationStore.Snapshot? = null,
) {"""
new_ctor = """    skillZipImportGateway: SkillZipImportGateway? = null,
    startBackgroundWork: Boolean = true,
    initialConversationSnapshot: AgentConversationStore.Snapshot? = null,
    probePlatformState: Boolean = startBackgroundWork,
) {"""
if old_ctor not in text:
    raise SystemExit('constructor anchor not found')
text = text.replace(old_ctor, new_ctor, 1)

old_state = """    var permissionHealthState by mutableStateOf(buildPermissionHealthState(appContext))
        private set

    var systemEnhanceState by mutableStateOf(buildSystemEnhanceState())
        private set
"""
new_state = """    var permissionHealthState by mutableStateOf(
        if (probePlatformState) buildPermissionHealthState(appContext) else PermissionHealthUiState(emptyList())
    )
        private set

    var systemEnhanceState by mutableStateOf(
        if (probePlatformState) buildSystemEnhanceState() else AgentSystemEnhanceUiState(emptyList())
    )
        private set
"""
if old_state not in text:
    raise SystemExit('platform state anchor not found')
text = text.replace(old_state, new_state, 1)
path.write_text(text)
