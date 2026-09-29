package io.github.floatingclock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.floatingclock.time.PlatformId
import java.time.ZoneId

@Composable
internal fun SettingsPage() {
    val preferences by AppStorage.preferences.collectAsState()
    Text("视觉与时间设置", style = MaterialTheme.typography.headlineSmall)
    Text("所有平台统一样式；变更保存后立即应用到当前悬浮窗。")
    VisualStyle.entries.forEach { style ->
        Row {
            RadioButton(selected = preferences.style == style, onClick = { AppStorage.update { it.preset(style) } })
            TextButton(onClick = { AppStorage.update { it.preset(style) } }) { Text(style.label) }
        }
    }
    if (preferences.style == VisualStyle.GLASS) Text("磨砂外观使用半透明渐变；不会读取或模糊其他应用画面。")
    Row { ClockFont.entries.forEach { font ->
        TextButton(onClick = { AppStorage.update { it.copy(font = font) } }, enabled = preferences.font != font) { Text(font.label) }
    } }
    var fontSize by remember(preferences.fontSizeSp) { mutableFloatStateOf(preferences.fontSizeSp) }
    Text("字号：${fontSize.toInt()} sp")
    Slider(value = fontSize, onValueChange = { fontSize = it }, onValueChangeFinished = { AppStorage.update { it.copy(fontSizeSp = fontSize) } }, valueRange = 16f..64f)
    var opacity by remember(preferences.backgroundOpacity) { mutableFloatStateOf(preferences.backgroundOpacity) }
    Text("背景透明度：${(opacity * 100).toInt()}%（纯数字模式始终无背景）")
    Slider(value = opacity, onValueChange = { opacity = it }, onValueChangeFinished = { AppStorage.update { it.copy(backgroundOpacity = opacity) } }, valueRange = 0f..1f)
    SettingField("文字颜色 #RRGGBB", "#%06X".format(preferences.textColorArgb and 0xffffff)) { value ->
        colorValue(value)?.let { color -> AppStorage.update { it.copy(textColorArgb = color) }; true } ?: false
    }
    SettingField("背景颜色 #RRGGBB", "#%06X".format(preferences.backgroundColorArgb and 0xffffff)) { value ->
        colorValue(value)?.let { color -> AppStorage.update { it.copy(backgroundColorArgb = color) }; true } ?: false
    }
    Column(Modifier.fillMaxWidth().background(Color(preferences.backgroundColorArgb).copy(alpha = if (preferences.style == VisualStyle.DIGITS) 0f else opacity)).padding(12.dp)) {
        Text("样式预览 · 非当前时间", color = Color.White, modifier = Modifier.background(Color(0xff191d23)))
        Text("08:00:00.000", color = Color(preferences.textColorArgb), fontSize = fontSize.sp,
            fontFamily = when (preferences.font) { ClockFont.MONOSPACE -> FontFamily.Monospace; ClockFont.SANS -> FontFamily.SansSerif; ClockFont.SERIF -> FontFamily.Serif })
    }
    PlatformControls()
    SettingField("时区", preferences.zoneId) { text ->
        runCatching { ZoneId.of(text) }.getOrNull()?.let { zone -> AppStorage.update { it.copy(zoneId = zone.id) }; true } ?: false
    }
    Text("偏移单位为毫秒；每项范围 ±86,400,000 ms；与来源校准独立。")
    SettingField("全局偏移（ms）", preferences.globalOffsetMillis.toString()) { text ->
        offsetValue(text)?.let { value -> AppStorage.update { it.copy(globalOffsetMillis = value) }; true } ?: false
    }
    PlatformId.entries.forEach { platform ->
        SettingField("${platform.label()}偏移（ms）", (preferences.platformOffsetsMillis[platform] ?: 0).toString()) { text ->
            offsetValue(text)?.let { value -> AppStorage.update { it.copy(platformOffsetsMillis = it.platformOffsetsMillis + (platform to value)) }; true } ?: false
        }
    }
    var reset by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { reset = true }) { Text("重置所有偏移") }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("将所有手动偏移归零？") },
        confirmButton = { TextButton(onClick = { AppStorage.update { it.copy(globalOffsetMillis = 0, platformOffsetsMillis = emptyMap()) }; reset = false }) { Text("重置") } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text("取消") } })
    SourceControls()
    Text("配置与诊断仅保存在本机，不参与自动备份或设备迁移。重新打开应用不会自动启动悬浮窗或网络校时。")
}

@Composable
private fun SettingField(label: String, saved: String, apply: (String) -> Boolean) {
    var text by remember(saved) { mutableStateOf(saved) }
    var invalid by remember { mutableStateOf(false) }
    OutlinedTextField(value = text, onValueChange = { text = it; invalid = false }, singleLine = true,
        label = { Text(label) }, isError = invalid, modifier = Modifier.fillMaxWidth())
    TextButton(onClick = { invalid = !apply(text.trim()) }, enabled = text != saved) { Text(if (invalid) "输入无效，请修正" else "应用$label") }
}

internal fun colorValue(value: String): Int? = if (value.matches(Regex("#[0-9a-fA-F]{6}"))) value.drop(1).toInt(16) or 0xff000000.toInt() else null
internal fun offsetValue(value: String): Long? = value.toLongOrNull()?.takeIf { it in -UserPreferences.MAX_OFFSET_MILLIS..UserPreferences.MAX_OFFSET_MILLIS }
