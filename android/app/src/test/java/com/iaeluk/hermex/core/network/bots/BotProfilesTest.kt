package com.iaeluk.hermex.core.network.bots

import com.iaeluk.hermex.core.network.hermes.HermesCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotProfilesTest {
    @Test
    fun faceCatalogs() {
        assertEquals(8, BotFace.shapes.size)
        assertEquals(11, BotFace.colors.size)
        assertEquals(16, BotFace.expressions.size)
        assertEquals("neutral", BotFace.resolveExpression("nope"))
        assertTrue(BotFace.isValidShape("cloud"))
        assertTrue(!BotFace.isValidShape("square"))
    }

    @Test
    fun nameValidation() {
        assertNull(BotProfileEdit.validateName(" Ada "))
        assertTrue(BotProfileEdit.validateName("   ") != null)
    }

    @Test
    fun profilesMethodsAndAdmission() {
        assertEquals("profiles.describe", HermesCall.ProfilesDescribe("ada").method)
        assertEquals("profiles.create", HermesCall.ProfilesCreate("ada").method)
        assertEquals("profiles.configure", HermesCall.ProfilesConfigure("ada", soul = "kind").method)
        val create = HermesCall.ProfilesCreate(" ada ", description = "  ", cloneFrom = "bob").params()
        assertEquals("ada", create["name"]?.toString()?.trim('"'))
        assertEquals("bob", create["clone_from"]?.toString()?.trim('"'))
    }
}
