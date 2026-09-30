package io.github.floatingclock

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.floatingclock.time.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** These are emulator-rendered UI fixtures, not live network or measured-accuracy evidence. */
@RunWith(AndroidJUnit4::class)
class HomeScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun sixHomeStatesRenderWithActualEngineAndExportApi35Screenshots() {
        compose.mainClock.autoAdvance = false
        val clock = ClockProvider { 1_000_000_000L }
        for (dark in listOf(false, true)) for (mode in listOf("stopped", "synced", "stale")) {
            val engine = TimeEngine(clock)
            val platform = PlatformId.TAOBAO_TMALL
            runBlocking { engine.calibrate(platform, object : TimeSource {
                override val sourceId = "fixture:system-network"
                override val type = TimeSourceType.SYSTEM_NETWORK
                override suspend fun calibrate() = CalibrationResult.Success(sourceId, type,
                    TimeAnchor(Instant.parse("2026-09-29T09:02:35.218Z").epochSecond * 1_000_000_000L + 218_000_000L,
                        clock.elapsedRealtimeNanos()), 1_000_000L)
            }) }
            if (mode == "stale") engine.markStale(platform, "来源暂时不可用")
            compose.runOnUiThread { compose.activity.setContent {
                HomeTheme(dark) {
                    HomeScreen(engine, engine.state(platform), UserPreferences(), mode != "stopped", false,
                        true, true, true, true, "已停止", null,
                        {}, {}, {}, {}, {}, {}, {})
                }
            } }
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithTag("home-clock").assertContentDescriptionEquals(
                if (mode == "stopped") "当前时间 --:--:--.---" else "当前时间 17:02:35.218")
            compose.onNodeWithText("精度未验证").assertIsDisplayed()
            compose.onNodeWithTag("home-primary").assertIsDisplayed()
            compose.onNodeWithTag("home-nav-0").assertIsSelected().assertIsDisplayed()
            compose.onNodeWithTag("home-offset-zero").assertIsDisplayed()
            compose.onNodeWithText("离线截图样例", substring = true).assertDoesNotExist()
            if (mode == "synced") compose.onNodeWithText("校准正常").assertDoesNotExist()
            if (mode == "stale") compose.onNodeWithTag("home-warning").assertIsDisplayed()
            if (Build.VERSION.SDK_INT == 35) saveImage("home-${if (dark) "dark" else "light"}-$mode.png")
        }
    }

    @Test fun majorPagesRenderBothThemesAndExportScreenshots() {
        compose.waitUntil(10_000) { AppStorage.ready }
        runBlocking { AppStorage.settings.update { UserPreferences(sourceChoice = SourceChoice.DEMO) } }
        val engine = TimeEngine(ClockProvider { 0 })
        for (dark in listOf(false, true)) for (page in listOf("appearance", "settings", "diagnostics")) {
            compose.runOnUiThread { compose.activity.setContent {
                HomeTheme(dark) { SecondaryPage(page, engine, false) {} }
            } }
            compose.waitForIdle()
            compose.onNodeWithText(when (page) { "appearance" -> "实时外观预览"; "settings" -> "高级设置"; else -> "校时诊断" }).assertIsDisplayed()
            compose.onNodeWithTag("home-nav-${if (page == "appearance") 1 else if (page == "diagnostics") 2 else 0}").assertIsSelected()
            if (Build.VERSION.SDK_INT == 35) saveImage("$page-${if (dark) "dark" else "light"}.png")
        }
    }

    @Test fun secondaryPagesRemainNavigableAtDoubleFontScale() {
        compose.waitUntil(10_000) { AppStorage.ready }
        val engine = TimeEngine(ClockProvider { 0 })
        for (dark in listOf(false, true)) for (page in listOf("appearance", "settings", "diagnostics")) {
            compose.runOnUiThread { compose.activity.setContent {
                val density = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit.Density(density.density, 2f)) {
                    HomeTheme(dark) { SecondaryPage(page, engine, false) {} }
                }
            } }
            compose.onNodeWithTag("home-nav-0").assertIsDisplayed()
            compose.onNodeWithText(when (page) { "appearance" -> "应用外观"; "settings" -> "关于与隐私"; else -> "测量分组" })
                .performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun homeClockAdvancesFromSnapshotWithoutSamplingAndClearsOnStop() {
        compose.mainClock.autoAdvance = false
        var now = 0L
        val clock = ClockProvider { now }
        val engine = TimeEngine(clock)
        runBlocking { engine.calibrate(PlatformId.TAOBAO_TMALL, DemoTimeSource(clock, 0)) }
        val state = engine.state(PlatformId.TAOBAO_TMALL)
        val running = androidx.compose.runtime.mutableStateOf(true)
        val preferences = androidx.compose.runtime.mutableStateOf(UserPreferences())
        compose.runOnUiThread { compose.activity.setContent {
            HomeTheme {
                HomeScreen(engine, state, preferences.value, running.value, false, true, true,
                    true, true, "", null, {}, {}, {}, {}, {}, {}, {})
            }
        } }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("home-clock").assertContentDescriptionEquals("当前时间 08:00:00.000")
        compose.runOnUiThread { now = 1_234_000_000L }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("home-clock").assertContentDescriptionEquals("当前时间 08:00:01.234")
        compose.runOnUiThread { running.value = false }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("home-clock").assertContentDescriptionEquals("当前时间 --:--:--.---")
        compose.runOnUiThread { preferences.value = UserPreferences(globalOffsetMillis = -15) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("home-offset-active").assertIsDisplayed()
        compose.onNodeWithText("-15 ms").assertIsDisplayed()
        compose.onNodeWithTag("home-offset-zero").assertDoesNotExist()
        assertSame(state.lastSuccess, engine.state(PlatformId.TAOBAO_TMALL).lastSuccess)
    }

    private fun saveImage(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.DESCRIPTION, "Offline UI fixture; simulated calibration, not a precision measurement")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FloatingClock")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (failure: Exception) { resolver.delete(uri, null, null); throw failure }
    }

    @Test fun homepagePrimaryButtonStartsAndStopsExistingService() {
        compose.mainClock.autoAdvance = false
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        shell("input keyevent 224"); shell("wm dismiss-keyguard")
        shell("appops set ${BuildConfig.APPLICATION_ID} SYSTEM_ALERT_WINDOW allow")
        compose.waitUntil(10_000) { AppStorage.ready }
        runBlocking { AppStorage.settings.update { UserPreferences(sourceChoice = SourceChoice.DEMO) } }
        compose.waitUntil(10_000) { OverlayState.sourceChoice == SourceChoice.DEMO }
        compose.activityRule.scenario.recreate()
        compose.mainClock.advanceTimeBy(100)
        try {
            compose.onNodeWithText("开启悬浮窗").performClick()
            compose.waitUntil(10_000) { OverlayState.running && OverlayState.timeState.lastSuccess != null }
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithText("停止悬浮窗").performClick()
            compose.waitUntil(10_000) { !OverlayState.running }
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithTag("home-clock").assertContentDescriptionEquals("当前时间 --:--:--.---")
        } finally { compose.runOnUiThread { compose.activity.stopOverlay() } }
    }
}
