package fuck.andes.agent.workspace

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProjectInstructionsTest {
    @Test
    fun loadsInstructionsFromRootToDeepestWorkingDirectory() {
        val root = Files.createTempDirectory("nova-agents").toFile()
        try {
            File(root, "AGENTS.md").writeText("root rules")
            val app = File(root, "app").apply { mkdirs() }
            File(app, "AGENTS.md").writeText("app rules")
            val feature = File(app, "src/feature").apply { mkdirs() }
            File(feature, "AGENTS.md").writeText("feature rules")

            val result = AgentProjectInstructions.load(root.path, feature.path)

            assertEquals(listOf("root rules", "app rules", "feature rules"), result.layers.map { it.content })
            assertFalse(result.truncated)
            assertEquals(null, result.error)
            val rendered = result.renderForModel()
            assertTrue(rendered.indexOf("root rules") < rendered.indexOf("app rules"))
            assertTrue(rendered.indexOf("app rules") < rendered.indexOf("feature rules"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unrelatedSiblingDirectoryDoesNotLeakInstructions() {
        val root = Files.createTempDirectory("nova-agents").toFile()
        try {
            val app = File(root, "app").apply { mkdirs() }
            val other = File(root, "other").apply { mkdirs() }
            File(app, "AGENTS.md").writeText("app only")
            File(other, "AGENTS.md").writeText("other only")
            val feature = File(app, "feature").apply { mkdirs() }

            val result = AgentProjectInstructions.load(root.path, feature.path)

            assertEquals(listOf("app only"), result.layers.map { it.content })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun workingDirectoryOutsideRepositoryIsRejected() {
        val root = Files.createTempDirectory("nova-root").toFile()
        val outside = Files.createTempDirectory("nova-outside").toFile()
        try {
            val result = AgentProjectInstructions.load(root.path, outside.path)

            assertTrue(result.layers.isEmpty())
            assertNotNull(result.error)
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun oversizedLayerIsBoundedAndMarkedTruncated() {
        val root = Files.createTempDirectory("nova-agents").toFile()
        try {
            File(root, "AGENTS.md").writeText("x".repeat(70 * 1024))

            val result = AgentProjectInstructions.load(root.path, root.path)

            assertEquals(1, result.layers.size)
            assertTrue(result.truncated)
            assertTrue(result.layers.single().content.toByteArray().size <= 64 * 1024)
        } finally {
            root.deleteRecursively()
        }
    }
}
