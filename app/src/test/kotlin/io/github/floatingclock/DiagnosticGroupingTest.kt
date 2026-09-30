package io.github.floatingclock

import io.github.floatingclock.time.*
import org.junit.Assert.*
import org.junit.Test

class DiagnosticGroupingTest {
    private fun sample(firstId: Long = 1) = diagnosticRecords(
        PlatformTimeState(PlatformId.JD, "ntp:time.cloudflare.com:123", TimeSourceType.NTP,
            CalibrationStatus.RETRYING, failureReason = "timeout"), null, 1234
    ).mapIndexed { index, record -> record.copy(id = firstId + index) }.reversed()

    @Test fun completeSharedSampleGroupsWithoutMutatingRawRecords() {
        val records = sample()
        val groups = measurementGroups(records)
        assertEquals(1, groups.size)
        assertEquals(records, groups.flatten())
        assertEquals(5, groups.first().size)
        assertTrue(groups.first().all { it.measuredErrorNanos == null })
    }

    @Test fun identicalConsecutiveSamplesStaySeparate() {
        assertEquals(listOf(5, 5), measurementGroups(sample(6) + sample()).map { it.size })
    }

    @Test fun partialPagesAndFilteredRecordsAreNotGuessed() {
        assertEquals(4, measurementGroups(sample().drop(1)).size)
        assertEquals(2, measurementGroups(sample().filter { it.platformId in listOf("JD", "PDD") }).size)
    }

    @Test fun DifferentMetricsOrEventsCannotBeMerged() {
        val records = sample()
        for (changed in listOf(records[2].copy(roundTripNanos = 0), records[2].copy(manualSync = true),
            records[2].copy(sourceRecovered = true), records[2].copy(sourceId = "other"))) {
            assertEquals(5, measurementGroups(records.toMutableList().apply { this[2] = changed }).size)
        }
    }

    @Test fun duplicatePlatformsAndIdGapsRemainRaw() {
        val records = sample()
        assertEquals(5, measurementGroups(records.toMutableList().apply {
            this[2] = this[2].copy(platformId = this[1].platformId)
        }).size)
        assertEquals(5, measurementGroups(records.toMutableList().apply {
            this[2] = this[2].copy(id = 50)
        }).size)
        assertTrue(measurementGroups(emptyList()).isEmpty())
    }
}
