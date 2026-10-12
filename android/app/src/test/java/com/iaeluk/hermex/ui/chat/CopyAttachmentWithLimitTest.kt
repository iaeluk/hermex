package com.iaeluk.hermex.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class CopyAttachmentWithLimitTest {

    @Test
    fun rejectsOversizeBeforeCopy() {
        val input = ByteArray(26 * 1024 * 1024).inputStream()
        val dest = File.createTempFile("att", ".bin")
        try {
            copyAttachmentWithLimit(input, dest, 20L * 1024 * 1024)
            fail("expected IllegalArgumentException")
        } catch (error: IllegalArgumentException) {
            assertTrue((error.message ?: "").contains("20 MB"))
        } finally {
            dest.delete()
        }
    }

    @Test
    fun copiesWithinLimitAndDeletesPartialOnFailure() {
        val dest = File.createTempFile("att", ".bin")
        try {
            val bytes = ByteArray(1024) { it.toByte() }
            val copied = copyAttachmentWithLimit(bytes.inputStream(), dest, 20L * 1024 * 1024)
            assertEquals(1024L, copied)
            assertEquals(1024, dest.length().toInt())
        } finally {
            dest.delete()
        }
        val doomed = File.createTempFile("att", ".bin")
        try {
            copyAttachmentWithLimit(ByteArray(10).inputStream(), doomed, 0L)
            fail("expected IllegalArgumentException")
        } catch (error: IllegalArgumentException) {
            assertTrue(!doomed.exists())
        } finally {
            doomed.delete()
        }
    }
}
