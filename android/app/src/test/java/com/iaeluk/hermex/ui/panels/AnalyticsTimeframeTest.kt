package com.iaeluk.hermex.ui.panels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.iaeluk.hermex.core.model.SessionSummary
import java.time.Instant
import java.time.ZoneId

class AnalyticsTimeframeTest {
    @Test
    fun mapsReferenceTimeframesToServerDays() {
        assertEquals(1, AnalyticsTimeframe.Today.serverDays)
        assertEquals(7, AnalyticsTimeframe.Last7Days.serverDays)
        assertEquals(30, AnalyticsTimeframe.Last30Days.serverDays)
        assertEquals(90, AnalyticsTimeframe.Past90Days.serverDays)
    }

    @Test
    fun exposesReferenceTimeframeTitles() {
        assertEquals("Today", AnalyticsTimeframe.Today.title)
        assertEquals("Last 7 Days", AnalyticsTimeframe.Last7Days.title)
        assertEquals("Last 30 Days", AnalyticsTimeframe.Last30Days.title)
        assertEquals("Past 90 Days", AnalyticsTimeframe.Past90Days.title)
    }

    @Test
    fun todayUsesJava8CompatibleZoneConversion() {
        val now = Instant.parse("2026-07-13T12:00:00Z")
        val session = SessionSummary(updatedAt = now.minusSeconds(60).epochSecond.toDouble())

        assertTrue(
            AnalyticsTimeframe.Today.contains(
                session = session,
                nowMillis = now.toEpochMilli(),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }
}
