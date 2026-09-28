package io.github.floatingclock.time

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class TimeEngine(private val clock: ClockProvider) {
    fun rawUtcEpochNanos(anchor: TimeAnchor): Long {
        val elapsedNanos = Math.subtractExact(clock.elapsedRealtimeNanos(), anchor.localMonotonicNanos)
        require(elapsedNanos >= 0) { "Anchor belongs to a future instant or an invalid session" }
        return Math.addExact(anchor.serverUtcEpochNanos, elapsedNanos)
    }

    fun shownUtcEpochNanos(
        anchor: TimeAnchor,
        globalManualOffsetMillis: Long = 0,
        platformManualOffsetMillis: Long = 0,
    ): Long {
        val offsetMillis = Math.addExact(globalManualOffsetMillis, platformManualOffsetMillis)
        return Math.addExact(rawUtcEpochNanos(anchor), Math.multiplyExact(offsetMillis, 1_000_000L))
    }
}

/** Display-only timezone; fractional milliseconds are truncated, never rounded upward. */
class MillisecondTimeFormatter(zoneId: ZoneId = ZoneId.of("Asia/Shanghai")) {
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT).withZone(zoneId)

    fun format(utcEpochNanos: Long): String =
        formatter.format(Instant.ofEpochSecond(0, utcEpochNanos))
}
