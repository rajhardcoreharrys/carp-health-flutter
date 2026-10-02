package cachet.plugins.health

import java.time.Duration
import java.time.Instant
import java.util.TreeSet

/** A record counts as a whole session's when overlap over combined span is at least this. */
private const val MATCH_THRESHOLD = 0.9

/** A session's record id, the package of the app that wrote it, and its time span. */
internal data class SessionSpan(val id: String, val origin: String, val time: ClosedRange<Instant>)

/** A distance record's id, time span, meters, and when it was last modified. */
internal data class DistanceSample(
    val id: String,
    val time: ClosedRange<Instant>,
    val meters: Double,
    val lastModified: Instant,
)

/** Where a session in an overlapping same-app group takes its distance from. */
internal sealed interface DistanceSource {
    /** Meters worked out from the app's own records for the session. */
    data class Own(val meters: Double) : DistanceSource

    /** Health Connect's aggregate, the same as for a session with no overlap. */
    data object Aggregate : DistanceSource

    /** Only other apps' distance: the app's records here belong to a sibling. */
    data object OtherApps : DistanceSource
}

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
 * Where each of a [group]'s sessions takes its distance from, given its app's
 * distance [records], keyed by session id.
 *
 * Health Connect doesn't link distance records to sessions, and its aggregate
 * removes overlap between one app's records, so two overlapping sessions
 * (e.g. Strava's own run and its copy of a Garmin run) share one stretch of
 * distance. The only evidence a record belongs to one session is that it
 * covers that session as a whole, as Strava's one record per activity does:
 *
 * - A session with such a record uses it ([DistanceSource.Own]).
 * - A session without one keeps the aggregate, unless a record in its window
 *   belongs to a sibling reaching outside it. It then uses the app's other
 *   records there, or other apps' distance if there are none (e.g. a strength
 *   session overlapping a run).
 *
 * So an app that writes distance in chunks keeps the aggregate throughout.
 * Its overlapping sessions can't be told apart, whether they are a duplicate,
 * a walk inside a hike, or two different activities.
 */
internal fun planGroup(
    group: List<SessionSpan>,
    records: List<DistanceSample>,
): Map<String, DistanceSource> {
    val matched = group.associate { session ->
        session.id to records.filter { matches(it.time, session.time) }
    }

    return group.associate { session ->
        val own = matched.getValue(session.id)
        if (own.isNotEmpty()) return@associate session.id to ownOrOtherApps(metersWithin(session.time, own))

        val elsewhere = group
            .filter { it.id != session.id && !within(it.time, session.time) }
            .flatMap { matched.getValue(it.id) }
            .mapTo(mutableSetOf()) { it.id }
        val inWindow = records.filter { overlapOf(it.time, session.time) > Duration.ZERO }
        if (inWindow.none { it.id in elsewhere }) return@associate session.id to DistanceSource.Aggregate

        session.id to ownOrOtherApps(metersWithin(session.time, inWindow.filter { it.id !in elsewhere }))
    }
}

private fun ownOrOtherApps(meters: Double): DistanceSource =
    if (meters > 0) DistanceSource.Own(meters) else DistanceSource.OtherApps

private fun matches(record: ClosedRange<Instant>, session: ClosedRange<Instant>): Boolean {
    val union = Duration.between(
        minOf(record.start, session.start),
        maxOf(record.endInclusive, session.endInclusive),
    )
    if (union <= Duration.ZERO) return false
    return overlapOf(record, session).toNanos().toDouble() / union.toNanos() >= MATCH_THRESHOLD
}

private val newestFirst =
    compareByDescending<DistanceSample> { it.lastModified }.thenByDescending { it.id }

/**
 * Meters [records] cover within [span], counting time where they overlap once
 * as Health Connect does within one app: the most recently modified record.
 */
private fun metersWithin(span: ClosedRange<Instant>, records: List<DistanceSample>): Double {
    val inSpan = records.filter { overlapOf(it.time, span) > Duration.ZERO }
    val byStart = inSpan.sortedBy { it.time.start }
    val byEnd = inSpan.sortedBy { it.time.endInclusive }
    val bounds = inSpan
        .flatMap { listOf(it.time.start, it.time.endInclusive) }
        .map { it.coerceIn(span.start, span.endInclusive) }
        .distinct()
        .sorted()

    val active = TreeSet(newestFirst)
    var started = 0
    var ended = 0
    var meters = 0.0
    for ((from, to) in bounds.zipWithNext()) {
        while (started < byStart.size && byStart[started].time.start <= from) active += byStart[started++]
        while (ended < byEnd.size && byEnd[ended].time.endInclusive <= from) active -= byEnd[ended++]
        val newest = active.firstOrNull() ?: continue
        meters += newest.meters * Duration.between(from, to).toNanos() / lengthOf(newest.time).toNanos()
    }
    return meters
}

private fun within(inner: ClosedRange<Instant>, outer: ClosedRange<Instant>): Boolean =
    inner.start >= outer.start && inner.endInclusive <= outer.endInclusive

private fun lengthOf(span: ClosedRange<Instant>): Duration =
    Duration.between(span.start, span.endInclusive)

private fun overlapOf(a: ClosedRange<Instant>, b: ClosedRange<Instant>): Duration {
    val start = maxOf(a.start, b.start)
    val end = minOf(a.endInclusive, b.endInclusive)
    return if (end.isAfter(start)) Duration.between(start, end) else Duration.ZERO
}
