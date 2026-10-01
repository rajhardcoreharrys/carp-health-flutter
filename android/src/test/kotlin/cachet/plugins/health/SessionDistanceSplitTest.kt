package cachet.plugins.health

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionDistanceSplitTest {
    private fun at(minute: Int, second: Int = 0): Instant =
        LocalDateTime.of(2026, 10, 1, 13, minute, second).toInstant(ZoneOffset.UTC)

    // Strava's own run overlapping Strava's copy of a Garmin run: Health
    // Connect's aggregate gave the run 150 m instead of 325 m.
    @Test
    fun `gives each overlapping session its own distance`() {
        val meters = splitDistanceBySession(
            sessions = listOf(at(44)..at(56, 48), at(46)..at(57, 38)),
            records = listOf(
                DistanceSample(at(44)..at(56, 48), 154.0),
                DistanceSample(at(46)..at(57, 38), 325.0),
            ),
        )

        assertEquals(154.0, meters[0], 0.01)
        assertEquals(325.0, meters[1], 0.01)
    }

    @Test
    fun `ignores records overlapping no session`() {
        val meters = splitDistanceBySession(
            sessions = listOf(at(46)..at(57, 38)),
            records = listOf(
                DistanceSample(at(46)..at(57, 38), 325.0),
                DistanceSample(at(20)..at(30), 900.0),
            ),
        )

        assertEquals(325.0, meters[0], 0.01)
    }

    @Test
    fun `counts a record crossing the session start pro rata`() {
        val meters = splitDistanceBySession(
            sessions = listOf(at(10, 30)..at(12)),
            records = listOf(
                DistanceSample(at(10)..at(11), 60.0),
                DistanceSample(at(11)..at(12), 60.0),
            ),
        )

        assertEquals(90.0, meters[0], 0.01)
    }

    @Test
    fun `gives zero when no records overlap`() {
        val meters = splitDistanceBySession(
            sessions = listOf(at(46)..at(57, 38)),
            records = emptyList(),
        )

        assertEquals(0.0, meters[0], 0.0)
    }
}
