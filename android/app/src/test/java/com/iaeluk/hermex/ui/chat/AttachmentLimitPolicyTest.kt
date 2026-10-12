package com.iaeluk.hermex.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AttachmentLimitPolicyTest {

    @Test
    fun remainingSlotsAccountsInFlight() {
        assertEquals(0, AttachmentLimitPolicy.remaining(maximum = 10, attached = 8, uploadsInFlight = 2))
    }

    @Test
    fun occupiedCountsPendingAndInFlightWithoutDoubling() {
        assertEquals(5, AttachmentLimitPolicy.occupied(attached = 2, pending = 3, uploadsInFlight = 3))
        assertEquals(3, AttachmentLimitPolicy.occupied(attached = 2, pending = 0, uploadsInFlight = 1))
        assertEquals(0, AttachmentLimitPolicy.occupied(attached = 0, pending = 0, uploadsInFlight = 0))
    }

    @Test
    fun knownSizeAboveLimitThrowsBeforeCopy() {
        try {
            AttachmentLimitPolicy.checkKnownSize(21L * 1024 * 1024, 20L * 1024 * 1024)
            fail("expected IllegalArgumentException")
        } catch (error: IllegalArgumentException) {
            assertEquals("Files must be 20 MB or smaller.", error.message)
        }
        AttachmentLimitPolicy.checkKnownSize(null, 20L * 1024 * 1024)
        AttachmentLimitPolicy.checkKnownSize(20L * 1024 * 1024, 20L * 1024 * 1024)
    }

    @Test
    fun mimePrefersReportedTypeAndFallsBackToExtension() {
        assertEquals("image/png", resolveAttachmentMime("image/png", "photo.jpg"))
        assertEquals("application/pdf", resolveAttachmentMime(null, "report.pdf"))
        assertEquals("image/jpeg", resolveAttachmentMime("", "photo.jpeg"))
        assertNull(resolveAttachmentMime(null, "noextension"))
    }

    @Test
    fun heicCandidatesDetectedByNameOrMime() {
        assertTrue(isHeicCandidate("photo.heic", null))
        assertTrue(isHeicCandidate("photo.HEIF", "application/octet-stream"))
        assertTrue(isHeicCandidate("photo.jpg", "image/heic"))
        assertTrue(!isHeicCandidate("photo.jpg", "image/jpeg"))
        assertTrue(!isHeicCandidate(null, null))
    }

    @Test
    fun storageCapDropsOldestFirst() {
        val ordered = listOf("a" to 30L * 1024 * 1024, "b" to 25L * 1024 * 1024)
        assertEquals(listOf("a"), PendingUploadStoragePolicy.idsToDrop(ordered, 50L * 1024 * 1024))
        assertEquals(
            emptyList<String>(),
            PendingUploadStoragePolicy.idsToDrop(ordered, 60L * 1024 * 1024),
        )
    }
}
