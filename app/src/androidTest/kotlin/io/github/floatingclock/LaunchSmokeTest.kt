package io.github.floatingclock

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun launcherShowsDemoHome() {
        compose.onNodeWithText(compose.activity.getString(R.string.app_name)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.development_version, BuildConfig.VERSION_NAME))
            .assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.demo_notice)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.overlay_placeholder))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.source_placeholder))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.settings_placeholder))
            .performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }
}
