package io.github.floatingclock

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.geometry.Size
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
    secondaryContainer = Color(0xffe6eeff), onSecondaryContainer = Color(0xff17478e),
    primaryContainer = Color(0xffe6eeff), onPrimaryContainer = Color(0xff17478e),
    background = Color(0xffeef2f6), surface = Color(0xffeef2f6),
    surfaceContainer = Color.White, onSurface = Color(0xff17212b),
    onSurfaceVariant = Color(0xff475569), outline = Color(0xff64748b),
    outlineVariant = Color(0xffdce3ec), error = Color(0xffb3261e),
    errorContainer = Color(0xfff9dedc), onErrorContainer = Color(0xff641d18),
)
private val DarkHome = darkColorScheme(
    primary = Color(0xffa8c7fa), onPrimary = Color(0xff062e6f),
    secondaryContainer = Color(0xff223957), onSecondaryContainer = Color(0xffcde0ff),
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
    val control = RoundedCornerShape(12.dp)
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
        preferences.clockRows.first().preset?.let { preferences.platformOffsetsMillis[it] } ?: 0)
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
    appearance: () -> Unit = settings,
) {
    val colors = MaterialTheme.colorScheme
    var readFailed by remember(state, running) { mutableStateOf(false) }
    val status = if (readFailed) "无可信时间" else homeStatus(running, requested, state)
    val warning = running && (readFailed || state.status in setOf(CalibrationStatus.STALE, CalibrationStatus.RETRYING, CalibrationStatus.RESELECT_REQUIRED))
    var details by remember { mutableStateOf(false) }
    val row = preferences.clockRows.first()
    val platform = row.preset
    val offset = preferences.globalOffsetMillis + (preferences.platformOffsetsMillis[platform] ?: 0)
    val demo = preferences.sourceChoice == SourceChoice.DEMO || state.sourceId?.startsWith("demo:") == true
    Scaffold(containerColor = colors.background,
        bottomBar = {
            Surface(color = colors.background) {
                Column {
                    Column(Modifier.fillMaxWidth().padding(horizontal = HomeDesign.gutter).padding(top = 8.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(HomeDesign.gap)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(HomeDesign.gap)) {
                            OutlinedButton(onClick = sync, enabled = running && !state.isCalibrating,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = HomeDesign.control) { Text("重新校准") }
                            Button(onClick = { when { running || requested -> stop(); !overlayGranted -> grantOverlay(); else -> start() } },
                                enabled = ready || running || requested,
                                modifier = Modifier.weight(1.4f).heightIn(min = 48.dp).testTag("home-primary"), shape = HomeDesign.control) {
                                Text(when { running || requested -> "停止悬浮窗"; !overlayGranted -> "授予悬浮权限"; else -> "开启悬浮窗" })
                            }
                        }
                        Text("锁屏后停止 · 解锁不自动恢复", Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    ClockNavigation(0, ready) { when (it) { 1 -> appearance(); 2 -> diagnostics() } }
                }
            }
        }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
            .padding(horizontal = HomeDesign.gutter).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Floating Clock", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                TextButton(onClick = settings, enabled = ready) { Text("设置") }
            }
            Surface(shape = HomeDesign.card, color = colors.surfaceContainer, border = BorderStroke(1.dp, colors.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(HomeDesign.gap)) {
                    if (platform != null) Text(row.label(), style = MaterialTheme.typography.labelMedium)
                    HomeClock(engine, state, preferences, running, active) { readFailed = true }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = colors.outlineVariant)
                    Text(if (running) homeSource(state) else "来源待校准", style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center, color = colors.onSurfaceVariant)
                    Text("精度未验证", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    if (warning) {
                        Surface(modifier = Modifier.fillMaxWidth().testTag("home-warning"), shape = HomeDesign.control, color = colors.errorContainer) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("$status · ${if (state.anchorUsable && !readFailed) "使用上次基准" else "无可信时间"}",
                                    color = colors.onErrorContainer, style = MaterialTheme.typography.labelLarge)
                                Text(if (readFailed) "推演不可用，等待新校准" else "仅重试原来源，不自动切换", color = colors.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else if (status != "校准正常") {
                        Surface(shape = RoundedCornerShape(8.dp), color = colors.primaryContainer) {
                            Text(status, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
                                color = colors.onPrimaryContainer)
                        }
                    }
                    if (demo) Text("演示数据 · 非真实网络校时", color = colors.primary, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (offset == 0L) {
                TextButton(onClick = settings, enabled = ready, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("home-offset-zero")) {
                    Text("手动偏移", Modifier.weight(1f), textAlign = TextAlign.Start, color = colors.onSurfaceVariant)
                    Text("0 ms  ›", fontFamily = FontFamily.Monospace)
                }
            } else {
                Column(Modifier.testTag("home-offset-active"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("手动偏移", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${if (offset >= 0) "+" else ""}$offset ms", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                            fontFamily = FontFamily.Monospace, color = colors.primary)
                        TextButton(onClick = settings, enabled = ready) { Text("调整") }
                    }
                    Text("${row.label()} · 与悬浮首行一致", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Text("手动偏移不代表平台官方时间", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            if (!preferences.presetMigrationAcknowledged) {
                Surface(shape = HomeDesign.control, color = colors.primaryContainer) {
                    Column(Modifier.padding(12.dp)) {
                        Text("平台入口现在称为手动偏移预设，原设置已保留；未接入平台官方时间。")
                        TextButton(onClick = { AppStorage.update { it.copy(presetMigrationAcknowledged = true) }; settings() }) { Text("查看我的预设") }
                        TextButton(onClick = { AppStorage.update { it.usePublicClock() } }) { Text("改用公共单行（保留偏移）") }
                        TextButton(onClick = { AppStorage.update { it.copy(presetMigrationAcknowledged = true) } }) { Text("知道了") }
                    }
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (overlayGranted) "悬浮权限已授予" else "开启前需要悬浮窗权限", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                TextButton(onClick = { details = true }) { Text("来源详情") }
            }
            if (!notificationsGranted) {
                Text("通知未允许，仍可从本页停止悬浮窗。", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                TextButton(onClick = grantNotifications) { Text("管理通知权限") }
            }
            if (!running && !requested && message !in listOf("未启动", "已停止")) Text(message,
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (running && message.startsWith("已请求")) Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.primary)
            storageError?.let { Text(it, color = colors.error) }
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("来源与校准") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (running) homeSource(state) else "当前未运行，历史基准不用于显示")
            Text("来源策略：${preferences.sourceChoice.label}")
            if (running) {
                Text("来源标识：${state.sourceId ?: "正在选择"}")
                state.failureReason?.let { Text(it) }
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
internal fun HomeNavIcon(index: Int) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.7.dp.toPx()
        when (index) {
            0 -> {
                drawCircle(color, radius = size.minDimension / 2 - stroke, style = Stroke(stroke))
                drawLine(color, center, Offset(center.x, size.height * .25f), stroke, StrokeCap.Round)
                drawLine(color, center, Offset(size.width * .72f, center.y), stroke, StrokeCap.Round)
            }
            1 -> for (x in listOf(.12f, .58f)) for (y in listOf(.12f, .58f))
                drawRect(color, Offset(size.width * x, size.height * y), Size(size.width * .3f, size.height * .3f), style = Stroke(stroke))
            else -> for ((x, top) in listOf(.2f to .55f, .5f to .2f, .8f to .38f))
                drawLine(color, Offset(size.width * x, size.height * top), Offset(size.width * x, size.height * .85f), stroke, StrokeCap.Round)
        }
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
    nanos?.let { Text(date.format(Instant.ofEpochSecond(0, it)),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Text(if (preferences.zoneId == "Asia/Shanghai") "北京时间 · UTC+08:00" else preferences.zoneId,
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun ClockNavigation(selected: Int, ready: Boolean = true, navigate: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    NavigationBar(containerColor = colors.surfaceContainer, tonalElevation = 0.dp) {
        listOf("时钟", "外观", "诊断").forEachIndexed { index, label ->
            NavigationBarItem(selected = selected == index, enabled = index == 0 || ready,
                onClick = { navigate(index) }, icon = { HomeNavIcon(index) }, label = { Text(label) },
                modifier = Modifier.testTag("home-nav-$index"),
                colors = NavigationBarItemDefaults.colors(indicatorColor = colors.primaryContainer,
                    selectedIconColor = colors.primary, selectedTextColor = colors.primary,
                    unselectedIconColor = colors.onSurfaceVariant, unselectedTextColor = colors.onSurfaceVariant))
        }
    }
}

internal fun shortSource(state: PlatformTimeState): String = when {
    state.sourceId?.startsWith("demo:") == true -> "演示"
    state.sourceType == TimeSourceType.SYSTEM_NETWORK -> "系统网络"
    state.sourceType == TimeSourceType.HTTP_ESTIMATE -> "HTTP估算"
    state.sourceId?.contains("cloudflare") == true -> "NTP·Cloudflare"
    state.sourceId?.contains("google") == true -> "NTP·Google"
    state.sourceType == TimeSourceType.NTP -> "公共NTP"
    else -> "来源待校准"
}
