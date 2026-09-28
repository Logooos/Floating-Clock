package io.github.floatingclock.time

/** Android implementations must use SystemClock.elapsedRealtimeNanos(), including sleep. */
fun interface ClockProvider {
    fun elapsedRealtimeNanos(): Long
}

enum class TimeSourceType {
    OFFICIAL_API, HTTP_ESTIMATE, SYSTEM_NETWORK, NTP,
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
)

/** A successful sample does not establish independently verified platform accuracy. */
sealed interface CalibrationResult {
    val sourceId: String

    data class Success(
        override val sourceId: String,
        val sourceType: TimeSourceType,
        val anchor: TimeAnchor,
        val resolutionNanos: Long,
        val estimatedUncertaintyNanos: Long? = null,
    ) : CalibrationResult {
        init {
            require(sourceId.isNotBlank())
            require(resolutionNanos > 0)
            require(estimatedUncertaintyNanos == null || estimatedUncertaintyNanos >= 0)
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
