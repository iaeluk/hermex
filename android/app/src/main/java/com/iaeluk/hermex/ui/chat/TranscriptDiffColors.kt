package com.iaeluk.hermex.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

internal enum class DiffLineKind {
    ADDED,
    REMOVED,
    CONTEXT,
}

internal object TranscriptDiffColors {
    fun classify(line: String): DiffLineKind = when {
        line.startsWith("+") -> DiffLineKind.ADDED
        line.startsWith("-") -> DiffLineKind.REMOVED
        else -> DiffLineKind.CONTEXT
    }

    fun annotated(content: String, addedArgb: Int, removedArgb: Int): AnnotatedString {
        val builder = AnnotatedString.Builder()
        content.lines().forEachIndexed { index, line ->
            if (index > 0) builder.append("\n")
            val start = builder.length
            builder.append(line)
            val style = when (classify(line)) {
                DiffLineKind.ADDED -> SpanStyle(color = Color(addedArgb))
                DiffLineKind.REMOVED -> SpanStyle(color = Color(removedArgb))
                DiffLineKind.CONTEXT -> null
            }
            if (style != null) builder.addStyle(style, start, builder.length)
        }
        return builder.toAnnotatedString()
    }
}
