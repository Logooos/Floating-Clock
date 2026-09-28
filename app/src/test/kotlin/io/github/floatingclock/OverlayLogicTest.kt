package io.github.floatingclock

import io.github.floatingclock.time.PlatformId.*
import org.junit.Assert.*
import org.junit.Test

class OverlayLogicTest {
    @Test fun defaultIsOneCombinedPlatform() { assertEquals(listOf(TAOBAO_TMALL), OverlayConfig().platforms) }
    @Test fun selectionRejectsFourthAndLastRemoval() {
        val initial = OverlayConfig()
        assertEquals(initial, initial.toggle(TAOBAO_TMALL))
        val three = initial.toggle(JD).toggle(MEITUAN)
        assertEquals(three, three.toggle(PDD))
        assertEquals(listOf(TAOBAO_TMALL, MEITUAN), three.toggle(JD).platforms)
    }
    @Test fun orderingMovesOnlyRequestedPlatform() {
        val config = OverlayConfig(listOf(TAOBAO_TMALL, JD, PDD))
        assertEquals(listOf(JD, TAOBAO_TMALL, PDD), config.move(JD, -1).platforms)
        assertEquals(listOf(TAOBAO_TMALL, PDD, JD), config.move(JD, 1).platforms)
        assertEquals(config, config.move(PDD, 1))
        assertEquals(config, config.move(MEITUAN, -1))
    }
    @Test fun modeDoesNotChangeOrder() {
        val config = OverlayConfig(listOf(JD, TAOBAO_TMALL))
        DisplayMode.entries.forEach { assertEquals(config.platforms, config.copy(mode = it).platforms) }
    }
    @Test fun invalidSelectionsAreRejected() {
        listOf(emptyList(), listOf(JD, JD), listOf(JD, PDD, MEITUAN, DOUYIN)).forEach {
            assertThrows(IllegalArgumentException::class.java) { OverlayConfig(it) }
        }
    }
    @Test fun missingPermissionNeverAcquiresResources() {
        val session = OverlaySession({ fail("foreground") }, { fail("window") }, { fail("release") })
        assertFalse(session.start(false, true)); assertNotNull(session.failure)
    }
    @Test fun lockedStartNeverAcquiresResources() {
        val session = OverlaySession({ fail("foreground") }, { fail("window") }, { fail("release") })
        assertFalse(session.start(true, false))
    }
    @Test fun startAndStopAreIdempotent() {
        var promotes = 0; var attaches = 0; var releases = 0
        val session = OverlaySession({ promotes++ }, { attaches++ }, { releases++ })
        assertTrue(session.start(true, true)); assertTrue(session.start(true, true))
        session.stop(); session.stop()
        assertFalse(session.running)
        assertEquals(1, promotes); assertEquals(1, attaches); assertEquals(1, releases)
    }
    @Test fun foregroundFailureReleasesPartialResources() {
        var releases = 0
        val session = OverlaySession({ throw SecurityException() }, { fail("attach") }, { releases++ })
        assertFalse(session.start(true, true)); session.stop()
        assertEquals(1, releases); assertNotNull(session.failure)
    }
    @Test fun windowFailureReleasesForegroundAndAllowsManualRetry() {
        var fail = true; var releases = 0
        val session = OverlaySession({}, { if (fail) throw IllegalStateException() }, { releases++ })
        assertFalse(session.start(true, true)); assertEquals(1, releases)
        fail = false
        assertTrue(session.start(true, true)); session.stop(); assertEquals(2, releases)
    }
    @Test fun permissionRevocationAndLockStopExistingSession() {
        listOf(false to true, true to false).forEach { (permission, interactive) ->
            var releases = 0
            val session = OverlaySession({}, {}, { releases++ })
            session.start(true, true)
            assertFalse(session.start(permission, interactive))
            assertFalse(session.running); assertEquals(1, releases)
        }
    }
    @Test fun actualFpsUsesDrawIntervals() {
        val stats = FrameStats()
        for (frame in 0..60) stats.drawn(frame * 1_000_000_000L / 60)
        assertEquals(60.0, stats.fps, 0.001)
        stats.reset()
        for (frame in 0..120) stats.drawn(frame * 1_000_000_000L / 120)
        assertEquals(120.0, stats.fps, 0.001)
    }
    @Test fun hiddenTimeDoesNotPolluteFps() {
        val stats = FrameStats()
        stats.drawn(0); stats.drawn(1_000_000_000)
        stats.reset()
        assertEquals(0.0, stats.fps, 0.0)
        stats.drawn(20_000_000_000); stats.drawn(21_000_000_000)
        assertEquals(1.0, stats.fps, 0.0)
    }
    @Test fun cancelledDuringAttachCannotBecomeRunning() {
        lateinit var session: OverlaySession
        var releases = 0
        session = OverlaySession({}, { session.stop() }, { releases++ })
        assertFalse(session.start(true, true)); assertFalse(session.running); assertEquals(1, releases)
    }
}