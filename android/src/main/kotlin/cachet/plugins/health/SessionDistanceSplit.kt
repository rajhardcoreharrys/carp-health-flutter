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
    // An app writing the same session or record twice counts it once.
    val spans = sessions.distinct()
    val meters = DoubleArray(spans.size)

    for (record in records.distinct()) {
        val overlaps = spans.map { overlapOf(it, record.time) }
        val most = overlaps.maxOrNull() ?: continue
        if (most <= Duration.ZERO) continue

        // A tie goes to the shortest session, the one the record covers most
        // of (e.g. a walk inside a hike), and is split if that still ties.
        val tied = spans.indices.filter { overlaps[it] == most }
        val shortest = tied.minOf { lengthOf(spans[it]) }
        val owners = tied.filter { lengthOf(spans[it]) == shortest }

        val share = record.meters * most.toMillis().toDouble() / lengthOf(record.time).toMillis()
        owners.forEach { meters[it] += share / owners.size }
    }

    return sessions.map { meters[spans.indexOf(it)] }
}

private fun lengthOf(span: ClosedRange<Instant>): Duration =
    Duration.between(span.start, span.endInclusive)

private fun overlapOf(a: ClosedRange<Instant>, b: ClosedRange<Instant>): Duration {
    val start = maxOf(a.start, b.start)
    val end = minOf(a.endInclusive, b.endInclusive)
    return if (end.isAfter(start)) Duration.between(start, end) else Duration.ZERO
}
