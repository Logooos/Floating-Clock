package io.github.floatingclock

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

@RunWith(AndroidJUnit4::class)
class StorageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @get:Rule val migration = MigrationTestHelper(instrumentation, DiagnosticDatabase::class.java)
    private val now = 1_000_000_000L
    private fun record(at: Long = now, platform: String = "JD", source: String = "NTP", id: Long = 0) = DiagnosticRecord(
        id, at, platform, source, "NTP", "SYNCED", null, 123, -456, null, null, at, null)
    private fun database() = Room.inMemoryDatabaseBuilder(context, DiagnosticDatabase::class.java).build()

    @Test fun migrationPreservesOldRowsAndUnknownMetrics() {
        val name = "diagnostic-migration-test"
        migration.createDatabase(name, 1).apply {
            execSQL("INSERT INTO diagnostics (recordedAtUtcMillis, platformId, sourceId, sourceType, status, roundTripNanos) VALUES (100, 'JD', 'NTP', 'NTP', 'SYNCED', 0)")
            close()
        }
        migration.runMigrationsAndValidate(name, 2, true, DiagnosticDatabase.MIGRATION_1_2).apply {
            query("SELECT roundTripNanos, uncertaintyNanos, measuredErrorNanos, manualSync, sourceRecovered FROM diagnostics").use {
                assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)); assertTrue(it.isNull(1)); assertTrue(it.isNull(2))
                assertEquals(0, it.getInt(3)); assertEquals(0, it.getInt(4))
            }
            close()
        }
    }

    @Test fun sevenDayBoundaryAndGrowthLimitAreEnforced() = runBlocking {
        val db = database()
        try {
            val repository = DiagnosticRepository(db, { now }, maximumRecords = 3)
            val cutoff = now - DiagnosticRepository.RETENTION_MILLIS
            repository.append(listOf(record(cutoff - 1), record(cutoff), record(now)))
            assertEquals(listOf(cutoff, now), repository.dao.exportPage(0, Long.MAX_VALUE, 0, null, null).map { it.recordedAtUtcMillis })
            repository.append(List(4) { record(now + it) })
            assertEquals(3, repository.dao.observe(null, null, 0).first().size)
        } finally { db.close() }
    }

    @Test fun filteringPagingClearAndUnknownMetricsRemainDistinct() = runBlocking {
        val db = database()
        try {
            val repository = DiagnosticRepository(db, { now })
            repository.append(listOf(record(), record(platform = "PDD"), record(source = "SYSTEM").copy(roundTripNanos = 0)))
            val selected = repository.dao.observe("JD", "SYSTEM", 0).first().single()
            assertEquals(0L, selected.roundTripNanos); assertNull(selected.uncertaintyNanos); assertNull(selected.measuredErrorNanos)
            assertEquals(2, repository.dao.sources().first().size)
            assertEquals(2, repository.dao.exportPage(0, Long.MAX_VALUE, 0, "JD", null).size)
            repository.dao.clear()
            assertTrue(repository.dao.observe(null, null, 0).first().isEmpty())
        } finally { db.close() }
    }

    @Test fun databaseReopenPreservesHistoryAndPrunesOnNextAccess() = runBlocking {
        val name = "history-reopen-test.db"
        context.deleteDatabase(name)
        val first = Room.databaseBuilder(context, DiagnosticDatabase::class.java, name).build()
        DiagnosticRepository(first, { now }).append(listOf(record()))
        first.close()
        val second = Room.databaseBuilder(context, DiagnosticDatabase::class.java, name).build()
        try {
            val repository = DiagnosticRepository(second, { now + DiagnosticRepository.RETENTION_MILLIS + 1 })
            assertEquals(1, repository.dao.observe(null, null, 0).first().size)
            repository.prune()
            assertTrue(repository.dao.observe(null, null, 0).first().isEmpty())
        } finally { second.close(); context.deleteDatabase(name) }
    }

    @Test fun jsonExportPreservesSpecialCharactersAndUnknownValues() = runBlocking {
        val output = ByteArrayOutputStream()
        val sample = record(id = 1, source = "文本,\"quoted\"\nnext")
        val result = exportDiagnostics(ExportFormat.JSON, ExportMetadata("test", "UTC", 31, now), { output }) { after ->
            if (after == 0L) listOf(sample) else emptyList()
        }
        assertEquals(ExportResult.SAVED, result)
        val root = JSONObject(output.toString("UTF-8"))
        val row = root.getJSONArray("records").getJSONObject(0)
        assertEquals(sample.sourceId, row.getString("sourceId"))
        assertTrue(row.isNull("uncertaintyNanos")); assertTrue(row.isNull("measuredErrorNanos"))
        assertEquals(-456, row.getLong("estimatedOffsetNanos"))
        assertFalse(root.getBoolean("accuracyVerified"))
    }

    @Test fun exportCancellationNeverOpensOrReadsData() = runBlocking {
        assertEquals(ExportResult.CANCELLED, exportDiagnostics(ExportFormat.JSON, ExportMetadata("test", "UTC", 31, now), null) {
            error("Cancelled export must not query data")
        })
    }

    @Test fun exportWriteAndCloseFailuresAreReported() = runBlocking {
        var closed = false
        val full = object : OutputStream() {
            override fun write(value: Int) { throw IOException("No space left") }
            override fun close() { closed = true }
        }
        assertEquals(ExportResult.FAILED, exportDiagnostics(ExportFormat.CSV, ExportMetadata("test", "UTC", 31, now), { full }) { emptyList() })
        assertTrue(closed)
        val denied = exportDiagnostics(ExportFormat.JSON, ExportMetadata("test", "UTC", 31, now), { throw SecurityException("Denied") }) { emptyList() }
        assertEquals(ExportResult.FAILED, denied)
        val closeFailure = object : ByteArrayOutputStream() { override fun close() { throw IOException("Close failed") } }
        assertEquals(ExportResult.FAILED, exportDiagnostics(ExportFormat.JSON, ExportMetadata("test", "UTC", 31, now), { closeFailure }) { emptyList() })
    }

    @Test fun pagedExportKeepsItsIdBoundaryWhileNewRowsArrive() = runBlocking {
        val db = database()
        try {
            val repository = DiagnosticRepository(db, { now })
            repository.append(List(501) { record() })
            val last = repository.dao.lastId()!!
            repository.append(listOf(record()))
            val first = repository.dao.exportPage(0, last, 0, null, null)
            val second = repository.dao.exportPage(first.last().id, last, 0, null, null)
            assertEquals(500, first.size); assertEquals(1, second.size); assertEquals(last, second.single().id)
        } finally { db.close() }
    }

    @Test fun csvExportIncludesFilteredRowsAndNulls() = runBlocking {
        val output = ByteArrayOutputStream()
        val result = exportDiagnostics(ExportFormat.CSV, ExportMetadata("test", "UTC", 31, now), { output }) { after ->
            if (after == 0L) listOf(record(id = 1, source = "=unsafe")) else emptyList()
        }
        assertEquals(ExportResult.SAVED, result)
        val text = output.toString("UTF-8")
        assertTrue(text.contains("\"'=unsafe\""))
        assertTrue(text.contains("\"uncertaintyNanos\"")); assertTrue(text.contains(",-456,,"))
    }

    @Test fun backupIsDisabledAndStorageUsesExcludedDirectory() {
        assertEquals(0, context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertTrue(context.noBackupFilesDir.name == "no_backup")
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        val sections = mutableSetOf<String>()
        parser.use {
            while (it.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (it.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) sections += it.name
                it.next()
            }
        }
        assertTrue(sections.containsAll(listOf("cloud-backup", "device-transfer", "exclude")))
    }
}
