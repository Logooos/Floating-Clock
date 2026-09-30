package io.github.floatingclock

import io.github.floatingclock.time.PlatformId

/** Read-only grouping of the legacy writer's five rows, never a network request count. */
internal fun measurementGroups(records: List<DiagnosticRecord>): List<List<DiagnosticRecord>> {
    val expected = PlatformId.entries.reversed().map { it.name }
    val result = mutableListOf<List<DiagnosticRecord>>()
    var index = 0
    while (index < records.size) {
        val candidate = records.subList(index, minOf(index + expected.size, records.size))
        val first = candidate.first()
        val signature = first.copy(id = 0, platformId = "")
        // Partial/filtered pages and ambiguous sequences remain separate raw records.
        val matched = candidate.size == expected.size && candidate.map { it.platformId } == expected &&
            first.id >= expected.size && candidate.withIndex().all { (position, record) ->
                record.id == first.id - position && record.copy(id = 0, platformId = "") == signature
            }
        result += if (matched) candidate.toList() else listOf(first)
        index += if (matched) expected.size else 1
    }
    return result
}
