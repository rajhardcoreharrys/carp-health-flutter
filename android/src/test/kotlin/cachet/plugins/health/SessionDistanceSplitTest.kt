package cachet.plugins.health

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val STRAVA = "com.strava"
private const val GARMIN = "com.garmin.android.apps.connectmobile"

class SessionDistanceSplitTest {
    private fun at(minute: Int, second: Int = 0): Instant =
        LocalDateTime.of(2026, 10, 1, 13, 0)
            .plusMinutes(minute.toLong())
            .plusSeconds(second.toLong())
            .toInstant(ZoneOffset.UTC)

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

        for (meters in withDuplicateRecords + withOneRecord) {
            assertEquals(325.0, meters, 0.01)
        }
    }

    @Test
    fun `gives a record tied between sessions to the shorter one`() {
        val hike = at(0)..at(60)
        val walk = at(10)..at(20)

        val meters = splitDistanceBySession(
            sessions = listOf(hike, walk),
            records = listOf(DistanceSample(hike, 5000.0), DistanceSample(walk, 1000.0)),
        )

        assertEquals(5000.0, meters[0], 0.01)
        assertEquals(1000.0, meters[1], 0.01)
    }

    // Garmin Connect and Samsung Health write a workout's distance and
    // all-day pedometer distance under the same app.
    @Test
    fun `does not add pedometer distance to a workout's own`() {
        val run = at(0)..at(30)
        val copy = at(20)..at(50)

        val meters = splitDistanceBySession(
            sessions = listOf(run, copy),
            records = listOf(
                DistanceSample(run, 5000.0),
                DistanceSample(at(0)..at(15), 2600.0),
                DistanceSample(at(15)..at(30), 2600.0),
                DistanceSample(copy, 3000.0),
            ),
        )

        assertEquals(5000.0, meters[0], 0.01)
        assertEquals(3000.0, meters[1], 0.01)
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

    @Test
    fun `groups only overlapping sessions from the same app`() {
        val run = SessionSpan("run", STRAVA, at(0)..at(30))
        val copy = SessionSpan("copy", STRAVA, at(20)..at(50))
        val chained = SessionSpan("chained", STRAVA, at(45)..at(60))
        val touching = SessionSpan("touching", STRAVA, at(60)..at(70))
        val otherApp = SessionSpan("garmin", GARMIN, at(0)..at(30))

        val groups = overlappingSameAppGroups(listOf(touching, chained, otherApp, copy, run))

        assertEquals(listOf(listOf(run, copy, chained)), groups)
    }

    @Test
    fun `keeps a session at zero rather than taking its sibling's distance`() {
        val strength = SessionSpan("strength", STRAVA, at(0)..at(60))
        val run = SessionSpan("run", STRAVA, at(30)..at(75))

        val distances = groupDistances(
            listOf(strength, run),
            listOf(DistanceSample(run.time, 7000.0)),
        )

        assertEquals(setOf("strength", "run"), distances.keys)
        assertEquals(0.0, distances.getValue("strength"), 0.0)
        assertEquals(7000.0, distances.getValue("run"), 0.01)
    }

    @Test
    fun `leaves a group on the aggregate when its app recorded no distance there`() {
        val run = SessionSpan("run", STRAVA, at(0)..at(30))
        val copy = SessionSpan("copy", STRAVA, at(20)..at(50))

        val distances = groupDistances(
            listOf(run, copy),
            listOf(DistanceSample(at(90)..at(100), 900.0)),
        )

        assertTrue(distances.isEmpty())
    }
}
