package io.github.floatingclock

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityWindowInfo
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
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun await(condition: () -> Boolean) = compose.waitUntil(10_000) {
        var result = false
        main { result = condition() }
        result
    }
    private fun windows(): List<AccessibilityWindowInfo> = automation.windows.filter { it.title?.toString() == "Floating Clock · 演示数据" }
    private fun start() { main { compose.activity.startOverlay() }; await { OverlayState.running } }

    @Before fun prepare() {
        val info = automation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        shell("input keyevent 224"); shell("wm dismiss-keyguard")
        shell("appops set ${BuildConfig.APPLICATION_ID} SYSTEM_ALERT_WINDOW allow")
        main { OverlayState.configure(OverlayConfig()) }
    }

    @After fun cleanup() {
        shell("input keyevent 224"); shell("wm dismiss-keyguard")
        main { compose.activity.stopOverlay() }
        await { !OverlayState.running }
        main { OverlayState.message = "未启动"; OverlayState.configure(OverlayConfig()) }
    }

    @Test fun permissionDeniedDoesNotStart() {
        shell("appops set ${BuildConfig.APPLICATION_ID} SYSTEM_ALERT_WINDOW deny")
        main { compose.activity.startOverlay(); assertFalse(OverlayState.requested); assertFalse(OverlayState.running) }
    }

    @Test fun repeatedStartCreatesOneWindowAndStops() {
        start()
        main { compose.activity.startOverlay() }
        compose.waitUntil(10_000) { windows().size == 1 }
        main { compose.activity.stopOverlay() }
        await { !OverlayState.running }
        compose.waitUntil(10_000) { windows().isEmpty() }
    }

    @Test fun notificationDenialStillAllowsHomeStop() {
        if (Build.VERSION.SDK_INT >= 33) assertEquals(PackageManager.PERMISSION_DENIED,
            compose.activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        start()
        main { compose.activity.stopOverlay() }
        await { !OverlayState.running }
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
        val before = Rect().also { windows().single().getBoundsInScreen(it) }
        shell("input tap ${before.centerX()} ${before.centerY()}")
        compose.waitUntil(10_000) { windows().firstOrNull()?.root?.findAccessibilityNodeInfosByText("京东")?.isNotEmpty() == true }
        val root = windows().single().root
        root.findAccessibilityNodeInfosByText("京东").first().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        await { io.github.floatingclock.time.PlatformId.JD in OverlayState.config.platforms }
        windows().single().root.findAccessibilityNodeInfosByText("关闭菜单").first()
            .performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        val downTime = SystemClock.uptimeMillis()
        fun touch(action: Int, x: Int, y: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            automation.injectInputEvent(event, true)
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
            val after = Rect().also { windows().single().getBoundsInScreen(it) }
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
            view.dispose()
            assertFalse(view.frameScheduled)
            (view.parent as android.view.ViewGroup).removeView(view)
            assertFalse(view.frameScheduled)
        }
    }
}