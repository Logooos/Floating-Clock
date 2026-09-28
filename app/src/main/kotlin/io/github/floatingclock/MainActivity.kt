package io.github.floatingclock

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.floatingclock.time.ClockProvider
import io.github.floatingclock.time.TimeEngine
import io.github.floatingclock.time.TimeSource

class MainActivity : ComponentActivity() {
    private val clock = ClockProvider(SystemClock::elapsedRealtimeNanos)
    private val engine = TimeEngine(clock)
    private val demoSource = DemoTimeSource(clock)
    private var active by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                HomeScreen(engine, demoSource, active)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        active = true
    }

    override fun onStop() {
        active = false
        super.onStop()
    }
}

@Composable
private fun HomeScreen(engine: TimeEngine, source: TimeSource, active: Boolean) {
    Scaffold { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.development_version, BuildConfig.VERSION_NAME))
            Text(stringResource(R.string.demo_notice), color = MaterialTheme.colorScheme.primary)
            StatusCard(stringResource(R.string.overlay_title), stringResource(R.string.overlay_placeholder))
            DemoClock(engine, source, active)
            Text(stringResource(R.string.accuracy_notice))
            Button(onClick = {}, enabled = false) {
                Text(stringResource(R.string.settings_placeholder))
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, description: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description)
        }
    }
}
