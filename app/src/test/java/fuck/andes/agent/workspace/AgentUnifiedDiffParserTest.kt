package fuck.andes.agent.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentUnifiedDiffParserTest {
    @Test
    fun parsesModifiedFileAndLineNumbers() {
        val diff = """
            diff --git a/app/Main.kt b/app/Main.kt
            index 1111111..2222222 100644
            --- a/app/Main.kt
            +++ b/app/Main.kt
            @@ -10,3 +10,4 @@ fun main() {
             before()
            -oldCall()
            +newCall()
            +verify()
             after()
        """.trimIndent()

        val change = AgentUnifiedDiffParser.parse(diff).files.single()
        val hunk = change.hunks.single()

        assertEquals(AgentGitChangeSet.Status.MODIFIED, change.status)
        assertEquals("app/Main.kt", change.oldPath)
        assertEquals("app/Main.kt", change.newPath)
        assertEquals(10, hunk.oldStart)
        assertEquals(10, hunk.newStart)
        assertEquals(
            listOf(
                AgentGitChangeSet.LineKind.CONTEXT,
                AgentGitChangeSet.LineKind.REMOVED,
                AgentGitChangeSet.LineKind.ADDED,
                AgentGitChangeSet.LineKind.ADDED,
                AgentGitChangeSet.LineKind.CONTEXT,
            ),
            hunk.lines.map { it.kind },
        )
        assertEquals(11, hunk.lines[1].oldLine)
        assertNull(hunk.lines[1].newLine)
        assertEquals(11, hunk.lines[2].newLine)
        assertNull(hunk.lines[2].oldLine)
        assertEquals(13, hunk.lines.last().newLine)
    }

    @Test
    fun recognizesAddedDeletedAndRenamedFiles() {
        val diff = """
            diff --git a/new.txt b/new.txt
            new file mode 100644
            --- /dev/null
            +++ b/new.txt
            @@ -0,0 +1 @@
            +hello
            diff --git a/old.txt b/old.txt
            deleted file mode 100644
            --- a/old.txt
            +++ /dev/null
            @@ -1 +0,0 @@
            -bye
            diff --git a/old-name.kt b/new-name.kt
            similarity index 100%
            rename from old-name.kt
            rename to new-name.kt
        """.trimIndent()

        val files = AgentUnifiedDiffParser.parse(diff).files

        assertEquals(
            listOf(
                AgentGitChangeSet.Status.ADDED,
                AgentGitChangeSet.Status.DELETED,
                AgentGitChangeSet.Status.RENAMED,
            ),
            files.map { it.status },
        )
        assertNull(files[0].oldPath)
        assertNull(files[1].newPath)
        assertEquals("old-name.kt", files[2].oldPath)
        assertEquals("new-name.kt", files[2].newPath)
    }

    @Test
    fun preservesNestedPathWhoseFirstRealDirectoryIsB() {
        val diff = """
            diff --git a/a/b/file.kt b/a/b/file.kt
            --- a/a/b/file.kt
            +++ b/a/b/file.kt
            @@ -1 +1 @@
            -old
            +new
        """.trimIndent()

        val change = AgentUnifiedDiffParser.parse(diff).files.single()

        assertEquals("a/b/file.kt", change.oldPath)
        assertEquals("a/b/file.kt", change.newPath)
    }

    @Test
    fun parsesQuotedSpaceAndUnicodePathsWithoutCorruption() {
        val diff = """
            diff --git "a/docs/foo bar.md" "b/docs/foo bar.md"
            --- "a/docs/foo bar.md"
            +++ "b/docs/foo bar.md"
            @@ -1 +1 @@
            -old
            +new
            diff --git a/文档/说明.md b/文档/说明.md
            --- a/文档/说明.md
            +++ b/文档/说明.md
            @@ -1 +1 @@
            -旧
            +新
        """.trimIndent()

        val files = AgentUnifiedDiffParser.parse(diff).files

        assertEquals("docs/foo bar.md", files[0].oldPath)
        assertEquals("docs/foo bar.md", files[0].newPath)
        assertEquals("文档/说明.md", files[1].oldPath)
        assertEquals("文档/说明.md", files[1].newPath)
    }

    @Test
    fun emptyDiffProducesEmptyReview() {
        assertTrue(AgentUnifiedDiffParser.parse("").files.isEmpty())
    }

    @Test
    fun noNewlineMarkerIsPreservedAsMetaLine() {
        val diff = """
            diff --git a/a.txt b/a.txt
            --- a/a.txt
            +++ b/a.txt
            @@ -1 +1 @@
            -a
            +b
            \ No newline at end of file
        """.trimIndent()

        val lines = AgentUnifiedDiffParser.parse(diff).files.single().hunks.single().lines
        assertEquals(AgentGitChangeSet.LineKind.META, lines.last().kind)
    }
}
