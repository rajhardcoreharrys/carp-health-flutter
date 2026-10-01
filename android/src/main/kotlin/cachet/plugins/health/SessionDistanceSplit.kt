package cachet.plugins.health

import java.time.Duration
import java.time.Instant

/** A distance record's time span and the meters it covers. */
internal data class DistanceSample(val time: ClosedRange<Instant>, val meters: Double)

/**
 * Splits one app's distance [records] between its overlapping [sessions],
 * returning each session's meters in the same order.
 *
 * Each record counts toward the session it overlaps most, pro rata, so two
 * overlapping sessions each keep their own distance. Records overlapping no
 * session are ignored.
 */
internal fun splitDistanceBySession(
    sessions: List<ClosedRange<Instant>>,
    records: List<DistanceSample>,
): List<Double> {
    val meters = DoubleArray(sessions.size)

    for (record in records) {
        var owner = -1
        var ownerOverlap = Duration.ZERO
        sessions.forEachIndexed { index, session ->
            val overlap = overlapOf(session, record.time)
            if (overlap > ownerOverlap) {
                owner = index
                ownerOverlap = overlap
            }
        }
        if (owner == -1) continue

        val length = Duration.between(record.time.start, record.time.endInclusive)
        meters[owner] += record.meters * ownerOverlap.toMillis().toDouble() / length.toMillis()
    }

    return meters.toList()
}

private fun overlapOf(a: ClosedRange<Instant>, b: ClosedRange<Instant>): Duration {
    val start = maxOf(a.start, b.start)
    val end = minOf(a.endInclusive, b.endInclusive)
    return if (end.isAfter(start)) Duration.between(start, end) else Duration.ZERO
}
