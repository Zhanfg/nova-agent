package fuck.andes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleConfigEntryPackagesTest {
    @Test
    fun assistantPackagesRemainHookTargets() {
        assertEquals("com.heytap.speechassist", ModuleConfig.BREENO_PACKAGE)
        assertEquals("com.miui.voiceassist", ModuleConfig.XIAOAI_PACKAGE)
        assertTrue(ModuleConfig.XIAOAI_CORE_PROCESS.endsWith(":core"))
    }
}
