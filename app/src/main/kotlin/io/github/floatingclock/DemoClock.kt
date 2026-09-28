package io.github.floatingclock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.floatingclock.time.CalibrationResult
import io.github.floatingclock.time.CalibrationStatus
import io.github.floatingclock.time.ClockProvider
import io.github.floatingclock.time.MillisecondTimeFormatter
import io.github.floatingclock.time.PlatformId
import io.github.floatingclock.time.PlatformTimeState
import io.github.floatingclock.time.TimeAnchor
import io.github.floatingclock.time.TimeEngine
import io.github.floatingclock.time.TimeReadings
import io.github.floatingclock.time.TimeSource
import io.github.floatingclock.time.TimeSourceType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Synthetic 2026-01-01 UTC baseline, never the device wall clock or a network response. */
internal class DemoTimeSource(
    private val clock: ClockProvider,
    private val serverUtcEpochNanos: Long = 1_767_225_600_000_000_000L,
) : TimeSource {
    override val sourceId = "demo:public-ntp"
    override val type = TimeSourceType.NTP
    override suspend fun calibrate(): CalibrationResult = CalibrationResult.Success(
        sourceId, type, TimeAnchor(serverUtcEpochNanos, clock.elapsedRealtimeNanos()),
        resolutionNanos = 1_000_000,
    )
}

@Composable
internal fun DemoClock(engine: TimeEngine, source: TimeSource, active: Boolean) {
    val lifecycle = (LocalContext.current as LifecycleOwner).lifecycle
    val platform = PlatformId.TAOBAO_TMALL
    val formatter = remember { MillisecondTimeFormatter() }
    var timeText by remember(engine) { mutableStateOf<String?>(null) }
    var state by remember(engine) { mutableStateOf(PlatformTimeState(platform)) }

    LaunchedEffect(engine, source, active, lifecycle) {
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (engine.state(platform).sourceId == null) engine.calibrate(platform, source)
            val readings = TimeReadings()
            while (isActive) {
                engine.readInto(readings)
                state = readings.state(platform)
                timeText = if (readings.hasTime(platform)) formatter.format(readings.shownUtcEpochNanos(platform)) else null
                // Presentation cadence only; elapsed time always comes from the engine.
                delay(16)
            }
        }
    }
    val sourceLabel = stringResource(when (state.sourceType) {
        TimeSourceType.OFFICIAL_API -> R.string.source_official
        TimeSourceType.HTTP_ESTIMATE -> R.string.source_http
        TimeSourceType.SYSTEM_NETWORK -> R.string.source_system
        TimeSourceType.NTP -> R.string.source_ntp
        null -> R.string.source_none
    })
    val statusLabel = stringResource(when {
        state.isCalibrating -> R.string.calibration_in_progress
        state.status == CalibrationStatus.SYNCED -> R.string.calibration_demo_success
        state.status == CalibrationStatus.RETRYING -> R.string.calibration_failed_with_history
        state.status == CalibrationStatus.STALE -> R.string.calibration_stale
        else -> R.string.no_calibrated_time
    })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.demo_platform))
        Text(timeText ?: stringResource(R.string.no_calibrated_time),
            modifier = Modifier.testTag("demo-clock"),
            style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
        Text(stringResource(R.string.demo_source, sourceLabel, state.sourceId ?: "—"))
        Text(statusLabel)
        Text(stringResource(R.string.demo_accuracy_unknown))
    }
}
