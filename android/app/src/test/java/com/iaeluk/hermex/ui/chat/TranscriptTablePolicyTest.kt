package com.iaeluk.hermex.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTablePolicyTest {

    @Test
    fun markdownTableDetected() {
        assertTrue(TranscriptTablePolicy.hasTable("| a | b |\n|---|---|\n| 1 | 2 |"))
    }

    @Test
    fun singlePipeLineIsNotATable() {
        assertFalse(TranscriptTablePolicy.hasTable("a | b"))
    }

    @Test
    fun plainTextHasNoTable() {
        assertFalse(TranscriptTablePolicy.hasTable("hello world"))
    }
}
