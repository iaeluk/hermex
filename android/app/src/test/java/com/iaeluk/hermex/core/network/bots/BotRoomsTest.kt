package com.iaeluk.hermex.core.network.bots

import com.iaeluk.hermex.core.network.hermes.HermesCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotRoomsTest {
    @Test
    fun roomsListParses() {
        val reply = parseBotJson("""{"rooms":[{"room_id":"r1","name":"Ops","members":[{"profile":"ada"},{"profile":"bob"}]}]}""")
        val rooms = BotRoom.listIn(reply)
        assertEquals("Ops", rooms.first().name)
        assertEquals(listOf("ada", "bob"), rooms.first().members)
    }

    @Test
    fun memberValidationBounds() {
        assertTrue(BotRoom.validateMembers(listOf("a")) != null)
        assertTrue(BotRoom.validateMembers(listOf("a", "b")) == null)
        assertTrue(BotRoom.validateMembers(listOf("a", "b", "c", "d", "e", "f", "g")) != null)
    }

    @Test
    fun groupsMethods() {
        assertEquals("groups.list", HermesCall.GroupsList(0).method)
        assertEquals("groups.send", HermesCall.GroupsSend("r", "e1", "hi").method)
        assertEquals("groups.disband", HermesCall.GroupsDisband("r").method)
        assertEquals("groups.create", HermesCall.GroupsCreate("r", "Ops", listOf("a", "b")).method)
    }
}
