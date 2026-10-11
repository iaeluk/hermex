package com.iaeluk.hermex.core.network.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotRosterTest {

    @Test
    fun profilesParseTolerantly() {
        val json = parseBotJson(
            """{"profiles":[{"name":"helper","display_name":"Helper","unread":true},{"name":"coder"}]}"""
        )

        val profiles = BotProfile.listIn(json)

        assertEquals(2, profiles.size)
        assertEquals("Helper", profiles[0].rowTitle())
        assertTrue(profiles[0].unread)
        assertEquals("coder", profiles[1].rowTitle())
    }

    @Test
    fun profileWithoutIdIsSkipped() {
        val json = parseBotJson("""{"profiles":[{},{"name":"ok"}]}""")

        val profiles = BotProfile.listIn(json)

        assertEquals(1, profiles.size)
        assertEquals("ok", profiles.single().id)
    }

    @Test
    fun sessionRowsParseTolerantly() {
        val json = parseBotJson(
            """{"sessions":[{"id":"s1","title":"Bot Chat","hidden":true},{"title":"no id"},{"id":"s2","title":"Hello"}]}"""
        )

        val rows = BotSessionRow.listIn(json)

        assertEquals(2, rows.size)
        assertTrue(rows[0].isBotChat)
        assertEquals("s2", rows[1].id)
    }

    @Test
    fun lineageRootIsTheIdentity() {
        val json = parseBotJson("""{"id":"tip","_lineage_root_id":"root"}""")

        val row = BotSessionRow.from(json)

        assertEquals("root", row?.identity)
    }

    @Test
    fun inboxSortsWaitingWorkingUnreadNewest() {
        val rows = listOf(
            BotInboxRow(BotProfile("c", "c"), BotLiveStatus.IDLE, lastActiveEpochSeconds = 300.0),
            BotInboxRow(BotProfile("a", "a"), BotLiveStatus.WORKING, lastActiveEpochSeconds = 100.0),
            BotInboxRow(BotProfile("b", "b"), BotLiveStatus.WAITING, lastActiveEpochSeconds = 50.0),
            BotInboxRow(BotProfile("d", "d"), BotLiveStatus.IDLE, unread = true, lastActiveEpochSeconds = 10.0),
        )

        val sorted = BotInboxRow.sorted(rows).map { it.profile.id }

        assertEquals(listOf("b", "a", "d", "c"), sorted)
    }

    @Test
    fun missingListsReadAsEmpty() {
        assertTrue(BotProfile.listIn(null).isEmpty())
        assertTrue(BotSessionRow.listIn(parseBotJson("""{}""")).isEmpty())
        assertNull(BotSessionRow.from(parseBotJson("""{"title":"x"}""")))
    }
}
