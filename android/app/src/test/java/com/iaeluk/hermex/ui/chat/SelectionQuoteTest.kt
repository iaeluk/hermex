package com.iaeluk.hermex.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionQuoteTest {
    @Test
    fun quotesEveryLine() {
        assertEquals(
            "> line one\n> line two",
            quoteSelectionForDraft("line one\nline two", ""),
        )
    }

    @Test
    fun keepsTheExistingDraftAfterABlankLine() {
        assertEquals(
            "> chosen\n\nmy question",
            quoteSelectionForDraft("chosen", "my question"),
        )
    }

    @Test
    fun blankSelectionKeepsTheDraft() {
        assertEquals("kept", quoteSelectionForDraft("   ", "kept"))
    }
}
