package io.github.floatingclock

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.floatingclock.storage.UserSettings
import io.github.floatingclock.time.*
import kotlinx.coroutines.launch
import java.time.ZoneId

internal val PreferencesSaver = Saver<UserPreferences, ByteArray>(
    save = { it.toProto().toByteArray() }, restore = { UserPreferences.fromProto(UserSettings.parseFrom(it)) })

/** Merge only the edited appearance; a concurrent source/offset/row change must survive. */
internal fun UserPreferences.withAppearance(draft: UserPreferences) = copy(
    overlay = overlay.copy(mode = draft.overlay.mode), style = draft.style, font = draft.font,
    fontSizeSp = draft.fontSizeSp, textColorArgb = draft.textColorArgb,
    backgroundColorArgb = draft.backgroundColorArgb, backgroundOpacity = draft.backgroundOpacity,
    positionXRatio = draft.positionXRatio, positionYRatio = draft.positionYRatio)

@Composable
internal fun AppearancePage(engine: TimeEngine, active: Boolean) {
    val saved by AppStorage.preferences.collectAsState()
    var draft by rememberSaveable(stateSaver = PreferencesSaver) { mutableStateOf(saved) }
    var message by rememberSaveable { mutableStateOf("") }
    var applying by remember { mutableStateOf(false) }
    var textValid by remember { mutableStateOf(true) }
    var sizeValid by remember { mutableStateOf(true) }
    var backgroundValid by remember { mutableStateOf(true) }
    var editorRevision by rememberSaveable { mutableIntStateOf(0) }
    var reset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Text("外观", style = MaterialTheme.typography.headlineMedium)
    Text("先预览，再一次应用", color = MaterialTheme.colorScheme.onSurfaceVariant)
    AppearancePreview(draft, engine, active)
    DesignCard("皮肤") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { VisualStyle.entries.forEach { style ->
            FilterChip(selected = draft.style == style, onClick = { draft = draft.preset(style); editorRevision++ }, label = { Text(style.label) })
        }
        }
        if (draft.style == VisualStyle.GLASS) Text("半透明渐变外观；不会读取或模糊其他应用。", style = MaterialTheme.typography.bodySmall)
    }
    DesignCard("信息密度") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { DisplayMode.entries.forEach { mode ->
            FilterChip(selected = draft.overlay.mode == mode, onClick = { draft = draft.copy(overlay = draft.overlay.copy(mode = mode)) },
                label = { Text(when (mode) { DisplayMode.FULL -> "完整"; DisplayMode.COMPACT -> "紧凑"; DisplayMode.MINIMAL -> "极简" }) })
        }
        }
        Text("每种密度均保留实际来源和未验证／异常状态。", style = MaterialTheme.typography.bodySmall)
    }
    DesignCard("字体与字号") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ClockFont.entries.forEach { font ->
            FilterChip(selected = draft.font == font, onClick = { draft = draft.copy(font = font) }, label = { Text(font.label) })
        }
        }
        var sizeText by rememberSaveable(draft.fontSizeSp, editorRevision) { mutableStateOf(draft.fontSizeSp.toInt().toString()) }
        LaunchedEffect(sizeText) { sizeValid = sizeText.toIntOrNull()?.let { it in 16..64 } == true }
        OutlinedTextField(sizeText, { text ->
            sizeText = text
            val number = text.toIntOrNull()?.takeIf { it in 16..64 }
            sizeValid = number != null
            if (number != null) draft = draft.copy(fontSizeSp = number.toFloat())
        }, label = { Text("字号 16–64 sp") }, singleLine = true, isError = !sizeValid,
            supportingText = { if (!sizeValid) Text("请输入 16–64 的整数") }, modifier = Modifier.fillMaxWidth())
        Slider(draft.fontSizeSp, { draft = draft.copy(fontSizeSp = it) }, valueRange = 16f..64f, steps = 47)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { draft = draft.copy(fontSizeSp = (draft.fontSizeSp - 1).coerceAtLeast(16f)) }) { Text("减小 1 sp") }
            OutlinedButton(onClick = { draft = draft.copy(fontSizeSp = (draft.fontSizeSp + 1).coerceAtMost(64f)) }) { Text("增大 1 sp") }
        }
    }
    DesignCard("颜色与背景") {
        key(editorRevision) {
        DraftColor("文字颜色", draft.textColorArgb, { textValid = it }) { draft = draft.copy(textColorArgb = it) }
        DraftColor("背景颜色", draft.backgroundColorArgb, { backgroundValid = it }) { draft = draft.copy(backgroundColorArgb = it) }
        }
        Text("背景不透明度 ${(draft.backgroundOpacity * 100).toInt()}%")
        Slider(draft.backgroundOpacity, { draft = draft.copy(backgroundOpacity = it) }, valueRange = 0f..1f,
            enabled = draft.style != VisualStyle.DIGITS)
        if (draft.style == VisualStyle.DIGITS) Text("纯数字皮肤不绘制背景；保留已保存的颜色与不透明度。")
        val contrast = (maxOf(Color(draft.textColorArgb).luminance(), Color(draft.backgroundColorArgb).luminance()) + .05f) /
            (minOf(Color(draft.textColorArgb).luminance(), Color(draft.backgroundColorArgb).luminance()) + .05f)
        if (contrast < 4.5f || draft.backgroundOpacity < .5f) Text("文字可能与底层画面对比不足，请检查预览；来源与异常底板保持可读。", color = MaterialTheme.colorScheme.error)
    }
    DesignCard("位置") {
        Text("水平 ${(draft.positionXRatio * 100).toInt()}%")
        Slider(draft.positionXRatio, { draft = draft.copy(positionXRatio = it) }, valueRange = 0f..1f)
        Text("垂直 ${(draft.positionYRatio * 100).toInt()}%")
        Slider(draft.positionYRatio, { draft = draft.copy(positionYRatio = it) }, valueRange = 0f..1f)
        Text("也可在悬浮窗上长按拖动。", style = MaterialTheme.typography.bodySmall)
    }
    Button(enabled = !applying && textValid && backgroundValid && sizeValid, onClick = {
        applying = true
        scope.launch {
            try { AppStorage.settings.update { it.withAppearance(draft) }; message = "外观已应用" }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { message = "保存失败，草稿已保留，请重试" }
            finally { applying = false }
        }
    }, modifier = Modifier.fillMaxWidth(), shape = HomeDesign.control) { Text(if (applying) "正在保存…" else "应用外观") }
    Row {
        TextButton(enabled = !applying, onClick = { draft = saved; editorRevision++; sizeValid = true; message = "已恢复保存的外观" }) { Text("放弃草稿") }
        TextButton(enabled = !applying, onClick = { reset = true }) { Text("重置外观") }
    }
    if (message.isNotEmpty()) Text(message)
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("将外观草稿恢复默认？") },
        text = { Text("仅重置皮肤、字体、颜色、密度与位置；点击应用后生效。") },
        confirmButton = { TextButton(onClick = { draft = draft.withAppearance(UserPreferences()); editorRevision++; reset = false }) { Text("重置草稿") } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text("取消") } })
}

@Composable
internal fun DesignCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = HomeDesign.card, color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DraftColor(label: String, value: Int, valid: (Boolean) -> Unit, change: (Int) -> Unit) {
    var text by rememberSaveable(value) { mutableStateOf("#%06X".format(value and 0xffffff)) }
    LaunchedEffect(text) { valid(colorValue(text) != null) }
    Text(label)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0xffffffff.toInt(), 0xff17202a.toInt(), 0xff191d23.toInt(), 0xffa8c7fa.toInt(), 0xffffcd64.toInt()).forEach { color ->
            OutlinedButton(onClick = { change(color) }, modifier = Modifier.semantics { contentDescription = "$label #%06X".format(color and 0xffffff) }, contentPadding = PaddingValues(8.dp)) {
                Box(Modifier.size(20.dp).background(Color(color)))
                Text(if (color == value) " ✓" else "", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    OutlinedTextField(text, { text = it; colorValue(it)?.let(change) }, label = { Text("$label #RRGGBB") },
        isError = colorValue(text) == null, supportingText = { if (colorValue(text) == null) Text("请输入六位十六进制颜色") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun AppearancePreview(draft: UserPreferences, engine: TimeEngine, active: Boolean) {
    val state = OverlayState.timeState
    var time by remember { mutableStateOf("08:00:00.000") }
    var example by rememberSaveable { mutableStateOf("正常") }
    val formatter = remember(draft.zoneId) { MillisecondTimeFormatter(ZoneId.of(draft.zoneId)) }
    LaunchedEffect(draft, state, active, OverlayState.running) {
        if (active && OverlayState.running) while (true) {
            withFrameNanos { }
            time = runCatching { homeTime(engine, true, state, draft)?.let(formatter::format) }.getOrNull() ?: "--:--:--.---"
        } else time = "08:00:00.000"
    }
    DesignCard("实时外观预览") {
        Text(if (OverlayState.running) "当前会话首行读数 · 外观草稿" else "首行样式示例 · 非当前时间", style = MaterialTheme.typography.bodySmall)
        Row { listOf("正常", "重试", "失效").forEach {
            TextButton(enabled = !OverlayState.running, onClick = { example = it }) { Text(if (example == it) "✓ $it" else it) }
        } }
        Column(Modifier.fillMaxWidth().background(Color(draft.backgroundColorArgb).copy(
            alpha = if (draft.style == VisualStyle.DIGITS) 0f else draft.backgroundOpacity)).padding(12.dp)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = HomeDesign.control) {
                Text("${if (OverlayState.running) homeSource(state) else "示例来源"} · ${if (OverlayState.running) state.statusLabel() else if (example == "正常") "未验证" else example}",
                    Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
            }
            Text(draft.clockRows.first().label(), color = Color(draft.textColorArgb), style = MaterialTheme.typography.labelMedium)
            Text(time, color = Color(draft.textColorArgb), fontSize = draft.fontSizeSp.sp,
                fontFamily = when (draft.font) { ClockFont.MONOSPACE -> FontFamily.Monospace; ClockFont.SANS -> FontFamily.SansSerif; ClockFont.SERIF -> FontFamily.Serif })
            if (draft.overlay.mode == DisplayMode.FULL) Text("手动偏移不代表官方时间", color = Color(draft.textColorArgb), style = MaterialTheme.typography.bodySmall)
        }
    }
}
