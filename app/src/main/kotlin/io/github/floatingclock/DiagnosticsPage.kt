package io.github.floatingclock

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.floatingclock.time.PlatformId
import kotlinx.coroutines.flow.catch
import java.time.Instant

@Composable
internal fun DiagnosticsPage() {
    val repository = AppStorage.diagnostics
    val context = LocalContext.current.applicationContext
    var platform by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf<String?>(null) }
    var offset by remember(platform, source) { mutableIntStateOf(0) }
    val records by remember(platform, source, offset) { repository.dao.observe(platform, source, offset).catch { AppStorage.reportReadFailure(); emit(emptyList()) } }.collectAsState(emptyList())
    val sources by remember { repository.dao.sources().catch { AppStorage.reportReadFailure(); emit(emptyList()) } }.collectAsState(emptyList())
    var detail by remember { mutableStateOf<DiagnosticRecord?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    fun export(uri: android.net.Uri?, format: ExportFormat) {
        if (uri == null) { message = "已取消导出"; return }
        exporting = true
        val selectedPlatform = platform
        val selectedSource = source
        val metadata = ExportMetadata(BuildConfig.VERSION_NAME, AppStorage.preferences.value.zoneId, Build.VERSION.SDK_INT, System.currentTimeMillis())
        AppStorage.work {
            try {
                repository.prune()
                val last = repository.dao.lastId() ?: 0L
                val cutoff = repository.cutoff()
                message = when (exportDiagnostics(format, metadata, { context.contentResolver.openOutputStream(uri, "wt") }) { after ->
                    repository.dao.exportPage(after, last, cutoff, selectedPlatform, selectedSource)
                }) {
                    ExportResult.SAVED -> "导出成功（当前筛选范围，所有分页）"
                    ExportResult.CANCELLED -> "已取消导出"
                    ExportResult.FAILED -> "导出失败：请检查磁盘空间或文档访问权限；目标位置可能留下不完整文件"
                }
                OverlayState.message = message
            } finally { exporting = false }
        }
    }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.JSON.mime)) { export(it, ExportFormat.JSON) }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.CSV.mime)) { export(it, ExportFormat.CSV) }
    LaunchedEffect(Unit) { AppStorage.work { repository.prune() } }
    Text("校时诊断", style = MaterialTheme.typography.headlineSmall)
    Text("当前会话：${OverlayState.timeState.statusLabel()}\n${OverlayState.syncDetails}")
    Text("历史记录只用于诊断，不能恢复为当前时间基准。保留最近 7 天，最多 120,000 条；实测误差未验证。")
    FilterMenu("平台", platform?.let { name -> PlatformId.entries.firstOrNull { it.name == name }?.label() } ?: "全部",
        listOf(null to "全部") + PlatformId.entries.map { it.name to it.label() }) { platform = it }
    FilterMenu("实际来源", source ?: "全部", listOf(null to "全部") + sources.map { it to it }) { source = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !exporting, onClick = { json.launch("floating-clock-diagnostics.json") }) { Text("导出 JSON") }
        Button(enabled = !exporting, onClick = { csv.launch("floating-clock-diagnostics.csv") }) { Text("导出 CSV") }
    }
    Text(if (exporting) "正在写入文档…" else message)
    OutlinedButton(enabled = !exporting, onClick = { clearing = true }) { Text("清除全部日志") }
    if (clearing) AlertDialog(onDismissRequest = { clearing = false }, title = { Text("永久清除本机全部诊断记录？") },
        confirmButton = { TextButton(onClick = { AppStorage.work { repository.dao.clear() }; clearing = false; offset = 0 }) { Text("清除") } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text("取消") } })
    Text("第 ${offset / 200 + 1} 页 · ${records.size} 条")
    Row {
        TextButton(enabled = offset > 0, onClick = { offset = (offset - 200).coerceAtLeast(0) }) { Text("上一页") }
        TextButton(enabled = records.size == 200, onClick = { offset += 200 }) { Text("下一页") }
    }
    records.forEach { record ->
        OutlinedButton(onClick = { detail = record }, modifier = Modifier.fillMaxWidth()) {
            Text("${Instant.ofEpochMilli(record.recordedAtUtcMillis)}\n${PlatformId.entries.firstOrNull { it.name == record.platformId }?.label() ?: record.platformId} · ${record.sourceId}\n${record.status} · ${record.failureReason ?: "实测误差未知"}")
        }
    }
    detail?.let { record ->
        AlertDialog(onDismissRequest = { detail = null }, title = { Text("诊断 #${record.id}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                record.fields().forEach { (key, value) -> Text("$key：${value ?: "未知"}") }
                Text("UTC 时间戳单位 ms；时长、偏移、跳变量单位 ns。记录时间使用本机墙钟；估计值不等于已验证误差。")
            } }, confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } })
    }
}

@Composable
private fun FilterMenu(label: String, selected: String, options: List<Pair<String?, String>>, select: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label：$selected") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { select(value); expanded = false }) }
        }
    }
}
