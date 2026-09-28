package io.github.floatingclock.time

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.*
import org.junit.Test

class CalibrationEngineTest {
    private val clock = FakeClock()
    private val engine = TimeEngine(clock)
    private val source = FakeTimeSource(clock)
    private val platform = PlatformId.TAOBAO_TMALL
    private val output = TimeReadings()

    private fun calibrate(target: PlatformId = platform, selected: TimeSource = source) =
        runImmediate { engine.calibrate(target, selected) }

    private fun read(target: PlatformId = platform): Long {
        engine.readInto(output)
        return output.shownUtcEpochNanos(target)
    }

    @Test fun uncalibratedEntriesHaveNoInventedTime() {
        engine.readInto(output)
        for (entry in PlatformId.entries) {
            assertFalse(output.hasTime(entry))
            assertEquals(CalibrationStatus.STOPPED, output.state(entry).status)
            assertNull(output.state(entry).lastSuccess)
            assertThrows(IllegalStateException::class.java) { output.shownUtcEpochNanos(entry) }
        }
    }

    @Test fun calibratedClockAdvancesByOneNanosecondMillisecondAndSecond() {
        calibrate()
        assertEquals(1_000_000_000L, read())
        clock.advanceNanos(1)
        assertEquals(1_000_000_001L, read())
        clock.advanceNanos(1_000_000)
        assertEquals(1_001_000_001L, read())
        clock.advanceNanos(1_000_000_000)
        assertEquals(2_001_000_001L, read())
    }

    @Test fun wallClockChangesDoNotAffectCalibratedTime() {
        calibrate()
        clock.wallUtcEpochMillis = Long.MAX_VALUE
        clock.advanceNanos(1_000_000)
        assertEquals(1_001_000_000L, read())
        clock.wallUtcEpochMillis = Long.MIN_VALUE
        assertEquals(1_001_000_000L, read())
    }

    @Test fun signedGlobalAndPlatformOffsetsUpdateWithoutSampling() {
        calibrate()
        engine.setOffsets(20)
        assertEquals(1_020_000_000L, read())
        engine.setOffsets(-20)
        assertEquals(980_000_000L, read())
        engine.setOffsets(-20, mapOf(platform to 30))
        assertEquals(1_010_000_000L, read())
        engine.setOffsets(20, mapOf(platform to -30))
        assertEquals(990_000_000L, read())
        assertEquals(1, source.calls)
        assertEquals(1_000_000_000L, engine.state(platform).lastSuccess!!.anchor.serverUtcEpochNanos)
    }

    @Test fun allFiveEntriesShareOneSampleButHaveIndependentOffsets() {
        runImmediate { engine.calibrate(PlatformId.entries.toSet(), source) }
        engine.setOffsets(10, mapOf(platform to -20, PlatformId.JD to 30))
        val readsBefore = clock.readCount.get()
        engine.readInto(output)
        assertEquals(readsBefore + 1, clock.readCount.get())
        assertEquals(990_000_000L, output.shownUtcEpochNanos(platform))
        assertEquals(1_040_000_000L, output.shownUtcEpochNanos(PlatformId.JD))
        for (entry in listOf(PlatformId.MEITUAN, PlatformId.PDD, PlatformId.DOUYIN)) {
            assertEquals(1_010_000_000L, output.shownUtcEpochNanos(entry))
        }
        repeat(10) { engine.readInto(output) }
        assertEquals(1, source.calls)
    }

    @Test fun taobaoAndTmallHaveOneMergedConfigurationEntry() {
        assertEquals(listOf("TAOBAO_TMALL", "JD", "MEITUAN", "PDD", "DOUYIN"), PlatformId.entries.map { it.name })
        calibrate()
        engine.setOffsets(0, mapOf(PlatformId.TAOBAO_TMALL to 25))
        assertEquals(25L, engine.state(PlatformId.TAOBAO_TMALL).manualOffsetMillis)
        assertEquals(1_025_000_000L, read(PlatformId.TAOBAO_TMALL))
    }

    @Test fun platformsCanHaveIndependentCalibrationAnchorsAndSources() {
        calibrate()
        val other = FakeTimeSource(clock, "fake:http", TimeSourceType.HTTP_ESTIMATE)
        other.serverUtcEpochNanos = 9_000_000_000
        calibrate(PlatformId.JD, other)
        assertEquals(1_000_000_000L, read(platform))
        assertEquals(9_000_000_000L, read(PlatformId.JD))
        assertEquals(TimeSourceType.HTTP_ESTIMATE, engine.state(PlatformId.JD).sourceType)
    }

    @Test fun calibrationInProgressIsObservableWithoutHoldingTheLock() {
        source.response = {
            assertEquals(CalibrationStatus.INITIALIZING, engine.state(platform).status)
            assertTrue(engine.state(platform).isCalibrating)
            val worker = Executors.newSingleThreadExecutor()
            try {
                worker.submit { engine.readInto(TimeReadings()) }.get(5, TimeUnit.SECONDS)
            } finally { worker.shutdownNow() }
            CalibrationResult.Success(source.sourceId, source.type, TimeAnchor(1_000_000_000, 0), 1)
        }
        calibrate()
        assertEquals(CalibrationStatus.SYNCED, engine.state(platform).status)
        assertFalse(engine.state(platform).isCalibrating)
    }

    @Test fun failureRetainsLastSuccessAndSource() {
        calibrate()
        val success = engine.state(platform).lastSuccess
        source.response = { CalibrationResult.Failure(source.sourceId, "simulated timeout") }
        clock.advanceNanos(100)
        calibrate()
        val state = engine.state(platform)
        assertEquals(CalibrationStatus.RETRYING, state.status)
        assertSame(success, state.lastSuccess)
        assertEquals(source.sourceId, state.sourceId)
        assertEquals(1_000_000_000L, state.lastSuccessfulCalibrationUtcEpochNanos)
        assertEquals(1_000_000_100L, read())
    }

    @Test fun firstFailureHasNoAnchor() {
        source.response = { CalibrationResult.Failure(source.sourceId, "offline") }
        calibrate()
        engine.readInto(output)
        assertEquals(CalibrationStatus.STALE, output.state(platform).status)
        assertFalse(output.hasTime(platform))
    }

    @Test fun staleAnchorStaysVisibleWithWarningUntilSameSourceRecovers() {
        calibrate()
        engine.markStale(platform, "No trusted freshness evidence")
        assertEquals(1_000_000_000L, read())
        assertEquals(CalibrationStatus.STALE, output.state(platform).status)
        source.response = { CalibrationResult.Failure(source.sourceId, "offline") }
        calibrate()
        assertEquals(CalibrationStatus.STALE, engine.state(platform).status)
        source.response = { CalibrationResult.Success(source.sourceId, source.type, TimeAnchor(500_000_000, 0), 1) }
        calibrate()
        assertEquals(500_000_000L, read()) // Recalibration may explicitly jump backward; no hidden smoothing.
        assertEquals(CalibrationStatus.SYNCED, engine.state(platform).status)
        assertNull(engine.state(platform).failureReason)
        assertEquals(500_000_000L, engine.state(platform).lastSuccessfulCalibrationUtcEpochNanos)
    }

    @Test fun failureNeverSubstitutesAnotherSourceOrType() {
        calibrate()
        assertThrows(IllegalArgumentException::class.java) { calibrate(platform, FakeTimeSource(clock, "other")) }
        assertThrows(IllegalArgumentException::class.java) {
            calibrate(platform, FakeTimeSource(clock, source.sourceId, TimeSourceType.OFFICIAL_API))
        }
        assertEquals(source.sourceId, engine.state(platform).sourceId)
    }

    @Test fun mismatchedSampleIsRejectedWithoutDestroyingHistory() {
        calibrate()
        val previous = engine.state(platform).lastSuccess
        source.response = { CalibrationResult.Success("foreign", source.type, TimeAnchor(10, 0), 1) }
        calibrate()
        assertSame(previous, engine.state(platform).lastSuccess)
        source.response = { CalibrationResult.Success(source.sourceId, TimeSourceType.OFFICIAL_API, TimeAnchor(10, 0), 1) }
        calibrate()
        assertSame(previous, engine.state(platform).lastSuccess)
        assertEquals(CalibrationStatus.RETRYING, engine.state(platform).status)
    }

    @Test fun unknownUncertaintyAndEstimatedOffsetNeverBecomeMeasuredAccuracy() {
        source.response = {
            CalibrationResult.Success(source.sourceId, source.type, TimeAnchor(1_000_000_000, 0), 1,
                estimatedOffsetNanos = -20_000_000)
        }
        calibrate()
        val state = engine.state(platform)
        val sample = checkNotNull(state.lastSuccess)
        assertEquals(-20_000_000L, sample.estimatedOffsetNanos)
        assertNull(sample.estimatedUncertaintyNanos)
        assertNull(state.measuredErrorNanos)
        assertFalse(state.accuracyVerified)
        assertEquals(1_000_000_000L, read()) // Estimated offset is metadata, not another display correction.
    }

    @Test fun uncertaintyRequiresEvidenceAndStillDoesNotVerifyAccuracy() {
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationResult.Success("test", source.type, TimeAnchor(0, 0), 1, 10)
        }
        source.response = {
            CalibrationResult.Success(source.sourceId, source.type, TimeAnchor(0, 0), 1, 10,
                uncertaintyEvidence = "Synthetic test evidence, not a real measurement")
        }
        calibrate()
        assertEquals(10L, engine.state(platform).lastSuccess!!.estimatedUncertaintyNanos)
        assertFalse(engine.state(platform).accuracyVerified)
    }

    @Test fun interpolationCrossesMinuteHourAndDateBoundaries() {
        val formatter = MillisecondTimeFormatter(ZoneId.of("UTC"))
        for ((start, expected) in listOf(
            "2026-01-01T12:34:59.999999999Z" to "12:35:00.000",
            "2026-01-01T12:59:59.999999999Z" to "13:00:00.000",
            "2026-12-31T23:59:59.999999999Z" to "00:00:00.000",
        )) {
            source.serverUtcEpochNanos = epochNanos(start)
            calibrate()
            clock.advanceNanos(1)
            assertEquals(expected, formatter.format(read()))
            assertEquals(source.serverUtcEpochNanos + 1, read())
        }
    }

    @Test fun negativeOffsetsCrossTheDateAndUnixEpoch() {
        source.serverUtcEpochNanos = 0
        calibrate()
        engine.setOffsets(-1)
        assertEquals(-1_000_000L, read())
        assertEquals(-1L, output.shownUtcEpochMillis(platform))
        assertEquals("23:59:59.999", MillisecondTimeFormatter(ZoneId.of("UTC")).format(read()))
    }

    @Test fun formatterHandlesBothDaylightSavingTransitions() {
        val formatter = MillisecondTimeFormatter(ZoneId.of("America/New_York"))
        assertEquals("01:59:59.999", formatter.format(epochNanos("2026-03-08T06:59:59.999999999Z")))
        assertEquals("03:00:00.000", formatter.format(epochNanos("2026-03-08T07:00:00Z")))
        assertEquals("01:59:59.999", formatter.format(epochNanos("2026-11-01T05:59:59.999999999Z")))
        assertEquals("01:00:00.000", formatter.format(epochNanos("2026-11-01T06:00:00Z")))
        assertThrows(DateTimeException::class.java) { MillisecondTimeFormatter(ZoneId.of("Invalid/Zone")) }
    }

    @Test fun epochMillisecondsFloorNegativeSubmillisecondValues() {
        source.serverUtcEpochNanos = -1
        calibrate()
        read()
        assertEquals(-1L, output.shownUtcEpochMillis(platform))
        assertEquals("23:59:59.999", MillisecondTimeFormatter(ZoneId.of("UTC")).format(-1))
    }

    @Test fun negativeAndFutureMonotonicAnchorsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TimeAnchor(0, -1) }
        source.response = { CalibrationResult.Success(source.sourceId, source.type, TimeAnchor(0, 1), 1) }
        calibrate()
        engine.readInto(output)
        assertFalse(output.hasTime(platform))
        assertEquals(CalibrationStatus.STALE, engine.state(platform).status)
    }

    @Test fun samplesFromBeforeThisRequestAreRejected() {
        calibrate()
        val old = engine.state(platform).lastSuccess!!
        clock.advanceNanos(1)
        source.response = { old }
        calibrate()
        assertSame(old, engine.state(platform).lastSuccess)
        assertEquals(CalibrationStatus.RETRYING, engine.state(platform).status)
    }

    @Test fun clockRollbackInvalidatesAnchorsUntilRecalibration() {
        calibrate()
        clock.advanceNanos(100)
        read()
        clock.monotonicNanos.set(50) // Still newer than the anchor; compare with the last observed clock too.
        engine.readInto(output)
        assertFalse(output.hasTime(platform))
        assertEquals(CalibrationStatus.STALE, output.state(platform).status)
        assertNotNull(output.state(platform).lastSuccess)
        clock.advanceNanos(100)
        engine.readInto(output)
        assertFalse(output.hasTime(platform))
        calibrate()
        assertEquals(1_000_000_000L, read())
    }

    @Test fun negativeClockRejectsReadAndClearsBufferAvailability() {
        calibrate()
        read()
        clock.monotonicNanos.set(-1)
        assertThrows(IllegalArgumentException::class.java) { engine.readInto(output) }
        assertFalse(output.hasTime(platform))
    }

    @Test fun lowLevelArithmeticRejectsClockRollbackEvenAfterTheAnchor() {
        val anchor = TimeAnchor(0, 0)
        clock.advanceNanos(100)
        assertEquals(100L, engine.rawUtcEpochNanos(anchor))
        clock.monotonicNanos.set(50)
        assertThrows(IllegalArgumentException::class.java) { engine.rawUtcEpochNanos(anchor) }
    }

    @Test fun overflowDoesNotPublishPartialOffsetsOrWrappedTime() {
        calibrate()
        engine.setOffsets(10)
        assertThrows(ArithmeticException::class.java) { engine.setOffsets(Long.MAX_VALUE) }
        assertEquals(1_010_000_000L, read())
        assertThrows(ArithmeticException::class.java) { engine.setOffsets(Long.MAX_VALUE, mapOf(platform to 1)) }
        assertEquals(1_010_000_000L, read())
        engine.setOffsets(0)
        source.serverUtcEpochNanos = Long.MAX_VALUE
        calibrate()
        assertEquals(Long.MAX_VALUE, read())
        clock.advanceNanos(1)
        assertThrows(ArithmeticException::class.java) { engine.readInto(output) }
        assertFalse(output.hasTime(platform))
    }

    @Test fun epochLowerBoundAndDisplayOverflowAreChecked() {
        source.serverUtcEpochNanos = Long.MIN_VALUE
        calibrate()
        assertEquals(Long.MIN_VALUE, read())
        engine.setOffsets(-1)
        assertThrows(ArithmeticException::class.java) { read() }
        assertFalse(output.hasTime(platform))
        engine.setOffsets(0)
        assertEquals(Long.MIN_VALUE, read())
    }

    @Test fun sourceExceptionsPreserveHistoryAndCancellationIsPropagated() {
        calibrate()
        val previous = engine.state(platform).lastSuccess
        source.response = { throw IllegalStateException("simulated failure") }
        calibrate()
        assertSame(previous, engine.state(platform).lastSuccess)
        source.response = { throw CancellationException("cancelled") }
        assertThrows(CancellationException::class.java) { calibrate() }
        assertFalse(engine.state(platform).isCalibrating)
        assertSame(previous, engine.state(platform).lastSuccess)
    }

    @Test fun olderInFlightResultCannotOverwriteNewerCalibrationOrOffsets() {
        var pending: Continuation<CalibrationResult>? = null
        var outcome: Result<Unit>? = null
        source.response = { suspendCoroutine { pending = it } }
        val work: suspend () -> Unit = { engine.calibrate(platform, source) }
        work.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { outcome = result }
        })
        assertTrue(engine.state(platform).isCalibrating)
        source.response = { CalibrationResult.Success(source.sourceId, source.type, TimeAnchor(2_000_000_000, 0), 1) }
        calibrate()
        engine.setOffsets(10)
        pending!!.resume(CalibrationResult.Success(source.sourceId, source.type, TimeAnchor(1_000_000_000, 0), 1))
        assertTrue(outcome!!.isSuccess)
        assertEquals(2_010_000_000L, read())
    }

    @Test fun capturedMetadataIsImmutableAndOffsetMapsAreNotRetained() {
        calibrate()
        val offsets = mutableMapOf(platform to 10L)
        engine.setOffsets(0, offsets)
        engine.readInto(output)
        val captured = output.state(platform)
        offsets[platform] = 999
        assertEquals(1_010_000_000L, read())
        engine.markStale(platform, "expired")
        assertEquals(CalibrationStatus.SYNCED, captured.status)
        assertEquals(CalibrationStatus.SYNCED, output.state(platform).status)
        engine.readInto(output)
        assertEquals(CalibrationStatus.STALE, output.state(platform).status)
    }

    @Test fun concurrentOffsetWritesNeverTearBatchReads() {
        runImmediate { engine.calibrate(PlatformId.entries.toSet(), source) }
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val writer = executor.submit {
                start.await()
                repeat(1_000) { n -> engine.setOffsets(n.toLong(), PlatformId.entries.associateWith { -n.toLong() }) }
            }
            val reader = executor.submit {
                val buffer = TimeReadings()
                start.await()
                repeat(1_000) {
                    engine.readInto(buffer)
                    for (entry in PlatformId.entries) assertEquals(1_000_000_000L, buffer.shownUtcEpochNanos(entry))
                }
            }
            start.countDown()
            writer.get(10, TimeUnit.SECONDS)
            reader.get(10, TimeUnit.SECONDS)
        } finally { executor.shutdownNow() }
    }

    private fun epochNanos(value: String): Long {
        val instant = Instant.parse(value)
        return Math.addExact(Math.multiplyExact(instant.epochSecond, 1_000_000_000L), instant.nano.toLong())
    }
}
