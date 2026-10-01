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
    fun `gives each copy of a duplicated session the full distance`() {
        val run = at(46)..at(57, 38)

        val withDuplicateRecords = splitDistanceBySession(
            sessions = listOf(run, run),
            records = listOf(DistanceSample(run, 325.0), DistanceSample(run, 325.0)),
        )
        val withOneRecord = splitDistanceBySession(
            sessions = listOf(run, run),
            records = listOf(DistanceSample(run, 325.0)),
        )

        assertEquals(listOf(325.0, 325.0), withDuplicateRecords)
        assertEquals(listOf(325.0, 325.0), withOneRecord)
    }

    @Test
    fun `gives a record tied between sessions to the shorter one`() {
        val hike = at(0)..at(59)
        val walk = at(10)..at(20)

        val meters = splitDistanceBySession(
            sessions = listOf(hike, walk),
            records = listOf(DistanceSample(hike, 5000.0), DistanceSample(walk, 1000.0)),
        )

        assertEquals(5000.0, meters[0], 0.01)
        assertEquals(1000.0, meters[1], 0.01)
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
