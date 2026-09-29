package io.github.floatingclock

import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.floatingclock.time.ClockProvider
import io.github.floatingclock.time.TimeEngine
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlaySmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    private fun main(action: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) action()
        else InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    }
    private fun await(condition: () -> Boolean) = compose.waitUntil(10_000) {
        var result = false
        main { result = condition() }
        result
    }
    private fun windows(): List<OverlayClockView> {
        var views = emptyList<OverlayClockView>()
        main { views = WindowInspector.getGlobalWindowViews().filterIsInstance<OverlayClockView>() }
        return views
    }
    private fun windowBounds(): Rect {
        val bounds = Rect()
        main {
            val view = windows().single()
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            bounds.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }
        return bounds
    }
    private fun menuClick(text: String) = main {
        val matches = ArrayList<View>()
        windows().single().findViewsWithText(matches, text, View.FIND_VIEWS_WITH_TEXT)
        assertTrue("Menu item missing: $text", matches.isNotEmpty())
        matches.first().performClick()
    }
    private fun start() {
        await { compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
        main { compose.activity.startOverlay() }
        await { OverlayState.running || !OverlayState.requested }
        main { assertTrue(OverlayState.message, OverlayState.running) }
        await { windows().singleOrNull()?.let { it.isAttachedToWindow && it.width > 0 && it.height > 0 } == true }
    }

    @Before fun prepare() {
        shell("input keyevent 224"); shell("wm dismiss-keyguard")
        shell("appops set ${BuildConfig.APPLICATION_ID} SYSTEM_ALERT_WINDOW allow")
        shell("appops set ${BuildConfig.APPLICATION_ID} POST_NOTIFICATION allow")
        await { AppStorage.ready }
        kotlinx.coroutines.runBlocking { AppStorage.settings.update { UserPreferences(sourceChoice = SourceChoice.DEMO) } }
        await { OverlayState.sourceChoice == SourceChoice.DEMO && OverlayState.config == OverlayConfig() }
    }

    @After fun cleanup() {
        shell("input keyevent 224"); shell("wm dismiss-keyguard")
        main { compose.activity.stopOverlay() }
        await { !OverlayState.running }
        main { OverlayState.message = "未启动"; OverlayState.configure(OverlayConfig()); NetworkSources.testSource = null }
    }

    @Test fun screenOffCancelsSourceAndUnlockNeverRestartsRequests() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        main {
            NetworkSources.testSource = object : io.github.floatingclock.time.TimeSource {
                override val sourceId = "test:cancellable-ntp"
                override val type = io.github.floatingclock.time.TimeSourceType.NTP
                override suspend fun calibrate(): io.github.floatingclock.time.CalibrationResult {
                    calls.incrementAndGet()
                    try { kotlinx.coroutines.awaitCancellation() } finally { cancelled.set(true) }
                }
            }
        }
        start(); await { calls.get() == 1 }
        shell("input keyevent 223")
        await { !OverlayState.running && cancelled.get() && OverlayState.onSyncNow == null }
        shell("input keyevent 224"); shell("wm dismiss-keyguard")
        await { compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
        main { assertFalse(OverlayState.running); assertEquals(1, calls.get()) }
    }

    @Test fun permissionDeniedDoesNotStart() {
        shell("appops set ${BuildConfig.APPLICATION_ID} SYSTEM_ALERT_WINDOW deny")
        main { compose.activity.startOverlay(); assertFalse(OverlayState.requested); assertFalse(OverlayState.running) }
    }

    @Test fun autoSourceCatalogMatchesApiWithoutRequestingNetwork() {
        main {
            val sources = NetworkSources.create(SourceChoice.AUTO, "", ClockProvider(SystemClock::elapsedRealtimeNanos))
            val expected = if (Build.VERSION.SDK_INT >= 33) io.github.floatingclock.time.TimeSourceType.SYSTEM_NETWORK
                else io.github.floatingclock.time.TimeSourceType.NTP
            assertEquals(expected, sources.first().type)
            assertTrue(sources.none { it.type == io.github.floatingclock.time.TimeSourceType.OFFICIAL_API })
        }
    }

    @Test fun repeatedStartCreatesOneWindowAndStops() {
        start()
        main { compose.activity.startOverlay() }
        compose.waitUntil(10_000) { windows().size == 1 }
        main { compose.activity.stopOverlay() }
        await { !OverlayState.running }
        compose.waitUntil(10_000) { windows().isEmpty() }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun notificationDenialStillAllowsHomeStop() {
        shell("appops set ${BuildConfig.APPLICATION_ID} POST_NOTIFICATION ignore")
        assertFalse(compose.activity.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled())
        start()
        main { compose.activity.stopOverlay() }
        await { !OverlayState.running }
    }

    @Test fun notificationStopActionReleasesService() {
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${BuildConfig.APPLICATION_ID} android.permission.POST_NOTIFICATIONS")
        start()
        val manager = compose.activity.getSystemService(android.app.NotificationManager::class.java)
        val notification = manager.activeNotifications.single { it.id == 1 }.notification
        notification.actions.single().actionIntent.send()
        await { !OverlayState.running && !OverlayState.requested }
        compose.waitUntil(10_000) { windows().isEmpty() }
    }

    @Test fun lockStopsAndUnlockDoesNotResume() {
        start()
        shell("input keyevent 223")
        await { !OverlayState.running && !OverlayState.requested }
        shell("input keyevent 224"); shell("wm dismiss-keyguard")
        main { assertFalse(OverlayState.running); assertFalse(OverlayState.requested) }
        compose.waitUntil(10_000) { windows().isEmpty() }
    }

    @Test fun revokePermissionReleasesWindow() {
        start()
        shell("appops set ${BuildConfig.APPLICATION_ID} SYSTEM_ALERT_WINDOW deny")
        await { !OverlayState.running }
        compose.waitUntil(10_000) { windows().isEmpty() }
    }

    @Test fun clickMenuChangesSelectionAndLongPressDrags() {
        start()
        compose.waitUntil(10_000) { windows().size == 1 }
        val before = windowBounds()
        val tapTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(tapTime, SystemClock.uptimeMillis(), action,
                before.centerX().toFloat(), before.centerY().toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue("Touch injection failed", automation.injectInputEvent(event, true))
            event.recycle()
        }
        await { windows().single().menuOpen }
        menuClick("京东")
        await { io.github.floatingclock.time.PlatformId.JD in OverlayState.config.platforms }
        menuClick("关闭菜单")
        await { !windows().single().menuOpen }
        val downTime = SystemClock.uptimeMillis()
        fun touch(action: Int, x: Int, y: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue("Drag injection failed", automation.injectInputEvent(event, true))
            event.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, before.centerX(), before.centerY())
        val longPress = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ longPress.countDown() },
            ViewConfiguration.getLongPressTimeout().toLong() + 150)
        assertTrue(longPress.await(5, java.util.concurrent.TimeUnit.SECONDS))
        touch(MotionEvent.ACTION_MOVE, before.centerX() + 60, before.centerY() + 80)
        touch(MotionEvent.ACTION_UP, before.centerX() + 60, before.centerY() + 80)
        compose.waitUntil(10_000) {
            val after = windowBounds()
            after.left != before.left || after.top != before.top
        }
    }

    @Test fun hiddenAndDetachedViewCancelFrames() {
        lateinit var view: OverlayClockView
        main {
            view = OverlayClockView(compose.activity, TimeEngine(ClockProvider { 0 }), { true }, {}, { _, _ -> }, {})
            compose.activity.addContentView(view, android.view.ViewGroup.LayoutParams(320, 200))
        }
        await { view.frameScheduled }
        main {
            view.visibility = View.GONE
            assertFalse(view.frameScheduled)
            view.visibility = View.VISIBLE
        }
        await { view.frameScheduled }
        main {
            view.performClick()
            assertTrue(view.menuOpen)
            assertFalse(view.frameScheduled)
            view.dispose()
            assertFalse(view.frameScheduled)
            (view.parent as android.view.ViewGroup).removeView(view)
            assertFalse(view.frameScheduled)
        }
    }

    @Test fun styleAndOffsetsUpdateExistingWindowWithoutRecalibration() {
        start()
        val view = windows().single()
        val anchor = OverlayState.timeState.lastSuccess
        kotlinx.coroutines.runBlocking { AppStorage.settings.update {
            it.preset(VisualStyle.LIGHT).copy(fontSizeSp = 40f, globalOffsetMillis = -250, zoneId = "UTC")
        } }
        await { OverlayState.preferences.fontSizeSp == 40f && view.width > 0 }
        main { assertSame(view, windows().single()); assertTrue(view.frameScheduled); assertSame(anchor, OverlayState.timeState.lastSuccess) }
    }

    @Test fun largeFontNeverShrinksWarningPlate() = main {
        val previous = OverlayState.preferences
        OverlayState.preferences = UserPreferences(fontSizeSp = 64f).preset(VisualStyle.LIGHT)
        val view = OverlayClockView(compose.activity, TimeEngine(ClockProvider { 0 }), { true }, {}, { _, _ -> }, {})
        val bitmap = android.graphics.Bitmap.createBitmap(500, 500, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            view.layout(0, 0, 500, 500)
            view.draw(android.graphics.Canvas(bitmap))
            val y = (30 * compose.activity.resources.displayMetrics.density).toInt()
            assertEquals(android.graphics.Color.rgb(25, 29, 35), bitmap.getPixel(490, y))
        } finally { view.dispose(); bitmap.recycle(); OverlayState.preferences = previous }
    }

    @Test fun activityRecreationRestoresPreferencesWithoutStartingRequests() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        main { NetworkSources.testSource = object : io.github.floatingclock.time.TimeSource {
            override val sourceId = "test:no-auto-start"
            override val type = io.github.floatingclock.time.TimeSourceType.NTP
            override suspend fun calibrate(): io.github.floatingclock.time.CalibrationResult { calls.incrementAndGet(); error("Must not request") }
        } }
        kotlinx.coroutines.runBlocking { AppStorage.settings.update { it.copy(zoneId = "UTC", globalOffsetMillis = 321) } }
        compose.activityRule.scenario.recreate()
        await { AppStorage.ready && OverlayState.preferences.zoneId == "UTC" }
        main { assertEquals(321L, OverlayState.preferences.globalOffsetMillis); assertFalse(OverlayState.running); assertEquals(0, calls.get()) }
    }

    @Test fun manualRestartRequiresFreshCalibrationAfterLock() {
        start()
        await { OverlayState.timeState.lastSuccess != null }
        shell("input keyevent 223")
        await { !OverlayState.running }
        shell("input keyevent 224"); shell("wm dismiss-keyguard")
        main { NetworkSources.testSource = object : io.github.floatingclock.time.TimeSource {
            override val sourceId = "test:pending"
            override val type = io.github.floatingclock.time.TimeSourceType.NTP
            override suspend fun calibrate(): io.github.floatingclock.time.CalibrationResult = kotlinx.coroutines.awaitCancellation()
        } }
        start()
        main { assertNull(OverlayState.timeState.lastSuccess); assertFalse(OverlayState.timeState.anchorUsable) }
    }
}
