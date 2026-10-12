package com.iaeluk.hermex.ui.workspace

import com.iaeluk.hermex.core.model.WorkspaceEntry
import com.iaeluk.hermex.core.model.WorkspaceRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTreePolicyTest {

    @Test
    fun openExternallyAbove25MB() {
        assertTrue(WorkspaceFilePreviewPolicy.shouldOpenExternally(26L * 1024 * 1024))
        assertFalse(WorkspaceFilePreviewPolicy.shouldOpenExternally(1024))
    }

    @Test
    fun searchKeepsAncestorOnDescendantMatch() {
        assertTrue(WorkspaceTreePolicy.isVisible(folder = "a", descendants = listOf("a/b/report.md"), query = "report"))
    }

    @Test
    fun multiWordQueryMatchesInAnyOrder() {
        assertTrue(WorkspaceTreePolicy.matchesQuery(name = "quarterly report.md", path = "docs/quarterly report.md", query = "report quarterly"))
        assertFalse(WorkspaceTreePolicy.matchesQuery(name = "quarterly report.md", path = "docs/quarterly report.md", query = "report annual"))
    }

    @Test
    fun naturalSortOrdersFile2BeforeFile10() {
        assertTrue(WorkspaceTreePolicy.compareNatural("file2.md", "file10.md") < 0)
        assertEquals(0, WorkspaceTreePolicy.compareNatural("a", "a"))
        assertTrue(WorkspaceTreePolicy.compareNatural("b", "a") > 0)
    }

    @Test
    fun flattenShowsOpenFoldersNested() {
        val roots = listOf(WorkspaceRoot(path = "/app", name = "app"))
        val entries = listOf(
            WorkspaceEntry(name = "src", path = "/app/src", type = "directory"),
            WorkspaceEntry(name = "a.md", path = "/app/a.md", type = "file"),
        )
        val rows = WorkspaceTreePolicy.flattenTree(roots, setOf("/app"), mapOf("/app" to entries), "")
        assertEquals(3, rows.size)
        assertEquals(0, rows[0].depth)
        assertEquals("/app/src", rows[1].entry.path)
        assertEquals(1, rows[1].depth)
        assertEquals("/app/a.md", rows[2].entry.path)
    }

    @Test
    fun flattenFiltersByQueryKeepingAncestors() {
        val roots = listOf(WorkspaceRoot(path = "/app", name = "app"))
        val entries = listOf(
            WorkspaceEntry(name = "notes.md", path = "/app/notes.md", type = "file"),
            WorkspaceEntry(name = "docs", path = "/app/docs", type = "directory"),
        )
        val docsEntries = listOf(WorkspaceEntry(name = "report.md", path = "/app/docs/report.md", type = "file"))
        val rows = WorkspaceTreePolicy.flattenTree(
            roots,
            setOf("/app", "/app/docs"),
            mapOf("/app" to entries, "/app/docs" to docsEntries),
            "report",
        )
        val paths = rows.map { it.entry.path }
        assertTrue(paths.contains("/app/docs/report.md"))
        assertTrue(paths.contains("/app/docs"))
        assertFalse(paths.contains("/app/notes.md"))
    }
}
