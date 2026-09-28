package io.github.floatingclock.time

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TimeEngineTest {
    private val clock = FakeClock()
    private val engine = TimeEngine(clock)
    private val anchor = TimeAnchor(serverUtcEpochNanos = 1_000_000_000, localMonotonicNanos = 0)

    @Test fun elapsedTimePreservesNanoseconds() {
        clock.advanceNanos(1_234_567)
        assertEquals(1_001_234_567L, engine.rawUtcEpochNanos(anchor))
        clock.advanceNanos(1)
        assertEquals(1_001_234_568L, engine.rawUtcEpochNanos(anchor))
    }

    @Test fun signedOffsetsAreAddedOnlyForDisplay() {
        assertEquals(1_025_000_000L, engine.shownUtcEpochNanos(anchor, 40, -15))
        assertEquals(975_000_000L, engine.shownUtcEpochNanos(anchor, -40, 15))
        assertEquals(1_000_000_000L, engine.rawUtcEpochNanos(anchor))
    }

    @Test fun millisecondsHaveThreeDigitsAndTruncate() {
        val formatter = MillisecondTimeFormatter(ZoneId.of("UTC"))
        assertEquals("00:00:00.007", formatter.format(7_999_999))
        assertEquals("00:00:00.999", formatter.format(999_999_999))
        assertEquals("00:00:01.000", formatter.format(1_000_000_000))
        assertEquals("23:59:59.999", formatter.format(-1))
    }

    @Test fun timezoneAffectsOnlyPresentation() {
        assertEquals("08:00:00.000", MillisecondTimeFormatter().format(0))
        assertEquals("00:00:00.000", MillisecondTimeFormatter(ZoneId.of("UTC")).format(0))
    }

    @Test fun virtualClockAdvancesWithoutSleeping() {
        clock.advanceNanos(5_000_000_000)
        assertEquals(5_000_000_000L, clock.elapsedRealtimeNanos())
        assertEquals(6_000_000_000L, engine.rawUtcEpochNanos(anchor))
    }

    @Test fun wallClockJumpsDoNotChangeInterpolation() {
        clock.wallUtcEpochMillis += 86_400_000
        clock.advanceNanos(10_000_000)
        assertEquals(1_010_000_000L, engine.rawUtcEpochNanos(anchor))
        clock.wallUtcEpochMillis -= 172_800_000
        clock.advanceNanos(10_000_000)
        assertEquals(1_020_000_000L, engine.rawUtcEpochNanos(anchor))
    }

    @Test fun futureAnchorIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            engine.rawUtcEpochNanos(anchor.copy(localMonotonicNanos = 1))
        }
    }

    @Test fun oversizedOffsetsFailInsteadOfWrapping() {
        assertThrows(ArithmeticException::class.java) {
            engine.shownUtcEpochNanos(anchor, Long.MAX_VALUE)
        }
    }

    @Test fun invalidSampleMetadataIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationResult.Success("test", TimeSourceType.NTP, anchor, resolutionNanos = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationResult.Success("test", TimeSourceType.NTP, anchor, 1, -1)
        }
    }
}
