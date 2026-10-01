package cachet.plugins.health

import java.time.Duration
import java.time.Instant

/** A session's record id, the package of the app that wrote it, and its time span. */
internal data class SessionSpan(val id: String, val origin: String, val time: ClosedRange<Instant>)

/** A distance record's time span and the meters it covers. */
internal data class DistanceSample(val time: ClosedRange<Instant>, val meters: Double)

/**
 * Groups of two or more [sessions] from the same app that overlap, directly
 * or through another session in the group.
 */
internal fun overlappingSameAppGroups(sessions: List<SessionSpan>): List<List<SessionSpan>> =
    sessions.groupBy { it.origin }.values.flatMap { appSessions ->
        val groups = mutableListOf<MutableList<SessionSpan>>()
        var groupEnd = Instant.MIN
        for (session in appSessions.sortedBy { it.time.start }) {
            if (groups.isNotEmpty() && session.time.start < groupEnd) {
                groups.last() += session
                groupEnd = maxOf(groupEnd, session.time.endInclusive)
            } else {
                groups += mutableListOf(session)
                groupEnd = session.time.endInclusive
            }
        }
        groups.filter { it.size > 1 }
    }

/**
 * Each of a [group]'s sessions' meters from its app's distance [records],
 * keyed by session id.
 *
 * Empty when none of the records overlap the group, so the sessions fall back
 * to Health Connect's aggregate (and its other-app fallback). Otherwise every
 * session gets a value, including 0 m for one the app recorded no distance
 * for (e.g. a strength session overlapping a run), so it doesn't take its
 * sibling's distance from the aggregate.
 */
internal fun groupDistances(
    group: List<SessionSpan>,
    records: List<DistanceSample>,
): Map<String, Double> {
    val meters = splitDistanceBySession(group.map { it.time }, records)
    if (meters.none { it > 0 }) return emptyMap()
    return group.indices.associate { group[it].id to meters[it] }
}

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
    val owned = List(spans.size) { mutableListOf<OwnedRecord>() }

    for (record in records.distinct()) {
        val overlaps = spans.map { overlapOf(it, record.time) }
        val most = overlaps.maxOrNull() ?: continue
        if (most <= Duration.ZERO) continue

        // A tie goes to the shortest session, the one the record covers most
        // of (e.g. a walk inside a hike), and is split if that still ties.
        val tied = spans.indices.filter { overlaps[it] == most }
        val shortest = tied.minOf { lengthOf(spans[it]) }
        val owners = tied.filter { lengthOf(spans[it]) == shortest }
        owners.forEach { owned[it] += OwnedRecord(record, share = 1.0 / owners.size) }
    }

    val meters = spans.mapIndexed { index, span -> metersWithin(span, owned[index]) }
    return sessions.map { meters[spans.indexOf(it)] }
}

private class OwnedRecord(val record: DistanceSample, share: Double) {
    /** Meters per nanosecond. */
    val rate = record.meters * share / lengthOf(record.time).toNanos()
}

/**
 * Meters [records] cover within [span], counting time where they overlap
 * once, as Health Connect's aggregate does, so an app's all-day pedometer
 * distance isn't added to a workout's own. Where records overlap, the one
 * overlapping [span] most counts (the workout's own record over pedometer
 * chunks), then the faster.
 */
private fun metersWithin(span: ClosedRange<Instant>, records: List<OwnedRecord>): Double {
    val bounds = records
        .flatMap { listOf(it.record.time.start, it.record.time.endInclusive) }
        .map { it.coerceIn(span.start, span.endInclusive) }
        .distinct()
        .sorted()

    var meters = 0.0
    for ((from, to) in bounds.zipWithNext()) {
        val counted = records
            .filter { it.record.time.start <= from && to <= it.record.time.endInclusive }
            .maxWithOrNull(compareBy({ overlapOf(span, it.record.time) }, { it.rate }))
            ?: continue
        meters += counted.rate * Duration.between(from, to).toNanos()
    }
    return meters
}

private fun lengthOf(span: ClosedRange<Instant>): Duration =
    Duration.between(span.start, span.endInclusive)

private fun overlapOf(a: ClosedRange<Instant>, b: ClosedRange<Instant>): Duration {
    val start = maxOf(a.start, b.start)
    val end = minOf(a.endInclusive, b.endInclusive)
    return if (end.isAfter(start)) Duration.between(start, end) else Duration.ZERO
}
