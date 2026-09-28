package io.github.floatingclock

import io.github.floatingclock.time.*
import org.junit.Assert.*
import org.junit.Test

class SourceLabelsTest {
    @Test fun platformDoesNotImplyOfficialSource() {
        PlatformId.entries.forEach { platform ->
            val state = PlatformTimeState(platform, sourceId = "ntp:time.cloudflare.com:123", sourceType = TimeSourceType.NTP)
            assertEquals("公共 NTP（非平台官方）", state.sourceLabel())
            assertFalse(state.accuracyVerified)
        }
    }
    @Test fun demoHttpAndSystemLabelsAreExplicit() {
        assertEquals("模拟 NTP", PlatformTimeState(PlatformId.JD, "demo:public-ntp", TimeSourceType.NTP).sourceLabel())
        assertTrue(PlatformTimeState(PlatformId.JD, "http-date:test", TimeSourceType.HTTP_ESTIMATE).sourceLabel().contains("HTTP_ESTIMATED"))
        assertEquals("Android 系统网络时间", PlatformTimeState(PlatformId.JD, "android:network-clock", TimeSourceType.SYSTEM_NETWORK).sourceLabel())
    }
    @Test fun staleAndFailedStatesKeepVisibleWarnings() {
        assertTrue(PlatformTimeState(PlatformId.JD, status = CalibrationStatus.STALE).statusLabel().contains("失效"))
        assertTrue(PlatformTimeState(PlatformId.JD, status = CalibrationStatus.RETRYING).statusLabel().contains("旧基准"))
    }
}
