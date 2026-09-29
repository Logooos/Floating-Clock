@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.floatingclock.time

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class TimeSynchronizationTest {
    @Test fun diagnosticCallbackCapturesInitialFailuresManualAttemptAndRecovery() = runTest {
        val clock = ClockProvider { testScheduler.currentTime * 1_000_000 }
        val rejected = healthy(clock, "rejected").apply { response = { CalibrationResult.Failure(sourceId, "offline") } }
        val source = healthy(clock, "selected")
        val success = source.response
        val events = mutableListOf<Triple<String?, Boolean, Boolean>>()
        val sync = TimeSynchronizer(TimeEngine(clock), clock, listOf(rejected, source), onSample = { state, _, manual, recovered ->
            events += Triple(state.sourceId, manual, recovered)
        })
        sync.start(this); runCurrent()
        assertEquals(listOf("rejected", "selected"), events.map { it.first })
        source.response = { CalibrationResult.Failure(source.sourceId, "offline") }
        sync.syncNow(); runCurrent(); advanceTimeBy(30_000); runCurrent()
        assertEquals(Triple("selected", true, false), events.last())
        source.response = success
        advanceTimeBy(30_000); runCurrent()
        assertEquals(Triple("selected", false, true), events.last())
        sync.stop()
        val count = events.size
        advanceTimeBy(300_000); runCurrent()
        assertEquals(count, events.size)
    }
    private val platform = PlatformId.TAOBAO_TMALL
    private fun healthy(clock: ClockProvider, id: String) = FakeTimeSource(clock, id).apply {
        response = { CalibrationResult.Success(sourceId, type, TimeAnchor(1_000_000_000L + clock.elapsedRealtimeNanos(), clock.elapsedRealtimeNanos()), 1_000_000) }
    }
    @Test fun api31SkipsSystemAndApi33PrefersIt() {
        val clock = FakeClock(); val system = healthy(clock, "system"); val main = healthy(clock, "main"); val backup = healthy(clock, "backup")
        assertEquals(listOf(main, backup), initialSourceOrder(31, system, main, backup))
        assertEquals(listOf(main, backup), initialSourceOrder(32, system, main, backup))
        assertEquals(listOf(system, main, backup), initialSourceOrder(33, system, main, backup))
        assertEquals(listOf(system, main, backup), initialSourceOrder(36, system, main, backup))
    }
    @Test fun initialUnavailableSystemAndUdpCanSelectBackup() = runTest {
        val clock = ClockProvider { testScheduler.currentTime * 1_000_000 }
        val engine = TimeEngine(clock)
        val system = healthy(clock, "system").apply { response = { CalibrationResult.Failure(sourceId, "Unavailable") } }
        val primary = healthy(clock, "primary").apply { response = { CalibrationResult.Failure(sourceId, "UDP timeout") } }
        val backup = healthy(clock, "backup")
        val sync = TimeSynchronizer(engine, clock, initialSourceOrder(33, system, primary, backup))
        sync.start(this); runCurrent()
        assertEquals("backup", sync.selectedSourceId)
        assertEquals(1, system.calls); assertEquals(1, primary.calls); assertEquals(1, backup.calls)
        PlatformId.entries.forEach { assertEquals("backup", engine.state(it).sourceId) }
        sync.stop()
    }
    @Test fun runtimeFailureKeepsAnchorNeverFallsBackAndSameSourceRecovers() = runTest {
        val clock = ClockProvider { testScheduler.currentTime * 1_000_000 }; val engine = TimeEngine(clock)
        val primary = healthy(clock, "primary"); val backup = healthy(clock, "backup")
        val success = primary.response
        val sync = TimeSynchronizer(engine, clock, listOf(primary, backup))
        sync.start(this); runCurrent()
        val old = engine.state(platform).lastSuccess
        primary.response = { CalibrationResult.Failure(primary.sourceId, "offline") }
        advanceTimeBy(30_000); runCurrent()
        assertSame(old, engine.state(platform).lastSuccess); assertEquals(CalibrationStatus.RETRYING, engine.state(platform).status)
        advanceTimeBy(90_000); runCurrent()
        assertEquals(CalibrationStatus.STALE, engine.state(platform).status)
        assertSame(old, engine.state(platform).lastSuccess); assertEquals(0, backup.calls)
        primary.response = success
        advanceTimeBy(300_000); runCurrent()
        assertEquals(CalibrationStatus.SYNCED, engine.state(platform).status)
        assertEquals("primary", sync.selectedSourceId); assertNotSame(old, engine.state(platform).lastSuccess)
        sync.stop()
    }
    @Test fun allInitialFailuresProduceNoTimeAndProbeOnlyLastCandidate() = runTest {
        val clock = ClockProvider { testScheduler.currentTime * 1_000_000 }; val engine = TimeEngine(clock)
        val sources = listOf("primary", "backup").map { healthy(clock, it).apply { response = { CalibrationResult.Failure(sourceId, "offline") } } }
        val sync = TimeSynchronizer(engine, clock, sources); sync.start(this); runCurrent()
        val readings = TimeReadings(); engine.readInto(readings)
        assertFalse(readings.hasTime(platform))
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, sources[0].calls); assertEquals(2, sources[1].calls)
        sync.stop()
    }
    @Test fun manualRequestsAreConflatedRateLimitedAndStartIsIdempotent() = runTest {
        val clock = ClockProvider { testScheduler.currentTime * 1_000_000 }; val source = healthy(clock, "one")
        val sync = TimeSynchronizer(TimeEngine(clock), clock, listOf(source))
        sync.start(this); sync.start(this); runCurrent()
        repeat(20) { assertTrue(sync.syncNow()) }; runCurrent()
        advanceTimeBy(29_999); runCurrent(); assertEquals(1, source.calls)
        advanceTimeBy(1); runCurrent(); assertEquals(2, source.calls)
        repeat(20) { sync.syncNow() }; runCurrent()
        advanceTimeBy(30_000); runCurrent(); assertEquals(3, source.calls)
        sync.stop(); assertFalse(sync.syncNow())
    }
    @Test fun policyBoundsBackoffAndUnstableSamples() {
        val policy = SyncPolicy()
        assertEquals(30_000, policy.nextMillis(true, null, null))
        assertEquals(60_000, policy.nextMillis(true, 0, 1))
        repeat(10) { policy.nextMillis(true, 0, 1) }
        assertEquals(300_000, policy.nextMillis(true, 0, 1))
        assertEquals(30_000, policy.nextMillis(true, 50_000_001, 1))
        assertEquals(30_000, policy.nextMillis(true, 0, 250_000_001))
        assertEquals(30_000, policy.nextMillis(false, null, null))
        assertEquals(60_000, policy.nextMillis(false, null, null))
        assertEquals(300_000, policy.nextMillis(false, null, null))
        repeat(10) { assertEquals(300_000, policy.nextMillis(false, null, null)) }
        assertEquals(30_000, policy.nextMillis(true, null, null)); assertEquals(0, policy.failures)
    }
    @Test fun budgetIsSharedAcrossSessionsAndConcurrentClaims() {
        val clock = FakeClock(); val budget = RequestBudget(clock)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = (1..20).map { executor.submit<Boolean> { budget.claim("same") } }.map { it.get() }
            assertEquals(1, results.count { it })
            assertTrue(budget.claim("other")); assertFalse(budget.claim("same"))
            clock.advanceNanos(30_000_000_000); assertTrue(budget.claim("same"))
            clock.monotonicNanos.set(0); assertFalse(budget.claim("same"))
        } finally { executor.shutdownNow() }
    }
    @Test fun budgetDenialDoesNotPerformIo() = runTest {
        val clock = FakeClock(); val source = healthy(clock, "one"); val guarded = BudgetedTimeSource(source, RequestBudget(clock))
        assertTrue(guarded.calibrate() is CalibrationResult.Success)
        assertTrue(guarded.calibrate() is CalibrationResult.Failure)
        assertEquals(1, source.calls)
    }
    @Test fun stopCancelsInFlightAndDoesNotResumeOnUnlockOrManualRequest() = runTest {
        val clock = ClockProvider { testScheduler.currentTime * 1_000_000 }; val source = healthy(clock, "one")
        var cancelled = false
        source.response = { try { awaitCancellation() } finally { cancelled = true } }
        val sync = TimeSynchronizer(TimeEngine(clock), clock, listOf(source)); sync.start(this); runCurrent()
        sync.stop(); runCurrent(); advanceTimeBy(3_600_000); runCurrent()
        assertTrue(cancelled); assertFalse(sync.syncNow()); assertEquals(1, source.calls)
        assertThrows(IllegalStateException::class.java) { sync.start(this) }
    }
    @Test fun lateUncooperativeResponseCannotReplaceLastAnchorAfterStop() = runTest {
        val clock = ClockProvider { testScheduler.currentTime * 1_000_000 }; val engine = TimeEngine(clock); val source = healthy(clock, "one")
        val sync = TimeSynchronizer(engine, clock, listOf(source)); sync.start(this); runCurrent()
        val old = engine.state(platform).lastSuccess; val gate = CompletableDeferred<Unit>()
        source.response = { withContext(NonCancellable) { gate.await() }; CalibrationResult.Success(source.sourceId, source.type, TimeAnchor(999, clock.elapsedRealtimeNanos()), 1) }
        advanceTimeBy(30_000); runCurrent(); sync.stop(); gate.complete(Unit); runCurrent()
        assertSame(old, engine.state(platform).lastSuccess); assertFalse(engine.state(platform).isCalibrating)
        assertEquals(2, source.calls)
    }
    @Test fun initialResetPreservesOffsetsButCannotDiscardASuccessfulAnchor() = runTest {
        val clock = FakeClock(); val engine = TimeEngine(clock); val source = healthy(clock, "one")
        engine.setOffsets(5, mapOf(platform to 7)); engine.resetFailedInitialSource(setOf(platform))
        assertEquals(7, engine.state(platform).manualOffsetMillis)
        engine.calibrate(platform, source)
        assertThrows(IllegalArgumentException::class.java) { engine.resetFailedInitialSource(setOf(platform)) }
        val readings = TimeReadings(); engine.readInto(readings); assertEquals(1_012_000_000, readings.shownUtcEpochNanos(platform))
    }
    @Test fun abnormalJumpNeedsSameSourceConfirmationAndRecoveryIsVisible() = runTest {
        val clock = FakeClock(); val source = healthy(clock, "one"); val checked = JumpCheckedSource(source)
        assertTrue(checked.calibrate() is CalibrationResult.Success)
        source.response = { CalibrationResult.Success(source.sourceId, source.type, TimeAnchor(11_000_000_000 + clock.elapsedRealtimeNanos(), clock.elapsedRealtimeNanos()), 1) }
        clock.advanceNanos(30_000_000_000)
        assertTrue(checked.calibrate() is CalibrationResult.Failure)
        clock.advanceNanos(30_000_000_000)
        assertTrue(checked.calibrate() is CalibrationResult.Success)
    }
}
