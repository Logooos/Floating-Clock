package io.github.floatingclock

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import io.github.floatingclock.time.ClockProvider
import io.github.floatingclock.time.TimeEngine

@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun useOfflineDemo() {
        compose.waitUntil(10_000) { AppStorage.ready }
        kotlinx.coroutines.runBlocking { AppStorage.settings.update { UserPreferences(sourceChoice = SourceChoice.DEMO) } }
        compose.waitUntil(10_000) { OverlayState.sourceChoice == SourceChoice.DEMO }
    }

    @Test fun launcherShowsDemoHome() {
        compose.onNodeWithText(compose.activity.getString(R.string.app_name)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.development_version, BuildConfig.VERSION_NAME))
            .assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.demo_notice)).assertIsDisplayed()
        compose.onNodeWithText("未启动")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.demo_platform))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.demo_accuracy_unknown))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("设置")
            .performScrollTo().assertIsDisplayed().assertIsEnabled()
    }

    @Test fun injectedMonotonicClockChangesDisplayedTime() {
        compose.mainClock.autoAdvance = false
        var monotonicNanos = 0L
        val clock = ClockProvider { monotonicNanos }
        val engine = TimeEngine(clock)
        val source = DemoTimeSource(clock, serverUtcEpochNanos = 0)
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme { DemoClock(engine, source, active = true) }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("demo-clock").assertTextEquals("08:00:00.000")
        compose.runOnUiThread { monotonicNanos = 1_234_000_000L }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("demo-clock").assertTextEquals("08:00:01.234")
    }
}
