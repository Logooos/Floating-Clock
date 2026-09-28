package io.github.floatingclock

import android.Manifest
import android.content.Intent
import android.app.NotificationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import io.github.floatingclock.time.*
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val clock = ClockProvider(SystemClock::elapsedRealtimeNanos)
    private val engine = TimeEngine(clock)
    private val demoSource = DemoTimeSource(clock)
    private var active by mutableStateOf(false)
    private var overlayGranted by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold { insets ->
                    Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                        Text(stringResource(R.string.development_version, BuildConfig.VERSION_NAME))
                        if (OverlayState.sourceChoice == SourceChoice.DEMO) Text(stringResource(R.string.demo_notice), color = MaterialTheme.colorScheme.primary)
                        else Text("平台仅用于分组；公共网络时间不是购物平台官方时间。")
                        Text("悬浮窗权限：${if (overlayGranted) "已授予" else "未授予"}")
                        Text(OverlayState.message)
                        Button(onClick = {
                            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                            catch (_: RuntimeException) { OverlayState.message = "无法打开系统授权页，请在系统设置中授予悬浮窗权限" }
                        }) { Text("申请悬浮窗权限") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = ::startOverlay, enabled = !OverlayState.requested && !OverlayState.running) { Text("启动悬浮窗") }
                            Button(onClick = ::stopOverlay, enabled = OverlayState.requested || OverlayState.running) { Text("停止悬浮窗") }
                        }
                        Text(if (notificationsGranted) "通知已允许，可从通知停止" else "通知未允许；仍可从本页停止悬浮窗")
                        if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted) {
                            TextButton(onClick = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("允许通知（可选）") }
                        }
                        Text(String.format(Locale.ROOT, "实绘 FPS：%.1f（非精度指标）", OverlayState.fps))
                        Text("部分应用会隐藏悬浮窗；锁屏停止，解锁不自动恢复。")
                        SourceControls()
                        PlatformControls()
                        if (OverlayState.sourceChoice == SourceChoice.DEMO) DemoClock(engine, demoSource, active)
                        Text(stringResource(R.string.accuracy_notice))
                        Button(onClick = {}, enabled = false) { Text(stringResource(R.string.settings_placeholder)) }
                    }
                }
            }
        }
    }

    private fun refreshPermissions() {
        overlayGranted = Settings.canDrawOverlays(this)
        notificationsGranted = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }

    internal fun startOverlay() {
        refreshPermissions()
        if (!overlayGranted) { OverlayState.message = "需要悬浮窗权限"; return }
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || OverlayState.requested || OverlayState.running) return
        if (OverlayState.sourceChoice == SourceChoice.HTTP) {
            try { HttpDateTimeSource(java.net.URL(OverlayState.httpUrl), clock) }
            catch (_: Exception) { OverlayState.message = "请输入有效的公开 HTTPS URL，不含认证、查询参数或片段"; return }
        }
        OverlayState.requested = true
        OverlayState.message = "正在启动"
        try { startForegroundService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)) }
        catch (_: RuntimeException) {
            OverlayState.requested = false
            OverlayState.message = "系统拒绝启动前台服务，请返回首页重试"
        }
    }

    internal fun stopOverlay() {
        OverlayState.requested = false
        stopService(Intent(this, OverlayService::class.java))
        OverlayState.message = "已停止"
    }

    override fun onResume() { super.onResume(); refreshPermissions() }
    override fun onStart() { super.onStart(); active = true }
    override fun onStop() { active = false; super.onStop() }
}

@Composable
private fun SourceControls() {
    var expanded by remember { mutableStateOf(false) }
    val stopped = !OverlayState.running && !OverlayState.requested
    Text("实际时间来源（所有显示平台共用）")
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = stopped) { Text(OverlayState.sourceChoice.label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SourceChoice.entries.forEach { choice ->
                DropdownMenuItem(text = { Text(choice.label) }, enabled = choice != SourceChoice.SYSTEM || Build.VERSION.SDK_INT >= 33,
                    onClick = { OverlayState.sourceChoice = choice; expanded = false })
            }
        }
    }
    if (OverlayState.sourceChoice == SourceChoice.HTTP) {
        OutlinedTextField(value = OverlayState.httpUrl, onValueChange = { OverlayState.httpUrl = it.trim() }, enabled = stopped,
            label = { Text("公开 HTTPS URL（无密钥、无查询参数）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("仅估算 Date 秒级时间；缓存或无法确认新鲜度的响应会被拒绝。")
    }
    Text("初次自动选源可兜底；运行中失败只重试原来源。更换来源需先停止。")
    Text(OverlayState.syncDetails)
    OutlinedButton(onClick = { OverlayState.onSyncNow?.invoke() }, enabled = OverlayState.running) { Text("立即同步（最短 30 秒）") }
}

@Composable
private fun PlatformControls() {
    val config = OverlayState.config
    Text("悬浮平台（1–3 个，按下方顺序显示）")
    PlatformId.entries.forEach { platform ->
        Row {
            val selected = platform in config.platforms
            Checkbox(checked = selected, modifier = Modifier.semantics { contentDescription = platform.label() },
                enabled = if (selected) config.platforms.size > 1 else config.platforms.size < 3,
                onCheckedChange = { OverlayState.configure(config.toggle(platform)) })
            Text(platform.label(), modifier = Modifier.padding(top = 12.dp))
        }
    }
    config.platforms.forEachIndexed { index, platform ->
        Row {
            Text("${index + 1}. ${platform.label()}", Modifier.weight(1f).padding(top = 12.dp))
            TextButton(onClick = { OverlayState.configure(config.move(platform, -1)) }, enabled = index > 0) { Text("上移") }
            TextButton(onClick = { OverlayState.configure(config.move(platform, 1)) }, enabled = index < config.platforms.lastIndex) { Text("下移") }
        }
    }
    Row {
        DisplayMode.entries.forEach { mode ->
            TextButton(onClick = { OverlayState.configure(config.copy(mode = mode)) }, enabled = config.mode != mode) {
                Text(when (mode) { DisplayMode.FULL -> "完整"; DisplayMode.COMPACT -> "紧凑"; DisplayMode.MINIMAL -> "极简" })
            }
        }
    }
}
