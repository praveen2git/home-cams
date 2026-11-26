package com.home.cams.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class PlaybackTimeTest {
    private val now = LocalDateTime.of(2026, 11, 22, 10, 0, 0)

    @Test
    fun pickedTime_countsDaysBackAndMinutesFromMidnight() {
        val today = LocalDate.of(2026, 11, 22)
        assertEquals(LocalDateTime.of(2026, 11, 22, 9, 30), pickedTime(today, 0, 570))
        assertEquals(LocalDateTime.of(2026, 11, 9, 23, 50), pickedTime(today, PLAYBACK_DAYS - 1, 1430))
    }

    @Test
    fun jumpTime_countsFromWhatIsOnScreenAndStopsBeforeNow() {
        val start = LocalDateTime.of(2026, 11, 22, 9, 0, 0)
        // 90 seconds in, back one minute.
        assertEquals(LocalDateTime.of(2026, 11, 22, 9, 0, 30), jumpTime(start, 90_000, -1, now))
        assertEquals(LocalDateTime.of(2026, 11, 22, 9, 11, 30), jumpTime(start, 90_000, 10, now))
        // Forward past the present lands one minute ago.
        assertEquals(LocalDateTime.of(2026, 11, 22, 9, 59, 0), jumpTime(start, 55 * 60_000L, 10, now))
    }

    @Test
    fun defaultPlaybackStart_startsThirtyMinutesAgoAcrossMidnight() {
        assertEquals(LocalDateTime.of(2026, 11, 22, 9, 30, 0), defaultPlaybackStart(LocalDateTime.of(2026, 11, 22, 10, 0, 45)))
        assertEquals(LocalDateTime.of(2026, 11, 21, 23, 45, 0), defaultPlaybackStart(LocalDateTime.of(2026, 11, 22, 0, 15, 12)))
    }

    @Test
    fun clampPlaybackTime_limitsTodayBeforeNowAndAllowsFullPastDays() {
        val today = now.toLocalDate()
        assertEquals(599, maxMinuteForDay(today, 0, now))
        assertEquals(1439, maxMinuteForDay(today, 1, now))
        assertEquals(0, maxMinuteForDay(today, 0, LocalDateTime.of(2026, 11, 22, 0, 0, 10)))

        // Future minute on Today clamps to 09:59; on Yesterday full 23:59 is allowed.
        assertEquals(LocalDateTime.of(2026, 11, 22, 9, 59), clampPlaybackTime(today, 0, 1200, now))
        assertEquals(LocalDateTime.of(2026, 11, 21, 23, 59), clampPlaybackTime(today, 1, 1439, now))
        // Out-of-range daysBack clamps into [0, PLAYBACK_DAYS - 1].
        assertEquals(LocalDateTime.of(2026, 11, 9, 12, 0), clampPlaybackTime(today, 30, 720, now))
    }

    @Test
    fun scrubStepMinutes_acceleratesWhenHeld() {
        assertEquals(1, scrubStepMinutes(0))
        assertEquals(1, scrubStepMinutes(4))
        assertEquals(5, scrubStepMinutes(5))
        assertEquals(15, scrubStepMinutes(15))
    }

    @Test
    fun livePlaybackTime_advancesAndClampsToNow() {
        val start = LocalDateTime.of(2026, 11, 22, 9, 55, 0)
        assertEquals(LocalDateTime.of(2026, 11, 22, 9, 56, 30), livePlaybackTime(start, 90_000L, now))
        assertEquals(now, livePlaybackTime(start, 10 * 60_000L, now))
    }
}
