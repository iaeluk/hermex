package com.iaeluk.hermex.core.network.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesCompatibilityTest {
    @Test
    fun supportedVersionsAtOrAboveTheMinimum() {
        assertTrue(HermesCompatibility.isSupported("0.21.3"))
        assertTrue(HermesCompatibility.isSupported("0.21.5"))
        assertTrue(HermesCompatibility.isSupported("0.21.6"))
        assertTrue(HermesCompatibility.isSupported("0.22.0"))
        assertTrue(HermesCompatibility.isSupported("1.0.0"))
    }

    @Test
    fun refusesVersionsOlderThanTheMinimum() {
        assertFalse(HermesCompatibility.isSupported("0.21.2"))
        assertFalse(HermesCompatibility.isSupported("0.21.0"))
        assertFalse(HermesCompatibility.isSupported("0.20.9"))
        assertFalse(HermesCompatibility.isSupported("0.9.0"))
    }

    @Test
    fun unreadableOrMissingVersionsCountAsSupported() {
        assertTrue(HermesCompatibility.isSupported(null))
        assertTrue(HermesCompatibility.isSupported(""))
        assertTrue(HermesCompatibility.isSupported("0.21"))
        assertTrue(HermesCompatibility.isSupported("v0.21.5"))
        assertTrue(HermesCompatibility.isSupported("nightly"))
    }

    @Test
    fun canarySuffixesReadAsTheirBaseRelease() {
        assertTrue(HermesCompatibility.isSupported("0.21.5+4853.g8d30c4e.dirty"))
        assertTrue(HermesCompatibility.isSupported("0.21.3+canary"))
        assertFalse(HermesCompatibility.isSupported("0.21.2+canary"))
    }

    @Test
    fun releaseParsesTheLeadingTriple() {
        assertEquals(listOf(0, 21, 5), HermesCompatibility.release("0.21.5"))
        assertEquals(listOf(0, 21, 5), HermesCompatibility.release("0.21.5+4853.g8d30c4e.dirty"))
        assertEquals(listOf(1, 2, 3), HermesCompatibility.release("1.2.3.4"))
        assertEquals(null, HermesCompatibility.release("0.21"))
        assertEquals(null, HermesCompatibility.release(""))
        assertEquals(null, HermesCompatibility.release(null))
    }
}
