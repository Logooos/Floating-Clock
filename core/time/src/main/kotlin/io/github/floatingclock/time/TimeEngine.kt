package io.github.floatingclock.time

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CancellationException

class TimeEngine(private val clock: ClockProvider) {
    // ponytail: one short lock for five entries; use immutable atomic snapshots if contention is measured.
    private val lock = Any()
    private var states = Array(PlatformId.entries.size) { PlatformTimeState(PlatformId.entries[it]) }
    private val attempts = LongArray(PlatformId.entries.size)
    private var combinedOffsetsNanos = LongArray(PlatformId.entries.size)
    private var globalOffsetMillis = 0L
    private var lastObservedMonotonicNanos = -1L

    private fun rawUtcEpochNanos(anchor: TimeAnchor, nowNanos: Long): Long {
        val elapsedNanos = Math.subtractExact(nowNanos, anchor.localMonotonicNanos)
        require(elapsedNanos >= 0) { "Anchor belongs to a future instant or an invalid session" }
        return Math.addExact(anchor.serverUtcEpochNanos, elapsedNanos)
    }

    /** Low-level arithmetic API retained for callers that own their anchor lifecycle. */
    fun rawUtcEpochNanos(anchor: TimeAnchor): Long = synchronized(lock) {
        val previous = lastObservedMonotonicNanos
        val now = observeClock()
        require(now >= previous) { "Monotonic clock moved backwards" }
        rawUtcEpochNanos(anchor, now)
    }

    fun shownUtcEpochNanos(
        anchor: TimeAnchor,
        globalManualOffsetMillis: Long = 0,
        platformManualOffsetMillis: Long = 0,
    ): Long {
        val offsetMillis = Math.addExact(globalManualOffsetMillis, platformManualOffsetMillis)
        return Math.addExact(rawUtcEpochNanos(anchor), Math.multiplyExact(offsetMillis, 1_000_000L))
    }

    /** Atomically replace the whole offset configuration. Missing platform offsets become zero. */
    fun setOffsets(globalManualOffsetMillis: Long, platformManualOffsetsMillis: Map<PlatformId, Long> = emptyMap()) {
        synchronized(lock) {
            val updated = Array(states.size) { index ->
                states[index].copy(manualOffsetMillis = platformManualOffsetsMillis[PlatformId.entries[index]] ?: 0)
            }
            val offsets = LongArray(states.size) { index ->
                Math.multiplyExact(Math.addExact(globalManualOffsetMillis, updated[index].manualOffsetMillis), 1_000_000L)
            }
            states = updated
            combinedOffsetsNanos = offsets
            globalOffsetMillis = globalManualOffsetMillis
        }
    }

    fun state(platform: PlatformId): PlatformTimeState = synchronized(lock) { states[platform.ordinal] }

    suspend fun calibrate(platform: PlatformId, source: TimeSource) = calibrate(setOf(platform), source)

    /** One explicit sample shared by these entries. No retry, source fallback or I/O during reads. */
    suspend fun calibrate(platforms: Set<PlatformId>, source: TimeSource) {
        val targets = platforms.toSet()
        require(targets.isNotEmpty())
        val sourceId = source.sourceId
        val sourceType = source.type
        require(sourceId.isNotBlank())
        val requestIds = LongArray(states.size)
        val startedAt = synchronized(lock) {
            for (platform in targets) {
                val previous = states[platform.ordinal]
                require(previous.sourceId == null || (previous.sourceId == sourceId && previous.sourceType == sourceType)) {
                    "Changing a source requires a new explicitly started engine session"
                }
            }
            val now = observeClock()
            for (platform in targets) {
                val index = platform.ordinal
                requestIds[index] = ++attempts[index]
                states[index] = states[index].copy(
                    sourceId = sourceId, sourceType = sourceType, isCalibrating = true,
                    status = if (states[index].lastSuccess == null) CalibrationStatus.INITIALIZING else states[index].status,
                )
            }
            now
        }
        // Deliberately outside the lock: a suspended/slow adapter must never block display reads.
        val result = try {
            source.calibrate()
        } catch (cancelled: CancellationException) {
            complete(targets, requestIds, startedAt, sourceId, sourceType,
                CalibrationResult.Failure(sourceId, "Calibration cancelled"))
            throw cancelled
        } catch (_: Exception) {
            CalibrationResult.Failure(sourceId, "Time source failed")
        }
        complete(targets, requestIds, startedAt, sourceId, sourceType, result)
    }

    private fun complete(
        targets: Set<PlatformId>, requestIds: LongArray, startedAt: Long,
        sourceId: String, sourceType: TimeSourceType, result: CalibrationResult,
    ) = synchronized(lock) {
        val now = observeClock()
        val valid = try {
            require(result.sourceId == sourceId)
            if (result is CalibrationResult.Success) {
                require(result.sourceType == sourceType)
                require(result.anchor.localMonotonicNanos in startedAt..now)
                rawUtcEpochNanos(result.anchor, now)
            }
            true
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: ArithmeticException) {
            false
        }
        for (platform in targets) {
            val index = platform.ordinal
            if (attempts[index] != requestIds[index]) continue // A newer attempt/invalidation wins.
            val previous = states[index]
            states[index] = if (valid && result is CalibrationResult.Success) {
                previous.copy(status = CalibrationStatus.SYNCED, isCalibrating = false,
                    lastSuccess = result, failureReason = null, anchorUsable = true)
            } else {
                previous.copy(
                    status = if (previous.lastSuccess != null && previous.status != CalibrationStatus.STALE)
                        CalibrationStatus.RETRYING else CalibrationStatus.STALE,
                    isCalibrating = false,
                    failureReason = if (valid && result is CalibrationResult.Failure) result.reason else "Invalid calibration sample",
                )
            }
        }
    }

    /** Explicit policy decision; retains the old anchor as stale data. No expiry interval is invented. */
    fun markStale(platform: PlatformId, reason: String) = synchronized(lock) {
        require(reason.isNotBlank())
        val index = platform.ordinal
        attempts[index]++
        states[index] = states[index].copy(status = CalibrationStatus.STALE, isCalibrating = false, failureReason = reason)
    }

    /** One clock read and one consistent configuration for all entries; no steady-state allocations. */
    fun readInto(output: TimeReadings) = synchronized(lock) {
        output.available.fill(false)
        val now = observeClock()
        output.monotonicNanos = now
        output.globalManualOffsetMillis = globalOffsetMillis
        try {
            for (index in states.indices) {
                val state = states[index]
                output.states[index] = state
                val sample = state.lastSuccess
                if (sample != null && state.anchorUsable) {
                    output.utcNanos[index] = Math.addExact(rawUtcEpochNanos(sample.anchor, now), combinedOffsetsNanos[index])
                    output.available[index] = true
                }
            }
        } catch (failure: ArithmeticException) {
            output.available.fill(false)
            throw failure
        }
    }

    /** Must be called under lock. Rollback invalidates all active anchors and in-flight results. */
    private fun observeClock(): Long {
        val now = clock.elapsedRealtimeNanos()
        if (now < 0 || now < lastObservedMonotonicNanos) {
            for (index in states.indices) {
                attempts[index]++
                states[index] = states[index].copy(status = CalibrationStatus.STALE, isCalibrating = false,
                    anchorUsable = false, failureReason = "Monotonic clock moved backwards")
            }
        }
        lastObservedMonotonicNanos = now
        require(now >= 0) { "Monotonic timestamp must be non-negative" }
        return now
    }
}

/** Display-only timezone; fractional milliseconds are truncated, never rounded upward. */
class MillisecondTimeFormatter(zoneId: ZoneId = ZoneId.of("Asia/Shanghai")) {
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT).withZone(zoneId)

    fun format(utcEpochNanos: Long): String =
        formatter.format(Instant.ofEpochSecond(0, utcEpochNanos))
}
