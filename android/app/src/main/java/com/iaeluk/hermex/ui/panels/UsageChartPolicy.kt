package com.iaeluk.hermex.ui.panels

import com.iaeluk.hermex.core.model.InsightsActivityByHour
import com.iaeluk.hermex.core.model.InsightsDailyToken
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class UsageBucket(
    val label: String,
    val input: Long,
    val output: Long,
    val cost: Double,
)

data class UsageHourBucket(
    val label: String,
    val sessions: Int,
)

object UsageChartPolicy {
    private val dayLabel = DateTimeFormatter.ofPattern("MM-dd")

    fun windowDays(window: AnalyticsTimeframe): Int =
        when (window) {
            AnalyticsTimeframe.Today -> 1
            AnalyticsTimeframe.Last7Days -> 7
            AnalyticsTimeframe.Last30Days -> 30
            AnalyticsTimeframe.Past90Days -> 90
        }

    fun buckets(
        daily: List<InsightsDailyToken>,
        window: AnalyticsTimeframe,
        today: LocalDate = LocalDate.now(),
    ): List<UsageBucket> {
        val days = windowDays(window)
        val byDate = daily.mapNotNull { day ->
            val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: return@mapNotNull null
            date to day
        }.toMap()
        val latest = (byDate.keys + today).maxOrNull() ?: today
        val first = latest.minusDays((days - 1).toLong())
        return (0 until days).map { offset ->
            val date = first.plusDays(offset.toLong())
            val day = byDate[date]
            UsageBucket(
                label = date.format(dayLabel),
                input = (day?.inputTokens ?: 0).toLong(),
                output = (day?.outputTokens ?: 0).toLong(),
                cost = day?.cost ?: 0.0,
            )
        }
    }

    fun hourlyBuckets(hourly: List<InsightsActivityByHour>): List<UsageHourBucket> {
        val byHour = hourly.mapNotNull { entry ->
            val hour = entry.hour?.takeIf { it in 0..23 } ?: return@mapNotNull null
            hour to (entry.sessions ?: 0)
        }.toMap()
        return (0..23).map { hour ->
            UsageHourBucket(
                label = String.format("%02d", hour),
                sessions = byHour[hour] ?: 0,
            )
        }
    }
}
