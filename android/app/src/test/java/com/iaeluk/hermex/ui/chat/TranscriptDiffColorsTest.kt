package com.iaeluk.hermex.ui.chat

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptDiffColorsTest {

    @Test
    fun diffLineKinds() {
        assertEquals(DiffLineKind.ADDED, TranscriptDiffColors.classify("+new line"))
        assertEquals(DiffLineKind.REMOVED, TranscriptDiffColors.classify("-old line"))
        assertEquals(DiffLineKind.CONTEXT, TranscriptDiffColors.classify(" same"))
    }

    @Test
    fun diffHunkHeaderIsContext() {
        assertEquals(DiffLineKind.CONTEXT, TranscriptDiffColors.classify("@@ -1,3 +1,4 @@"))
    }

    @Test
    fun emptyLineIsContext() {
        assertEquals(DiffLineKind.CONTEXT, TranscriptDiffColors.classify(""))
    }

    @Test
    fun annotatedMarksAddedAndRemovedLines() {
        val added = Color(0xFF00FF00.toInt())
        val removed = Color(0xFFFF0000.toInt())
        val annotated = TranscriptDiffColors.annotated("+added\n same\n-removed", 0xFF00FF00.toInt(), 0xFFFF0000.toInt())
        val styles = annotated.spanStyles
        assertEquals(2, styles.size)
        assertEquals("+added", annotated.text.substring(styles[0].start, styles[0].end))
        assertEquals(added, styles[0].item.color)
        assertEquals("-removed", annotated.text.substring(styles[1].start, styles[1].end))
        assertEquals(removed, styles[1].item.color)
    }

    @Test
    fun annotatedLeavesContextLinesUnstyled() {
        val annotated = TranscriptDiffColors.annotated(" same", 0xFF00FF00.toInt(), 0xFFFF0000.toInt())
        assertTrue(annotated.spanStyles.isEmpty())
    }
}
