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
}
