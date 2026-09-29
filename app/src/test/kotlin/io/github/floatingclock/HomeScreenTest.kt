package io.github.floatingclock

import io.github.floatingclock.time.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HomeScreenTest {
    @Test fun liveAnchorUsesEngineOffsetsAndStoppedOrInvalidNeverReadsHistory() = runBlocking {
        var now = 0L
        val engine = TimeEngine(ClockProvider { now })
        engine.calibrate(PlatformId.TAOBAO_TMALL, DemoTimeSource(ClockProvider { now }, 0))
        val state = engine.state(PlatformId.TAOBAO_TMALL)
        val preferences = UserPreferences(globalOffsetMillis = 35, platformOffsetsMillis = mapOf(PlatformId.TAOBAO_TMALL to -20))
        now = 1_000_000_000
        assertEquals(1_015_000_000L, homeTime(engine, true, state, preferences))
        assertNull(homeTime(engine, false, state, preferences))
        assertNull(homeTime(engine, true, state.copy(anchorUsable = false), preferences))
        assertNull(homeTime(engine, true, state.copy(status = CalibrationStatus.STOPPED), preferences))
    }

    @Test fun staleAndRetryStatesAreNotSuccessfulCalibration() {
        val state = PlatformTimeState(PlatformId.TAOBAO_TMALL)
        assertEquals("已停止", homeStatus(false, false, state.copy(status = CalibrationStatus.SYNCED)))
        assertEquals("正在开启", homeStatus(false, true, state))
        assertEquals("校准失效", homeStatus(true, false, state.copy(status = CalibrationStatus.STALE)))
        assertEquals("同源重试中", homeStatus(true, false, state.copy(status = CalibrationStatus.RETRYING)))
        assertEquals("无可信时间", homeStatus(true, false, state))
    }

    @Test fun selectedStrategyCannotOverwriteActualSourceLabel() {
        val state = PlatformTimeState(PlatformId.JD, "android:system-network", TimeSourceType.SYSTEM_NETWORK)
        assertEquals("Android 系统网络时间", homeSource(state))
        assertEquals("公共 NTP · Google", homeSource(state.copy(sourceId = "ntp:time.google.com:123", sourceType = TimeSourceType.NTP)))
        assertEquals("离线演示 · 模拟来源", homeSource(state.copy(sourceId = "demo:public-ntp", sourceType = TimeSourceType.NTP)))
    }
}
