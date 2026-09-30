package io.github.floatingclock

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.floatingclock.time.PlatformId
import kotlinx.coroutines.flow.catch
import java.time.Instant

@Composable
internal fun DiagnosticsPage() {
    val repository = AppStorage.diagnostics
    val preferences by AppStorage.preferences.collectAsState()
    val dateFormatter = remember(preferences.zoneId) { DateTimeFormatter.ofPattern("MM月dd日 HH:mm:ss.SSS").withZone(ZoneId.of(preferences.zoneId)) }
    fun diagnosticDate(millis: Long) = dateFormatter.format(Instant.ofEpochMilli(millis))
    val context = LocalContext.current.applicationContext
    var platform by rememberSaveable { mutableStateOf<String?>(null) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var offset by rememberSaveable(platform, source) { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var readError by remember(platform, source, offset, retry) { mutableStateOf(false) }
    var exportOptions by rememberSaveable { mutableStateOf(false) }
    var grouped by rememberSaveable { mutableStateOf(true) }
    var rendering by rememberSaveable { mutableStateOf(false) }
    val records by remember(platform, source, offset, retry) { repository.dao.observe(platform, source, offset).catch { readError = true; emit(emptyList()) } }.collectAsState(emptyList())
    val sources by remember { repository.dao.sources().catch { AppStorage.reportReadFailure(); emit(emptyList()) } }.collectAsState(emptyList())
    var detail by remember { mutableStateOf<DiagnosticRecord?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val exporting = DiagnosticExportState.running
    var message by remember { mutableStateOf("") }
    fun export(uri: android.net.Uri?, format: ExportFormat) {
        if (uri == null) { message = "已取消导出"; return }
        DiagnosticExportState.running = true
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
            } finally { DiagnosticExportState.running = false }
        }
    }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.JSON.mime)) { export(it, ExportFormat.JSON) }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.CSV.mime)) { export(it, ExportFormat.CSV) }
    LaunchedEffect(Unit) { AppStorage.work { repository.prune() } }
    Text("校时诊断", style = MaterialTheme.typography.headlineSmall)
    DesignCard("当前会话") {
        Text(homeStatus(OverlayState.running, OverlayState.requested, OverlayState.timeState))
        Text(if (OverlayState.running) homeSource(OverlayState.timeState) else "当前未运行，历史记录不用于显示")
        Text("精度未验证", style = MaterialTheme.typography.labelMedium)
        if (OverlayState.running) Text(OverlayState.timeState.lastSuccessfulCalibrationUtcEpochNanos?.let {
            "最近成功：${diagnosticDate(Math.floorDiv(it, 1_000_000L))}"
        } ?: "本会话尚无成功校准", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { rendering = !rendering }) { Text("渲染详情") }
        if (rendering) Text(if (OverlayState.running) "实际绘制 ${"%.1f".format(OverlayState.fps)} FPS；不代表校时精度或设备已通过 120FPS 验收。" else "当前未绘制")
    }
    records.firstOrNull()?.let { recent -> DesignCard("本页最近记录") {
        Text("${diagnosticDate(recent.recordedAtUtcMillis)} · ${diagnosticStatus(recent.status)}")
        Text(recent.sourceId)
        TextButton(onClick = { detail = recent }) { Text("查看最近记录详情") }
    } }
    Text("历史保留最近 7 天 · 不恢复为当前时间基准", style = MaterialTheme.typography.bodySmall)
    FilterMenu("手动预设", platform?.let { name -> PlatformId.entries.firstOrNull { it.name == name }?.label() } ?: "全部",
        listOf(null to "全部") + PlatformId.entries.map { it.name to it.label() }) { platform = it }
    FilterMenu("实际来源", source ?: "全部", listOf(null to "全部") + sources.map { it to it }) { source = it }
    Text("第 ${offset / 200 + 1} 页 · ${records.size} 条")
    Row {
        TextButton(enabled = offset > 0, onClick = { offset = (offset - 200).coerceAtLeast(0) }) { Text("上一页") }
        TextButton(enabled = records.size == 200, onClick = { offset += 200 }) { Text("下一页") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = grouped, onClick = { grouped = true }, label = { Text("测量分组") })
        FilterChip(selected = !grouped, onClick = { grouped = false }, label = { Text("原始记录") })
    }
    val groups = remember(records, grouped) { if (grouped) measurementGroups(records) else records.map { listOf(it) } }
    Text("本页 ${groups.size} 个显示项 / ${records.size} 条原始记录", style = MaterialTheme.typography.bodySmall)
    if (grouped) Text("仅匹配完整共享样本；不完整或有冲突的记录单列。分组数不是网络请求数。", style = MaterialTheme.typography.bodySmall)
    if (readError) {
        Text("读取失败，未清除历史记录", color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { retry++ }) { Text("重试读取") }
    } else if (records.isEmpty()) Text(if (platform == null && source == null && offset == 0) "尚无诊断记录" else "当前筛选或分页无结果")
    groups.forEach { group ->
        val record = group.first()
        Surface(shape = HomeDesign.control, color = MaterialTheme.colorScheme.surfaceContainer,
            onClick = { detail = record }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${diagnosticDate(record.recordedAtUtcMillis)} · ${diagnosticStatus(record.status)}", style = MaterialTheme.typography.titleSmall)
                Text(record.sourceId, style = MaterialTheme.typography.bodyMedium)
                Text(if (group.size > 1) "匹配测量组 · 关联 ${group.size} 条旧预设记录" else
                    "${PlatformId.entries.firstOrNull { it.name == record.platformId }?.label() ?: record.platformId} · 原始记录", style = MaterialTheme.typography.bodySmall)
                if (record.manualSync || record.sourceRecovered) Text(listOfNotNull(if (record.manualSync) "手动同步" else null,
                    if (record.sourceRecovered) "来源恢复" else null).joinToString(" · "), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    TextButton(onClick = { exportOptions = !exportOptions }) { Text("导出与清除") }
    if (exportOptions) {
    Text("导出最近七天当前筛选的全部原始记录，包含所有分页和共享测量的关联行。")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !exporting, onClick = { json.launch("floating-clock-diagnostics.json") }) { Text("导出 JSON") }
        OutlinedButton(enabled = !exporting, onClick = { csv.launch("floating-clock-diagnostics.csv") }) { Text("导出 CSV") }
    }
    Text(if (exporting) "正在写入文档…" else message)
    TextButton(enabled = !exporting, onClick = { clearing = true }) { Text("清除全部日志") }
    if (clearing) AlertDialog(onDismissRequest = { clearing = false }, title = { Text("永久清除本机全部诊断记录？") }, text = { Text("不影响保存的配置。") },
        confirmButton = { TextButton(onClick = { AppStorage.work { repository.dao.clear() }; clearing = false; offset = 0 }) { Text("清除") } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text("取消") } })
    }
    detail?.let { record ->
        AlertDialog(onDismissRequest = { detail = null }, title = { Text("诊断 #${record.id}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${diagnosticDate(record.recordedAtUtcMillis)} · ${diagnosticStatus(record.status)}")
                Text("实际来源：${record.sourceId}")
                Text("显示时区：${preferences.zoneId}")
                Text("失败原因：${record.failureReason ?: "无"}")
                Text("RTT：${record.roundTripNanos?.let { "$it ns" } ?: if (record.sourceType == "SYSTEM_NETWORK") "系统接口未提供" else "本次未获得"}")
                Text("估计偏移：${record.estimatedOffsetNanos?.let { "$it ns" } ?: "未知"}")
                Text("不确定度：${record.uncertaintyNanos?.let { "$it ns" } ?: "未知"}")
                Text("校准跳变量：${record.adjustmentNanos?.let { "$it ns" } ?: "未知"}")
                Text("实测误差：未验证")
                Text("关联原始行：${groups.firstOrNull { record in it }?.joinToString { it.id.toString() } ?: record.id}")
                Text("原始字段", style = MaterialTheme.typography.titleSmall)
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

internal fun diagnosticStatus(status: String): String = when (status) {
    "SYNCED" -> "校准成功"; "RETRYING" -> "同源重试"; "STALE" -> "校准失效"
    "STOPPED" -> "已停止"; "INITIALIZING" -> "正在校准"; "RESELECT_REQUIRED" -> "需要重新选源"
    else -> status
}

/** Process-only activity state: never stored in DataStore or Room. */
private object DiagnosticExportState { var running by mutableStateOf(false) }
