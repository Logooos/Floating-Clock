package io.github.floatingclock

import androidx.room.*
import io.github.floatingclock.time.*
import kotlinx.coroutines.flow.Flow
import java.net.URI

/** Historical UTC measurements only. No boot-relative anchor can be reconstructed from this table. */
@Entity(tableName = "diagnostics", indices = [Index("recordedAtUtcMillis"), Index(value = ["platformId", "sourceId"])])
internal data class DiagnosticRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordedAtUtcMillis: Long,
    val platformId: String,
    val sourceId: String,
    val sourceType: String?,
    val status: String,
    val failureReason: String?,
    val roundTripNanos: Long?,
    val estimatedOffsetNanos: Long?,
    val uncertaintyNanos: Long?,
    val adjustmentNanos: Long?,
    val lastSuccessUtcMillis: Long?,
    val measuredErrorNanos: Long? = null,
    @ColumnInfo(defaultValue = "0") val manualSync: Boolean = false,
    @ColumnInfo(defaultValue = "0") val sourceRecovered: Boolean = false,
)

@Dao
internal interface DiagnosticDao {
    @Insert suspend fun insert(records: List<DiagnosticRecord>)
    @Query("DELETE FROM diagnostics WHERE recordedAtUtcMillis < :cutoff") suspend fun expire(cutoff: Long)
    @Query("DELETE FROM diagnostics WHERE id NOT IN (SELECT id FROM diagnostics ORDER BY id DESC LIMIT :maximum)")
    suspend fun trim(maximum: Int)
    @Query("DELETE FROM diagnostics") suspend fun clear()
    @Query("SELECT * FROM diagnostics WHERE (:platform IS NULL OR platformId = :platform) AND (:source IS NULL OR sourceId = :source) ORDER BY id DESC LIMIT 200 OFFSET :offset")
    fun observe(platform: String?, source: String?, offset: Int): Flow<List<DiagnosticRecord>>
    @Query("SELECT DISTINCT sourceId FROM diagnostics ORDER BY sourceId") fun sources(): Flow<List<String>>
    @Query("SELECT MAX(id) FROM diagnostics") suspend fun lastId(): Long?
    @Query("SELECT * FROM diagnostics WHERE id > :after AND id <= :last AND recordedAtUtcMillis >= :cutoff AND (:platform IS NULL OR platformId = :platform) AND (:source IS NULL OR sourceId = :source) ORDER BY id LIMIT 500")
    suspend fun exportPage(after: Long, last: Long, cutoff: Long, platform: String?, source: String?): List<DiagnosticRecord>
}

@Database(entities = [DiagnosticRecord::class], version = 2, exportSchema = true)
internal abstract class DiagnosticDatabase : RoomDatabase() {
    abstract fun records(): DiagnosticDao
    companion object {
        // Schema 1 was the v0.5 development baseline, not a previous public release.
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE diagnostics ADD COLUMN manualSync INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE diagnostics ADD COLUMN sourceRecovered INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}

internal class DiagnosticRepository(
    val database: DiagnosticDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val maximumRecords: Int = 120_000,
) {
    val dao = database.records()
    init { require(maximumRecords > 0) }
    suspend fun prune() = database.withTransaction {
        dao.expire(cutoff()); dao.trim(maximumRecords)
    }
    fun cutoff(): Long = nowMillis() - RETENTION_MILLIS
    suspend fun append(records: List<DiagnosticRecord>) = database.withTransaction {
        dao.insert(records); dao.expire(cutoff()); dao.trim(maximumRecords)
    }
    companion object { const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000 }
}

/** Persist a controlled failure category, never exception text, cookies or request URLs. */
internal fun diagnosticFailure(reason: String?): String? = reason?.let {
    when {
        "超时" in it || "timeout" in it.lowercase() -> "TIMEOUT"
        "限频" in it -> "RATE_LIMITED"
        "跳变" in it -> "DISCONTINUITY"
        "延迟" in it -> "HIGH_DELAY"
        "不可用" in it -> "UNAVAILABLE"
        else -> "SOURCE_FAILURE"
    }
}

internal fun diagnosticSource(id: String?, type: TimeSourceType?): String {
    if (id?.startsWith("demo") == true) return "demo"
    if (type == TimeSourceType.HTTP_ESTIMATE) return runCatching {
        val uri = URI(id?.removePrefix("http-date:"))
        require(uri.scheme == "https" && uri.host != null)
        "https://${uri.host}${if (uri.port == -1) "" else ":${uri.port}"}"
    }.getOrDefault("HTTP_ESTIMATED")
    return when (type) {
        TimeSourceType.NTP -> if (id in setOf("ntp:time.cloudflare.com:123", "ntp:time.google.com:123")) id!! else "NTP"
        TimeSourceType.SYSTEM_NETWORK -> "Android system network time"
        else -> "UNKNOWN"
    }
}

internal fun diagnosticRecords(state: PlatformTimeState, adjustment: Long?, nowMillis: Long, manual: Boolean = false, recovered: Boolean = false): List<DiagnosticRecord> {
    val success = state.lastSuccess.takeIf { state.status == CalibrationStatus.SYNCED }
    return PlatformId.entries.map { platform -> DiagnosticRecord(
        recordedAtUtcMillis = nowMillis, platformId = platform.name,
        sourceId = diagnosticSource(state.sourceId, state.sourceType), sourceType = state.sourceType?.name,
        status = state.status.name, failureReason = diagnosticFailure(state.failureReason),
        roundTripNanos = success?.roundTripNanos, estimatedOffsetNanos = success?.estimatedOffsetNanos,
        uncertaintyNanos = success?.estimatedUncertaintyNanos, adjustmentNanos = adjustment,
        lastSuccessUtcMillis = state.lastSuccess?.anchor?.serverUtcEpochNanos?.let { Math.floorDiv(it, 1_000_000L) },
        manualSync = manual, sourceRecovered = recovered,
    ) }
}
