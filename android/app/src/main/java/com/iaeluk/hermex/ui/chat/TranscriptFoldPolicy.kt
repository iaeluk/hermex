package com.iaeluk.hermex.ui.chat

internal object TranscriptFoldPolicy {
    const val MAX_UNFOLDED_LINES = 12
    const val MAX_UNFOLDED_CHARACTERS = 1500
    const val PREVIEW_LINES = 3

    fun shouldFold(text: String): Boolean {
        if (text.length > MAX_UNFOLDED_CHARACTERS) return true
        return text.lines().size > MAX_UNFOLDED_LINES
    }

    fun foldedPreview(text: String): String =
        text.lines().take(PREVIEW_LINES).joinToString("\n")
}
