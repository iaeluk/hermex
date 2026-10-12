package com.iaeluk.hermex.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptFoldPolicyTest {

    @Test
    fun shortMessageDoesNotFold() {
        assertFalse(TranscriptFoldPolicy.shouldFold("hello world"))
    }

    @Test
    fun longUserMessageFolds() {
        assertTrue(TranscriptFoldPolicy.shouldFold("a\n".repeat(200)))
    }

    @Test
    fun longSingleLineMessageFolds() {
        assertTrue(TranscriptFoldPolicy.shouldFold("x".repeat(2000)))
    }

    @Test
    fun foldedPreviewKeepsFirstThreeLines() {
        assertEquals(3, TranscriptFoldPolicy.foldedPreview("l1\nl2\nl3\nl4").lines().size)
    }

    @Test
    fun foldedPreviewOfShortTextIsWholeText() {
        assertEquals("hi", TranscriptFoldPolicy.foldedPreview("hi"))
    }
}
