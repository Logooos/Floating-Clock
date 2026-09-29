package io.github.floatingclock

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStream

internal enum class ExportFormat(val mime: String, val extension: String) { JSON("application/json", "json"), CSV("text/csv", "csv") }
internal enum class ExportResult { SAVED, CANCELLED, FAILED }
internal data class ExportMetadata(val appVersion: String, val zoneId: String, val androidApi: Int, val exportedAtUtcMillis: Long)

internal fun DiagnosticRecord.fields(): LinkedHashMap<String, Any?> = linkedMapOf(
    "id" to id, "recordedAtUtcMillis" to recordedAtUtcMillis, "platformId" to platformId,
    "sourceId" to sourceId, "sourceType" to sourceType, "status" to status, "failureReason" to failureReason,
    "roundTripNanos" to roundTripNanos, "estimatedOffsetNanos" to estimatedOffsetNanos,
    "uncertaintyNanos" to uncertaintyNanos, "adjustmentNanos" to adjustmentNanos,
    "lastSuccessUtcMillis" to lastSuccessUtcMillis, "measuredErrorNanos" to measuredErrorNanos,
    "manualSync" to manualSync, "sourceRecovered" to sourceRecovered,
)

/** Text cells are quoted and formula-neutralized. Numeric negative offsets remain numeric. */
internal fun csvCell(value: Any?): String {
    if (value == null) return ""
    if (value is Number) return value.toString()
    val text = value.toString()
    val safe = if (text.trimStart().firstOrNull() in listOf('=', '+', '-', '@') || text.firstOrNull() in listOf('\t', '\r', '\n')) "'$text" else text
    return "\"${safe.replace("\"", "\"\"")}\""
}

/** Export runs off the UI thread, pages bounded data, and closes the selected document on all paths. */
internal suspend fun exportDiagnostics(
    format: ExportFormat,
    metadata: ExportMetadata,
    open: (() -> OutputStream?)?,
    page: suspend (afterId: Long) -> List<DiagnosticRecord>,
): ExportResult {
    if (open == null) return ExportResult.CANCELLED
    return withContext(Dispatchers.IO) {
        try {
            val stream = open() ?: return@withContext ExportResult.FAILED
            // Android's writer may throw while flushing before closing its underlying stream.
            stream.use { raw -> raw.bufferedWriter(Charsets.UTF_8).use { writer ->
                val meta = linkedMapOf<String, Any?>("schemaVersion" to 1, "appVersion" to metadata.appVersion,
                    "zoneId" to metadata.zoneId, "androidApi" to metadata.androidApi,
                    "exportedAtUtcMillis" to metadata.exportedAtUtcMillis, "accuracyVerified" to false,
                    "units" to "UTC epoch timestamps=ms; durations and offsets=ns",
                    "unknown" to "JSON null / CSV empty; success is not verified accuracy",
                    "fields" to "id=local record; recordedAtUtcMillis=local wall clock; platformId=shopping entry, not provenance; sourceId=redacted origin; sourceType=actual time source kind; status=calibration result; failureReason=controlled category; roundTripNanos=estimated RTT; estimatedOffsetNanos=difference from request wall clock; uncertaintyNanos=estimate with evidence or unknown; adjustmentNanos=anchor correction; lastSuccessUtcMillis=historical source UTC; measuredErrorNanos=independent reference error, currently unknown; manualSync=user-requested attempt; sourceRecovered=same-source recovery")
                val columns = DiagnosticRecord(recordedAtUtcMillis = 0, platformId = "", sourceId = "", sourceType = null,
                    status = "", failureReason = null, roundTripNanos = null, estimatedOffsetNanos = null,
                    uncertaintyNanos = null, adjustmentNanos = null, lastSuccessUtcMillis = null).fields().keys
                if (format == ExportFormat.JSON) {
                    writer.write(JSONObject(meta).toString().dropLast(1)); writer.write(",\"records\":[")
                } else {
                    writer.write((listOf("rowType") + meta.keys + columns).joinToString(",", transform = ::csvCell) + "\r\n")
                    writer.write((listOf("metadata") + meta.values + List(columns.size) { null }).joinToString(",", transform = ::csvCell) + "\r\n")
                }
                var after = 0L
                var first = true
                while (true) {
                    val records = page(after)
                    if (records.isEmpty()) break
                    for (record in records) {
                        require(record.id > after) { "Export pages must be ordered by increasing id" }
                        val fields = record.fields()
                        if (format == ExportFormat.JSON) {
                            if (!first) writer.write(",")
                            val json = JSONObject()
                            fields.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
                            writer.write(json.toString())
                        } else {
                            writer.write((listOf("record") + List(meta.size) { null } + fields.values).joinToString(",", transform = ::csvCell) + "\r\n")
                        }
                        first = false; after = record.id
                    }
                }
                if (format == ExportFormat.JSON) writer.write("]}")
            } }
            ExportResult.SAVED
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            ExportResult.FAILED
        }
    }
}
