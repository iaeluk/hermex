package com.iaeluk.hermex.ui.chat

internal object TranscriptTablePolicy {
    fun hasTable(text: String): Boolean =
        text.lines().count { it.trimStart().startsWith("|") } >= 2
}
