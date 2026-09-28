package io.github.floatingclock.time

/** Android implementations must use SystemClock.elapsedRealtimeNanos(), including sleep. */
fun interface ClockProvider {
    fun elapsedRealtimeNanos(): Long
}

enum class TimeSourceType {
    OFFICIAL_API, HTTP_ESTIMATE, SYSTEM_NETWORK, NTP,
}

/** Business display entries, never a statement about the origin of time. */
enum class PlatformId {
    TAOBAO_TMALL, JD, MEITUAN, PDD, DOUYIN,
}

enum class CalibrationStatus {
    STOPPED, INITIALIZING, SYNCED, RETRYING, STALE, RESELECT_REQUIRED, PERMISSION_BLOCKED,
}

/** A source is independent of the shopping platform selected for display. */
interface TimeSource {
    val sourceId: String
    val type: TimeSourceType

    /** Implementations must be cancellable, bounded and perform network I/O off the UI thread. */
    suspend fun calibrate(): CalibrationResult
}

/** In-memory anchor, valid only in the current manually started session and device boot. */
data class TimeAnchor(
    val serverUtcEpochNanos: Long,
    val localMonotonicNanos: Long,
) {
    init {
        require(localMonotonicNanos >= 0) { "Monotonic timestamp must be non-negative" }
    }
}

/** A successful sample does not establish independently verified platform accuracy. */
sealed interface CalibrationResult {
    val sourceId: String

    data class Success(
        override val sourceId: String,
        val sourceType: TimeSourceType,
        val anchor: TimeAnchor,
        val resolutionNanos: Long,
        val estimatedUncertaintyNanos: Long? = null,
        val estimatedOffsetNanos: Long? = null,
        val uncertaintyEvidence: String? = null,
        val roundTripNanos: Long? = null,
        val endpoint: String? = null,
        val measurementNotes: String? = null,
    ) : CalibrationResult {
        init {
            require(sourceId.isNotBlank())
            require(resolutionNanos > 0)
            require(roundTripNanos == null || roundTripNanos >= 0)
            require(estimatedUncertaintyNanos == null || estimatedUncertaintyNanos >= 0)
            require(estimatedUncertaintyNanos == null || !uncertaintyEvidence.isNullOrBlank()) {
                "An uncertainty estimate requires documented evidence; otherwise leave it unknown"
            }
        }
    }

    /** The session owner must retain the last anchor and retry this source, never silently switch. */
    data class Failure(
        override val sourceId: String,
        val reason: String,
    ) : CalibrationResult {
        init {
            require(sourceId.isNotBlank())
            require(reason.isNotBlank())
        }
    }
}

/** Immutable snapshot. SYNCED means a sample was accepted, not verified official accuracy. */
data class PlatformTimeState(
    val platformId: PlatformId,
    val sourceId: String? = null,
    val sourceType: TimeSourceType? = null,
    val status: CalibrationStatus = CalibrationStatus.STOPPED,
    val isCalibrating: Boolean = false,
    val lastSuccess: CalibrationResult.Success? = null,
    val failureReason: String? = null,
    val manualOffsetMillis: Long = 0,
    val anchorUsable: Boolean = false,
) {
    /** Source UTC at the last successful anchor, not the host's mutable wall clock. */
    val lastSuccessfulCalibrationUtcEpochNanos: Long?
        get() = lastSuccess?.anchor?.serverUtcEpochNanos

    // No independent reference measurements have been collected.
    val measuredErrorNanos: Long? get() = null
    val accuracyVerified: Boolean get() = false
}

/** Caller-owned reusable buffer. Use one per reader; do not share a buffer across threads. */
class TimeReadings {
    internal val utcNanos = LongArray(PlatformId.entries.size)
    internal val available = BooleanArray(PlatformId.entries.size)
    internal val states = Array(PlatformId.entries.size) { PlatformTimeState(PlatformId.entries[it]) }

    var monotonicNanos: Long = 0
        internal set
    var globalManualOffsetMillis: Long = 0
        internal set

    fun hasTime(platform: PlatformId): Boolean = available[platform.ordinal]
    fun state(platform: PlatformId): PlatformTimeState = states[platform.ordinal]

    fun shownUtcEpochNanos(platform: PlatformId): Long {
        check(hasTime(platform)) { "No usable calibrated time for $platform" }
        return utcNanos[platform.ordinal]
    }

    /** Floor division also handles dates before the Unix epoch correctly. */
    fun shownUtcEpochMillis(platform: PlatformId): Long =
        Math.floorDiv(shownUtcEpochNanos(platform), 1_000_000L)
}
