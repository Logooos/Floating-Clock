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
    var section by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var zonePicker by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    Text("高级设置", style = MaterialTheme.typography.headlineMedium)
    Text("时间来源与显示偏移分开管理", color = MaterialTheme.colorScheme.onSurfaceVariant)
    listOf("时间来源", "时区", "全局手动偏移", "手动偏移预设", "权限与运行规则", "关于与隐私").forEach { title ->
        DesignCard(title) {
            TextButton(onClick = { section = if (section == title) "" else title }) {
                Text(when (title) {
                    "时间来源" -> preferences.sourceChoice.label
                    "时区" -> preferences.zoneId
                    "全局手动偏移" -> "${preferences.globalOffsetMillis} ms"
                    "手动偏移预设" -> preferences.clockRows.joinToString { it.label() }
                    else -> if (section == title) "收起" else "查看"
                })
            }
            if (section == title) when (title) {
                "时间来源" -> SourceControls()
                "时区" -> {
                    TextButton(onClick = { zonePicker = true }) { Text("搜索时区") }
                    TextButton(onClick = { AppStorage.update { it.copy(zoneId = "Asia/Shanghai") } }) { Text("北京时间 Asia/Shanghai") }
                    SettingField("时区", preferences.zoneId) { text ->
                        runCatching { ZoneId.of(text) }.getOrNull()?.let { zone -> AppStorage.update { it.copy(zoneId = zone.id) }; true } ?: false
                    }
                }
                "全局手动偏移" -> {
                    Text("正数让显示提前，负数让显示延后；单位 ms，范围 ±86,400,000。")
                    SettingField("全局偏移（ms）", preferences.globalOffsetMillis.toString()) { text ->
                        offsetValue(text)?.let { value -> AppStorage.update { it.copy(globalOffsetMillis = value) }; true } ?: false
                    }
                }
                "手动偏移预设" -> {
                    ClockRowControls(preferences)
                    PlatformId.entries.forEach { platform ->
                        Text("${platform.label()} · 手动预设", style = MaterialTheme.typography.titleSmall)
                        Text("净偏移 ${preferences.globalOffsetMillis + (preferences.platformOffsetsMillis[platform] ?: 0)} ms（含全局偏移）")
                        SettingField("${platform.label()}偏移（ms）", (preferences.platformOffsetsMillis[platform] ?: 0).toString()) { text ->
                            offsetValue(text)?.let { value -> AppStorage.update { it.copy(platformOffsetsMillis = it.platformOffsetsMillis + (platform to value)) }; true } ?: false
                        }
                    }
                    TextButton(onClick = { reset = true }) { Text("重置所有偏移") }
                    Text("移出显示不会删除已保存的偏移。所有行共用当前会话来源，不是购物平台官方时间。")
                }
                "权限与运行规则" -> Text("仅手动启动；锁屏立即停止校时和悬浮窗，解锁不恢复。通知被拒绝时仍可从首页停止。授权由首页进入系统设置；部分应用会隐藏悬浮窗。")
                else -> Text("Floating Clock ${BuildConfig.VERSION_NAME}\n免费开源 · MIT\n配置与诊断仅保存在本机，不参与自动备份或设备迁移。重新打开应用不会自动启动悬浮窗或网络校时。诊断保留七天，可由你主动导出或清除。毫秒显示与帧率均不是精度证明。")
            }
        }
    }
    if (zonePicker) {
        var query by remember { mutableStateOf("") }
        val zones = remember(query) { ZoneId.getAvailableZoneIds().sorted().filter { it.contains(query.trim(), ignoreCase = true) } }
        AlertDialog(onDismissRequest = { zonePicker = false }, title = { Text("选择时区") },
            text = { Column {
                OutlinedTextField(query, { query = it }, label = { Text("搜索城市或 ZoneId") }, singleLine = true)
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(zones.size) { index -> TextButton(onClick = {
                        AppStorage.update { it.copy(zoneId = zones[index]) }; zonePicker = false
                    }) { Text(zones[index]) } }
                }
                if (zones.isEmpty()) Text("未找到合法时区")
            } }, confirmButton = { TextButton(onClick = { zonePicker = false }) { Text("取消") } })
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("将所有手动偏移归零？") },
        confirmButton = { TextButton(onClick = { AppStorage.update { it.copy(globalOffsetMillis = 0, platformOffsetsMillis = emptyMap()) }; reset = false }) { Text("重置") } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text("取消") } })

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

@Composable
internal fun ClockRowControls(preferences: UserPreferences) {
    var replacement by remember { mutableStateOf<ClockRow?>(null) }
    Text("选择显示行 · 最多三行")
    ClockRow.entries.forEach { row ->
        FilterChip(selected = row in preferences.clockRows, onClick = {
            if (row !in preferences.clockRows && preferences.clockRows.size == 3) replacement = row
            else AppStorage.update { current -> current.copy(clockRows =
                if (row in current.clockRows) (current.clockRows - row).ifEmpty { listOf(ClockRow.PUBLIC) }
                else (current.clockRows + row).distinct().take(3)) }
        }, label = { Text(row.label()) })
    }
    preferences.clockRows.forEachIndexed { index, row ->
        Text("${index + 1}. ${row.label()}")
        Row {
            listOf(-1 to "上移", 1 to "下移").forEach { (direction, label) ->
                TextButton(enabled = index + direction in preferences.clockRows.indices, onClick = {
                    AppStorage.update { current ->
                        val rows = current.clockRows.toMutableList()
                        val from = rows.indexOf(row)
                        if (from >= 0 && from + direction in rows.indices) java.util.Collections.swap(rows, from, from + direction)
                        current.copy(clockRows = rows)
                    }
                }) { Text(label) }
            }
        }
    }
    if (preferences.clockRows.map { it.preset?.let { id -> preferences.platformOffsetsMillis[id] } ?: 0 }.distinct().size < preferences.clockRows.size)
        Text("部分读数相同：这些行的手动偏移一致。", style = MaterialTheme.typography.bodySmall)
    replacement?.let { added ->
        AlertDialog(onDismissRequest = { replacement = null }, title = { Text("最多显示三行，选择一行替换") },
            text = { Column { preferences.clockRows.forEach { old ->
                TextButton(onClick = { AppStorage.update { it.copy(clockRows = it.clockRows.map { row -> if (row == old) added else row }.distinct()) }; replacement = null }) { Text(old.label()) }
            } } }, confirmButton = { TextButton(onClick = { replacement = null }) { Text("取消") } })
    }
}
