package com.iaeluk.hermex.ui.panels

import com.iaeluk.hermex.core.model.InsightsActivityByHour
import com.iaeluk.hermex.core.model.InsightsDailyToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class UsageChartPolicyTest {

    @Test
    fun ninetyDayBucketsStackInputOutput() {
        val buckets = UsageChartPolicy.buckets(
            listOf(InsightsDailyToken("2026-10-01", 100, 50, 10, 0.4)),
            AnalyticsTimeframe.Past90Days,
            today = LocalDate.parse("2026-10-01"),
        )
        assertEquals(90, buckets.size)
        assertEquals(100, buckets.last().input)
        assertEquals(50, buckets.last().output)
    }

    @Test
    fun emptyWindowYieldsEmptyBuckets() {
        assertTrue(
            UsageChartPolicy.buckets(
                emptyList(),
                AnalyticsTimeframe.Today,
                today = LocalDate.parse("2026-10-01"),
            ).all { it.input == 0L },
        )
    }

    @Test
    fun bucketsFillMissingDaysWithZeros() {
        val buckets = UsageChartPolicy.buckets(
            listOf(InsightsDailyToken("2026-10-07", 20, 5, 1, 0.1)),
            AnalyticsTimeframe.Last7Days,
            today = LocalDate.parse("2026-10-07"),
        )
        assertEquals(7, buckets.size)
        assertEquals(6, buckets.count { it.input == 0L })
        assertEquals("10-07", buckets.last().label)
    }

    @Test
    fun hourlyBucketsCover24Hours() {
        val buckets = UsageChartPolicy.hourlyBuckets(
            listOf(InsightsActivityByHour(9, 4)),
        )
        assertEquals(24, buckets.size)
        assertEquals(4, buckets[9].sessions)
        assertEquals(0, buckets[10].sessions)
    }
}
