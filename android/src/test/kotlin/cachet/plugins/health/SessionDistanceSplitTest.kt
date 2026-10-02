package cachet.plugins.health

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val STRAVA = "com.strava"
private const val GARMIN = "com.garmin.android.apps.connectmobile"

class SessionDistanceSplitTest {
    private var nextId = 0

    private fun at(minute: Int, second: Int = 0): Instant =
        LocalDateTime.of(2026, 10, 1, 13, 0)
            .plusMinutes(minute.toLong())
            .plusSeconds(second.toLong())
            .toInstant(ZoneOffset.UTC)

    private fun session(id: String, time: ClosedRange<Instant>, origin: String = STRAVA) =
        SessionSpan(id, origin, time)

    private fun record(time: ClosedRange<Instant>, meters: Double, modified: Instant = at(0)) =
        DistanceSample("r${nextId++}", time, meters, modified)

    /** [meters] over [time], written as consecutive records [every] long. */
    private fun chunks(time: ClosedRange<Instant>, meters: Double, every: Duration): List<DistanceSample> {
        val total = Duration.between(time.start, time.endInclusive).toNanos()
        val result = mutableListOf<DistanceSample>()
        var from = time.start
        while (from < time.endInclusive) {
            val to = minOf(from.plus(every), time.endInclusive)
            result += record(from..to, meters * Duration.between(from, to).toNanos() / total)
            from = to
        }
        return result
    }

    private fun Map<String, DistanceSource>.ownMeters(id: String): Double =
        (getValue(id) as DistanceSource.Own).meters

    // Strava's own run overlapping Strava's copy of a Garmin run: Health
    // Connect's aggregate gave the run 150 m instead of 325 m.
    @Test
    fun `gives each overlapping session its whole-workout record`() {
        val copy = session("copy", at(44)..at(56, 48))
        val run = session("run", at(46)..at(57, 38))

        val plan = planGroup(listOf(copy, run), listOf(record(copy.time, 154.0), record(run.time, 325.0)))

        assertEquals(154.0, plan.ownMeters("copy"), 0.01)
        assertEquals(325.0, plan.ownMeters("run"), 0.01)
    }

    @Test
    fun `keeps the aggregate when the same overlap is written in chunks`() {
        val copy = session("copy", at(44)..at(56, 48))
        val run = session("run", at(46)..at(57, 38))
        val minute = Duration.ofMinutes(1)

        val plan = planGroup(
            listOf(copy, run),
            chunks(copy.time, 154.0, minute) + chunks(run.time, 325.0, minute),
        )

        assertEquals(mapOf("copy" to DistanceSource.Aggregate, "run" to DistanceSource.Aggregate), plan)
    }

    @Test
    fun `gives both copies of a re-synced session the full distance`() {
        val first = session("first", at(0)..at(30))
        val second = session("second", at(0, 2)..at(30, 2))

        val plan = planGroup(
            listOf(first, second),
            listOf(
                record(first.time, 5000.0, modified = at(40)),
                record(second.time, 5000.0, modified = at(41)),
            ),
        )

        assertEquals(5000.0, plan.ownMeters("first"), 0.01)
        assertEquals(5000.0, plan.ownMeters("second"), 0.01)
    }

    @Test
    fun `keeps the aggregate for a re-synced session written in chunks`() {
        val first = session("first", at(0)..at(30))
        val second = session("second", at(0, 2)..at(30, 2))

        for (every in listOf(Duration.ofMinutes(1), Duration.ofMinutes(15))) {
            val plan = planGroup(
                listOf(first, second),
                chunks(first.time, 5000.0, every) + chunks(second.time, 5000.0, every),
            )

            assertEquals(
                mapOf("first" to DistanceSource.Aggregate, "second" to DistanceSource.Aggregate),
                plan,
            )
        }
    }

    @Test
    fun `gives a walk inside a hike its own record`() {
        val hike = session("hike", at(0)..at(60))
        val walk = session("walk", at(10)..at(20))

        val plan = planGroup(listOf(hike, walk), listOf(record(hike.time, 5000.0), record(walk.time, 1000.0)))

        assertEquals(5000.0, plan.ownMeters("hike"), 0.01)
        assertEquals(1000.0, plan.ownMeters("walk"), 0.01)
    }

    @Test
    fun `keeps the aggregate for a walk inside a hike written in chunks`() {
        val hike = session("hike", at(0)..at(60))
        val walk = session("walk", at(10)..at(20))

        val plan = planGroup(listOf(hike, walk), chunks(hike.time, 4800.0, Duration.ofMinutes(15)))

        assertEquals(mapOf("hike" to DistanceSource.Aggregate, "walk" to DistanceSource.Aggregate), plan)
    }

    @Test
    fun `keeps the aggregate for a hike around a walk that lines up with a chunk`() {
        val hike = session("hike", at(0)..at(60))
        val walk = session("walk", at(15)..at(29))

        val plan = planGroup(listOf(hike, walk), chunks(hike.time, 4800.0, Duration.ofMinutes(15)))

        assertEquals(DistanceSource.Aggregate, plan.getValue("hike"))
        assertEquals(1120.0, plan.ownMeters("walk"), 0.01)
    }

    // Garmin Connect and Samsung Health write a workout's distance and
    // all-day pedometer distance under the same app.
    @Test
    fun `does not add pedometer distance to a workout's own record`() {
        val run = session("run", at(0)..at(30))
        val copy = session("copy", at(20)..at(50))

        val plan = planGroup(
            listOf(run, copy),
            listOf(record(run.time, 5000.0), record(copy.time, 3000.0)) +
                chunks(at(0)..at(30), 5200.0, Duration.ofMinutes(15)).map { it.copy(lastModified = at(90)) },
        )

        assertEquals(5000.0, plan.ownMeters("run"), 0.01)
        assertEquals(3000.0, plan.ownMeters("copy"), 0.01)
    }

    @Test
    fun `leaves a session whose window holds a sibling's record to other apps`() {
        val strength = session("strength", at(0)..at(60))
        val run = session("run", at(30)..at(75))

        val plan = planGroup(listOf(strength, run), listOf(record(run.time, 7000.0)))

        assertEquals(DistanceSource.OtherApps, plan.getValue("strength"))
        assertEquals(7000.0, plan.ownMeters("run"), 0.01)
    }

    @Test
    fun `keeps the aggregate when the app recorded no distance there`() {
        val run = session("run", at(0)..at(30))
        val copy = session("copy", at(20)..at(50))

        val plan = planGroup(listOf(run, copy), emptyList())

        assertEquals(mapOf("run" to DistanceSource.Aggregate, "copy" to DistanceSource.Aggregate), plan)
    }

    @Test
    fun `counts the most recently modified record where records overlap`() {
        val run = session("run", at(0)..at(30))
        val copy = session("copy", at(20)..at(50))
        val records = listOf(
            record(run.time, 5000.0, modified = at(41)),
            record(run.time, 5200.0, modified = at(40)),
            record(copy.time, 3000.0),
        )

        val plan = planGroup(listOf(run, copy), records)

        assertEquals(5000.0, plan.ownMeters("run"), 0.01)
    }

    @Test
    fun `breaks a modified-time tie by record id whatever the order`() {
        val run = session("run", at(0)..at(30))
        val copy = session("copy", at(20)..at(50))
        val records = listOf(record(run.time, 5000.0), record(run.time, 5200.0), record(copy.time, 3000.0))

        val plan = planGroup(listOf(run, copy), records)

        assertEquals(5200.0, plan.ownMeters("run"), 0.01)
        assertEquals(plan, planGroup(listOf(copy, run), records.reversed()))
    }

    @Test
    fun `matches a record covering at least 90 percent of a session`() {
        val ride = session("ride", at(0)..at(100))
        val other = session("other", at(50)..at(150))

        val over = planGroup(listOf(ride, other), listOf(record(at(0)..at(91), 910.0)))
        val under = planGroup(listOf(ride, other), listOf(record(at(0)..at(89), 890.0)))

        assertEquals(910.0, over.ownMeters("ride"), 0.01)
        assertEquals(DistanceSource.Aggregate, under.getValue("ride"))
    }

    @Test
    fun `counts a matched record only within its session`() {
        val run = session("run", at(0)..at(60))
        val copy = session("copy", at(50)..at(70))

        val plan = planGroup(
            listOf(run, copy),
            listOf(record(at(-3)..at(60), 6300.0), record(copy.time, 2000.0)),
        )

        assertEquals(6000.0, plan.ownMeters("run"), 0.01)
    }

    @Test
    fun `groups only overlapping sessions from the same app`() {
        val run = session("run", at(0)..at(30))
        val copy = session("copy", at(20)..at(50))
        val chained = session("chained", at(45)..at(60))
        val touching = session("touching", at(60)..at(70))
        val otherApp = session("garmin", at(0)..at(30), origin = GARMIN)

        val groups = overlappingSameAppGroups(listOf(touching, chained, otherApp, copy, run))

        assertEquals(listOf(listOf(run, copy, chained)), groups)
        assertTrue(overlappingSameAppGroups(listOf(otherApp, touching)).isEmpty())
    }
}
