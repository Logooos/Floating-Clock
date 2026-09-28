package io.github.floatingclock.time

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Session-local ordering; a runtime failure must never re-enter initial selection. */
fun initialSourceOrder(apiLevel: Int, system: TimeSource?, primary: TimeSource, backup: TimeSource): List<TimeSource> =
    if (apiLevel >= 33 && system != null) listOf(system, primary, backup) else listOf(primary, backup)

/** Shared across service restarts. Manual actions cannot bypass the per-endpoint request budget. */
class RequestBudget(private val clock: ClockProvider, private val minimumMillis: Long = 30_000) {
    init { require(minimumMillis in 1..300_000) }
    private val last = mutableMapOf<String, Long>()
    @Synchronized fun claim(id: String): Boolean {
        val now = clock.elapsedRealtimeNanos()
        require(now >= 0)
        val previous = last[id]
        if (previous != null && (now < previous || now - previous < minimumMillis * 1_000_000)) return false
        last[id] = now
        return true
    }
}

class BudgetedTimeSource(private val delegate: TimeSource, private val budget: RequestBudget) : TimeSource {
    override val sourceId get() = delegate.sourceId
    override val type get() = delegate.type
    override suspend fun calibrate(): CalibrationResult =
        if (budget.claim(sourceId)) delegate.calibrate() else CalibrationResult.Failure(sourceId, "请求限频：请等待至少 30 秒")
}

class SyncPolicy(val minimumMillis: Long = 30_000, val maximumMillis: Long = 300_000) {
    init { require(minimumMillis in 1..maximumMillis && maximumMillis <= 3_600_000) }
    var failures = 0
        private set
    private var stable = 0
    fun nextMillis(success: Boolean, adjustmentNanos: Long?, roundTripNanos: Long?): Long {
        if (!success) {
            stable = 0
            failures = (failures + 1).coerceAtMost(3)
            return if (failures >= 3) maximumMillis else (minimumMillis * (1L shl (failures - 1))).coerceAtMost(maximumMillis)
        }
        failures = 0
        stable = if (adjustmentNanos != null && adjustmentNanos in -50_000_000..50_000_000 &&
            (roundTripNanos == null || roundTripNanos <= 250_000_000)) (stable + 1).coerceAtMost(10) else 0
        return (minimumMillis * (1L shl stable)).coerceAtMost(maximumMillis)
    }
}

/** Serial-session guard: a >2 s jump needs a second agreeing sample, not an accuracy claim. */
internal class JumpCheckedSource(private val source: TimeSource) : TimeSource {
    override val sourceId get() = source.sourceId
    override val type get() = source.type
    private var accepted: CalibrationResult.Success? = null
    private var suspect: CalibrationResult.Success? = null
    override suspend fun calibrate(): CalibrationResult {
        val result = source.calibrate()
        currentCoroutineContext().ensureActive()
        if (result !is CalibrationResult.Success) { suspect = null; return result }
        fun difference(previous: CalibrationResult.Success): Long = Math.subtractExact(result.anchor.serverUtcEpochNanos,
            Math.addExact(previous.anchor.serverUtcEpochNanos, checkedElapsed(previous.anchor.localMonotonicNanos, result.anchor.localMonotonicNanos)))
        if (accepted?.let { difference(it) !in -2_000_000_000L..2_000_000_000L } == true &&
            suspect?.let { difference(it) in -250_000_000L..250_000_000L } != true) {
            suspect = result
            return CalibrationResult.Failure(sourceId, "异常校准跳变：保留旧锚点，等待同源复核")
        }
        accepted = result; suspect = null
        return result
    }
}

/** One serial worker, a conflated manual request, no per-frame networking or concurrent requests. */
class TimeSynchronizer(
    private val engine: TimeEngine,
    private val clock: ClockProvider,
    private val candidates: List<TimeSource>,
    private val platforms: Set<PlatformId> = PlatformId.entries.toSet(),
    private val policy: SyncPolicy = SyncPolicy(),
    private val onUpdate: (PlatformTimeState, Long?, Long) -> Unit = { _, _, _ -> },
) {
    init { require(candidates.isNotEmpty() && platforms.isNotEmpty()); require(candidates.map { it.sourceId }.distinct().size == candidates.size) }
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    @Volatile private var closed = false
    @Volatile
    var selectedSourceId: String? = null
        private set

    @Synchronized fun start(scope: CoroutineScope) {
        check(!closed)
        if (job != null) return
        job = scope.launch {
            var selected = candidates.last()
            for ((index, source) in candidates.map { JumpCheckedSource(it) }.withIndex()) {
                ensureActive()
                if (index > 0) engine.resetFailedInitialSource(platforms)
                engine.calibrate(platforms, source)
                ensureActive()
                selected = source
                if (engine.state(platforms.first()).status == CalibrationStatus.SYNCED) break
            }
            selectedSourceId = selected.sourceId
            var previous: CalibrationResult.Success? = null
            while (isActive) {
                val state = engine.state(platforms.first())
                val success = state.status == CalibrationStatus.SYNCED
                val sample = state.lastSuccess
                val adjustment = if (success && sample != null && previous != null) {
                    runCatching { Math.subtractExact(sample.anchor.serverUtcEpochNanos,
                        Math.addExact(previous!!.anchor.serverUtcEpochNanos,
                            Math.subtractExact(sample.anchor.localMonotonicNanos, previous!!.anchor.localMonotonicNanos))) }.getOrNull()
                } else null
                if (success) previous = sample
                val interval = policy.nextMillis(success, adjustment, sample?.roundTripNanos)
                if (policy.failures >= 3) platforms.forEach { engine.markStale(it, "连续校准失败；保留旧基准，定期探测原来源") }
                onUpdate(engine.state(platforms.first()), adjustment, interval)
                val finishedAt = clock.elapsedRealtimeNanos()
                val manual = withTimeoutOrNull(interval) { requests.receive(); true } ?: false
                if (manual) {
                    val passed = checkedElapsed(finishedAt, clock.elapsedRealtimeNanos()) / 1_000_000
                    delay((policy.minimumMillis - passed).coerceAtLeast(0))
                    while (requests.tryReceive().isSuccess) { /* Coalesce taps while rate-limited. */ }
                }
                ensureActive()
                // The selected source object/endpoint stays fixed, including during STALE probing.
                engine.calibrate(platforms, selected)
                ensureActive()
            }
        }
    }

    fun syncNow(): Boolean = !closed && requests.trySend(Unit).isSuccess
    @Synchronized fun stop() { closed = true; job?.cancel(); requests.close() }
}
