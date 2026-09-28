package io.github.floatingclock.time

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

internal class FakeClock : ClockProvider {
    val monotonicNanos = AtomicLong(0)
    val readCount = AtomicInteger(0)
    var wallUtcEpochMillis = 0L

    override fun elapsedRealtimeNanos(): Long {
        readCount.incrementAndGet()
        return monotonicNanos.get()
    }

    fun advanceNanos(durationNanos: Long) {
        require(durationNanos >= 0)
        monotonicNanos.updateAndGet { Math.addExact(it, durationNanos) }
    }
}

internal class FakeTimeSource(
    private val clock: ClockProvider,
    override val sourceId: String = "fake:ntp",
    override val type: TimeSourceType = TimeSourceType.NTP,
) : TimeSource {
    var serverUtcEpochNanos = 1_000_000_000L
    var calls = 0
    var response: suspend () -> CalibrationResult = {
        CalibrationResult.Success(sourceId, type,
            TimeAnchor(serverUtcEpochNanos, clock.elapsedRealtimeNanos()), resolutionNanos = 1_000_000)
    }

    override suspend fun calibrate(): CalibrationResult {
        calls++
        return response()
    }
}

/** Only for immediate fakes. A genuinely suspended operation must be explicitly resumed by its test. */
internal fun <T> runImmediate(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) { outcome = result }
    })
    return checkNotNull(outcome) { "Fake unexpectedly suspended" }.getOrThrow()
}
