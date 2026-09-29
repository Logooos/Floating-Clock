package io.github.floatingclock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.floatingclock.time.*
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val LightHome = lightColorScheme(
    primary = Color(0xff0b57d0), onPrimary = Color.White,
    primaryContainer = Color(0xffe6eeff), onPrimaryContainer = Color(0xff17478e),
    background = Color(0xfff5f7fa), surface = Color(0xfff5f7fa),
    surfaceContainer = Color.White, onSurface = Color(0xff17212b),
    onSurfaceVariant = Color(0xff475569), outline = Color(0xff64748b),
    outlineVariant = Color(0xffdce3ec), error = Color(0xffb3261e),
    errorContainer = Color(0xfff9dedc), onErrorContainer = Color(0xff641d18),
)
private val DarkHome = darkColorScheme(
    primary = Color(0xffa8c7fa), onPrimary = Color(0xff062e6f),
    primaryContainer = Color(0xff223957), onPrimaryContainer = Color(0xffcde0ff),
    background = Color(0xff10151d), surface = Color(0xff10151d),
    surfaceContainer = Color(0xff18212d), onSurface = Color(0xffe8edf4),
    onSurfaceVariant = Color(0xffb8c4d4), outline = Color(0xff8493a7),
    outlineVariant = Color(0xff344254), error = Color(0xffffb4ab),
    errorContainer = Color(0xff601410), onErrorContainer = Color(0xffffdad5),
)
internal object HomeDesign {
    val gutter = 16.dp
    val section = 24.dp
    val gap = 8.dp
    val card = RoundedCornerShape(24.dp)
    val control = RoundedCornerShape(16.dp)
}

@Composable
internal fun HomeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkHome else LightHome,
        typography = Typography(
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
        ), content = content)
}

internal fun homeStatus(running: Boolean, requested: Boolean, state: PlatformTimeState): String = when {
    !running && requested -> "正在开启"
    !running -> "已停止"
    state.status == CalibrationStatus.STALE || state.status == CalibrationStatus.RESELECT_REQUIRED -> "校准失效"
    state.status == CalibrationStatus.RETRYING -> "同源重试中"
    state.lastSuccess == null || !state.anchorUsable -> if (state.isCalibrating) "正在校准" else "无可信时间"
    state.isCalibrating -> "正在重新校准"
    else -> "校准正常"
}

/** A read-only consumer of the session snapshot. No sampling, persistence or duplicate clock formula. */
internal fun homeTime(engine: TimeEngine, running: Boolean, state: PlatformTimeState, preferences: UserPreferences): Long? {
    if (!running || !state.anchorUsable || state.status == CalibrationStatus.STOPPED) return null
    val anchor = state.lastSuccess?.anchor ?: return null
    return engine.shownUtcEpochNanos(anchor, preferences.globalOffsetMillis,
        preferences.platformOffsetsMillis[preferences.overlay.platforms.first()] ?: 0)
}

internal fun homeSource(state: PlatformTimeState): String = when {
    state.sourceId?.startsWith("demo:") == true -> "离线演示 · 模拟来源"
    state.sourceType == TimeSourceType.SYSTEM_NETWORK -> "Android 系统网络时间"
    state.sourceType == TimeSourceType.NTP -> when {
        state.sourceId?.contains("time.cloudflare.com") == true -> "公共 NTP · Cloudflare"
        state.sourceId?.contains("time.google.com") == true -> "公共 NTP · Google"
        else -> "公共 NTP"
    }
    else -> state.sourceLabel()
}

@Composable
internal fun HomeScreen(
    engine: TimeEngine, state: PlatformTimeState, preferences: UserPreferences,
    running: Boolean, requested: Boolean, active: Boolean, ready: Boolean,
    overlayGranted: Boolean, notificationsGranted: Boolean, message: String, storageError: String?,
    start: () -> Unit, stop: () -> Unit, grantOverlay: () -> Unit, grantNotifications: () -> Unit,
    sync: () -> Unit, settings: () -> Unit, diagnostics: () -> Unit,
    fixtureLabel: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    var readFailed by remember(state, running) { mutableStateOf(false) }
    val status = if (readFailed) "无可信时间" else homeStatus(running, requested, state)
    val warning = running && (readFailed || state.status in setOf(CalibrationStatus.STALE, CalibrationStatus.RETRYING, CalibrationStatus.RESELECT_REQUIRED))
    var details by remember { mutableStateOf(false) }
    val platform = preferences.overlay.platforms.first()
    val offset = preferences.globalOffsetMillis + (preferences.platformOffsetsMillis[platform] ?: 0)
    val demo = preferences.sourceChoice == SourceChoice.DEMO || state.sourceId?.startsWith("demo:") == true
    Scaffold(containerColor = colors.background,
        bottomBar = {
            Surface(color = colors.background) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = HomeDesign.gutter).padding(top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(HomeDesign.gap)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(HomeDesign.gap)) {
                        OutlinedButton(onClick = sync, enabled = running && !state.isCalibrating,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = HomeDesign.control) { Text("重新校准") }
                        OutlinedButton(onClick = diagnostics, enabled = ready,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = HomeDesign.control) { Text("校时诊断") }
                    }
                    Button(onClick = { when { running || requested -> stop(); !overlayGranted -> grantOverlay(); else -> start() } },
                        enabled = ready || running || requested,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("home-primary"), shape = HomeDesign.control) {
                        Text(when { running || requested -> "停止悬浮窗"; !overlayGranted -> "授予悬浮权限"; else -> "开启悬浮窗" },
                            style = MaterialTheme.typography.titleMedium)
                    }
                    Text("锁屏后停止 · 解锁不自动恢复", Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
            .padding(horizontal = HomeDesign.gutter).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ClockMark()
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Floating Clock", style = MaterialTheme.typography.titleLarge)
                    Text("来源透明，让时间一目了然", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                TextButton(onClick = settings, enabled = ready) { Text("设置") }
            }
            fixtureLabel?.let { Text(it, color = colors.primary, style = MaterialTheme.typography.labelMedium) }
            if (demo) Text("演示数据 · 非真实网络校时", color = colors.primary, style = MaterialTheme.typography.labelLarge)
            Surface(shape = HomeDesign.card, color = colors.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(HomeDesign.gap)) {
                    Surface(shape = RoundedCornerShape(50), color = if (warning) colors.errorContainer else colors.primaryContainer) {
                        Text(status, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge,
                            color = if (warning) colors.onErrorContainer else colors.onPrimaryContainer)
                    }
                    Text("公共网络时钟", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                    HomeClock(engine, state, preferences, running, active) { readFailed = true }
                    HorizontalDivider(color = colors.outlineVariant)
                    Text(if (running) homeSource(state) else "尚未校准", style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
                    Text("精度未验证", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            if (warning) {
                Surface(modifier = Modifier.fillMaxWidth(), shape = HomeDesign.control, color = colors.errorContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (state.anchorUsable && !readFailed) "正在使用上次基准" else "当前没有可信时间", color = colors.onErrorContainer,
                            style = MaterialTheme.typography.titleMedium)
                        Text("${if (readFailed) "时间推演不可用，等待新的校准" else state.failureReason ?: "当前来源未能完成校准"}。仅重试原来源，不自动切换。",
                            color = colors.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("手动偏移", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${if (offset >= 0) "+" else ""}$offset ms", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace)
                    TextButton(onClick = settings, enabled = ready) { Text("调整") }
                }
                Text("${platform.label()}预设 · 与悬浮首行一致", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Text("手动偏移不代表平台官方时间", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            HorizontalDivider(color = colors.outlineVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (running) "悬浮窗运行中" else "悬浮窗未运行", style = MaterialTheme.typography.titleMedium)
                    Text(if (overlayGranted) "悬浮权限已授予" else "开启前需要悬浮窗权限", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                TextButton(onClick = { details = true }) { Text("详情") }
            }
            if (!notificationsGranted) {
                Text("通知未允许，仍可从本页停止悬浮窗。", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                TextButton(onClick = grantNotifications) { Text("管理通知权限") }
            }
            if (!running && !requested) Text(if (message in listOf("未启动", "已停止")) "开启后获取网络时间；打开首页不会自动校时。" else message,
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (running && message.startsWith("已请求")) Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.primary)
            storageError?.let { Text(it, color = colors.error) }
            Text("首页视觉预览版 · v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("来源与校准") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (running) homeSource(state) else "当前未运行，历史基准不用于显示")
            Text("来源策略：${preferences.sourceChoice.label}")
            if (running) {
                Text("来源标识：${state.sourceId ?: "正在选择"}")
                state.lastSuccess?.let { Text("最后成功基准 UTC：${Instant.ofEpochSecond(0, it.anchor.serverUtcEpochNanos)}") }
                Text(when (state.sourceType) {
                    TimeSourceType.SYSTEM_NETWORK -> "系统提供的网络时间；缓存新鲜度和实测误差未知。重新校准仅重新读取系统结果。"
                    TimeSourceType.HTTP_ESTIMATE -> "HTTP Date 为秒级来源，毫秒为推演显示；实测误差未验证。"
                    else -> "RTT 和校准成功不代表实测误差；精度未验证。"
                })
            }
            Text("所有平台共享会话来源。平台预设只调整显示，不代表购物平台官方时间。")
        } }, confirmButton = { TextButton(onClick = { details = false }) { Text("关闭") } })
}

@Composable
private fun ClockMark() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(32.dp)) {
        drawCircle(color, radius = size.minDimension / 2 - 2.dp.toPx(), style = Stroke(2.dp.toPx()))
        drawLine(color, center, Offset(center.x, size.height * .27f), 2.dp.toPx(), StrokeCap.Round)
        drawLine(color, center, Offset(size.width * .70f, center.y), 2.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun HomeClock(engine: TimeEngine, state: PlatformTimeState, preferences: UserPreferences, running: Boolean, active: Boolean, onReadFailure: () -> Unit) {
    val failureCallback by rememberUpdatedState(onReadFailure)
    val formatter = remember(preferences.zoneId) { MillisecondTimeFormatter(ZoneId.of(preferences.zoneId)) }
    val date = remember(preferences.zoneId) { DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE).withZone(ZoneId.of(preferences.zoneId)) }
    var nanos by remember(engine, state, preferences, running, active) { mutableStateOf<Long?>(null) }
    LaunchedEffect(engine, state, preferences, running, active) {
        if (active && running && state.anchorUsable) while (isActive) {
            withFrameNanos { /* Vsync schedules reads; it is not the clock's time source. */ }
            nanos = try { homeTime(engine, running, state, preferences) } catch (_: RuntimeException) { failureCallback(); null }
            if (nanos == null) break // Do not reuse a failed read until the session publishes a new snapshot.
        }
    }
    val time = nanos?.let(formatter::format) ?: "--:--:--.---"
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("home-clock").clearAndSetSemantics { contentDescription = "当前时间 $time" }) {
        val split = LocalDensity.current.fontScale > 1.3f
        val size = if (maxWidth < 270.dp) 40.sp else 44.sp
        Text(buildAnnotatedString {
            if (split) {
                append(time.take(5)); append("\n")
                withStyle(SpanStyle(fontSize = 28.sp)) { append(time.drop(6)); append(" 秒") }
            } else {
                append(time.take(8))
                withStyle(SpanStyle(fontSize = 22.sp)) { append(time.drop(8)) }
            }
        }, Modifier.fillMaxWidth(), fontSize = size, lineHeight = 52.sp, fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
    }
    Text(nanos?.let { date.format(Instant.ofEpochSecond(0, it)) } ?: "等待本次会话校准",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(if (preferences.zoneId == "Asia/Shanghai") "北京时间 · Asia/Shanghai" else preferences.zoneId,
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
