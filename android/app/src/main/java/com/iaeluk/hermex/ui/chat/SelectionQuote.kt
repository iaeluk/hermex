package com.iaeluk.hermex.ui.chat

/**
 * Builds the composer draft for "ask about this selection": the quoted
 * selection first, then any text already in the composer.
 */
fun quoteSelectionForDraft(selection: String, existingDraft: String): String {
    if (selection.isBlank()) return existingDraft
    val quoted = selection
        .trim()
        .lineSequence()
        .joinToString("\n") { "> ${it.trimEnd()}" }
        .trimEnd()
    if (quoted.isEmpty()) return existingDraft
    if (existingDraft.isBlank()) return quoted
    return quoted + "\n\n" + existingDraft.trim()
}
