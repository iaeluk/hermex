package com.iaeluk.hermex.ui.workspace

import com.iaeluk.hermex.core.model.WorkspaceEntry
import com.iaeluk.hermex.core.model.WorkspaceRoot

internal data class WorkspaceTreeRow(
    val entry: WorkspaceEntry,
    val depth: Int,
    val root: WorkspaceRoot?,
)

internal object WorkspaceTreePolicy {
    fun matchesQuery(name: String, path: String?, query: String): Boolean {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        val haystacks = listOf(name, path.orEmpty())
        return tokens.all { token -> haystacks.any { it.contains(token, ignoreCase = true) } }
    }

    fun isVisible(folder: String, descendants: List<String>, query: String): Boolean {
        if (query.isBlank()) return true
        if (matchesQuery(folder.substringAfterLast('/').substringAfterLast('\\'), folder, query)) return true
        return descendants.any { matchesQuery(it.substringAfterLast('/').substringAfterLast('\\'), it, query) }
    }

    fun compareNatural(a: String, b: String): Int {
        val partsA = splitNatural(a)
        val partsB = splitNatural(b)
        for (index in 0 until maxOf(partsA.size, partsB.size)) {
            val left = partsA.getOrNull(index) ?: return -1
            val right = partsB.getOrNull(index) ?: return 1
            val compared = if (left.first && right.first) {
                left.second.toBigInteger().compareTo(right.second.toBigInteger())
            } else {
                left.second.compareTo(right.second, ignoreCase = true)
            }
            if (compared != 0) return compared
        }
        return 0
    }

    fun flattenTree(
        roots: List<WorkspaceRoot>,
        openFolders: Set<String>,
        folderEntries: Map<String, List<WorkspaceEntry>>,
        query: String,
    ): List<WorkspaceTreeRow> {
        val rows = mutableListOf<WorkspaceTreeRow>()
        val trimmedQuery = query.trim()
        roots.forEach { root ->
            val rootPath = root.path ?: root.name ?: return@forEach
            val rootEntry = WorkspaceEntry(name = root.name ?: rootPath, path = rootPath, type = "directory")
            if (trimmedQuery.isNotBlank()) {
                val subtreePaths = collectSubtreePaths(rootPath, openFolders, folderEntries)
                if (!isVisible(rootPath, subtreePaths, trimmedQuery)) return@forEach
                rows += WorkspaceTreeRow(rootEntry, 0, root)
                appendFilteredChildren(rows, root, rootPath, 1, openFolders, folderEntries, trimmedQuery)
            } else {
                rows += WorkspaceTreeRow(rootEntry, 0, root)
                if (openFolders.contains(rootPath)) {
                    appendAllChildren(rows, root, rootPath, 1, openFolders, folderEntries)
                }
            }
        }
        return rows
    }

    private fun collectSubtreePaths(
        folder: String,
        openFolders: Set<String>,
        folderEntries: Map<String, List<WorkspaceEntry>>,
    ): List<String> {
        val paths = mutableListOf<String>()
        val entries = folderEntries[folder] ?: return paths
        entries.forEach { entry ->
            val path = entry.path ?: entry.name ?: return@forEach
            paths += path
            if (isDirectoryEntry(entry) && openFolders.contains(path)) {
                paths += collectSubtreePaths(path, openFolders, folderEntries)
            }
        }
        return paths
    }

    private fun appendAllChildren(
        rows: MutableList<WorkspaceTreeRow>,
        root: WorkspaceRoot,
        folder: String,
        depth: Int,
        openFolders: Set<String>,
        folderEntries: Map<String, List<WorkspaceEntry>>,
    ) {
        val entries = folderEntries[folder]?.let(::sortWorkspaceEntries) ?: return
        entries.forEach { entry ->
            rows += WorkspaceTreeRow(entry, depth, root)
            val path = entry.path ?: entry.name ?: return@forEach
            if (isDirectoryEntry(entry) && openFolders.contains(path)) {
                appendAllChildren(rows, root, path, depth + 1, openFolders, folderEntries)
            }
        }
    }

    private fun appendFilteredChildren(
        rows: MutableList<WorkspaceTreeRow>,
        root: WorkspaceRoot,
        folder: String,
        depth: Int,
        openFolders: Set<String>,
        folderEntries: Map<String, List<WorkspaceEntry>>,
        query: String,
    ) {
        val entries = folderEntries[folder]?.let(::sortWorkspaceEntries) ?: return
        entries.forEach { entry ->
            val name = entry.name ?: entry.path ?: return@forEach
            val path = entry.path ?: entry.name ?: return@forEach
            if (isDirectoryEntry(entry)) {
                val subtree = if (openFolders.contains(path)) {
                    collectSubtreePaths(path, openFolders, folderEntries)
                } else {
                    emptyList()
                }
                if (isVisible(path, subtree, query)) {
                    rows += WorkspaceTreeRow(entry, depth, root)
                    if (openFolders.contains(path)) {
                        appendFilteredChildren(rows, root, path, depth + 1, openFolders, folderEntries, query)
                    }
                }
            } else {
                if (matchesQuery(name, path, query)) {
                    rows += WorkspaceTreeRow(entry, depth, root)
                }
            }
        }
    }

    private fun isDirectoryEntry(entry: WorkspaceEntry): Boolean {
        return entry.type == "directory" || entry.type == "dir" || entry.type == "folder"
    }

    private fun splitNatural(text: String): List<Pair<Boolean, String>> {
        val parts = mutableListOf<Pair<Boolean, String>>()
        val current = StringBuilder()
        var currentIsDigit: Boolean? = null
        text.forEach { character ->
            val isDigit = character.isDigit()
            if (currentIsDigit == null || currentIsDigit == isDigit) {
                current.append(character)
            } else {
                parts += (currentIsDigit == true) to current.toString()
                current.clear()
                current.append(character)
            }
            currentIsDigit = isDigit
        }
        if (current.isNotEmpty()) parts += (currentIsDigit == true) to current.toString()
        return parts
    }
}
