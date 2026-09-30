package io.github.floatingclock

import io.github.floatingclock.storage.UserSettings
import io.github.floatingclock.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun emptyProtoUsesSafeDefaults() {
        assertEquals(UserPreferences().copy(clockRows = listOf(ClockRow.TAOBAO_TMALL),
            presetMigrationAcknowledged = false), UserPreferences.fromProto(UserSettings.getDefaultInstance()))
    }

    @Test fun legacyMigrationPreservesEveryPreferenceAndIsIdempotent() {
        val original = UserPreferences(overlay = OverlayConfig(listOf(PlatformId.JD, PlatformId.PDD)),
            globalOffsetMillis = -35, platformOffsetsMillis = PlatformId.entries.associateWith { it.ordinal * -17L },
            zoneId = "America/New_York", sourceChoice = SourceChoice.NTP_BACKUP,
            manualSource = SourceChoice.NTP_BACKUP, font = ClockFont.SERIF,
            fontSizeSp = 42f, positionXRatio = .8f).preset(VisualStyle.GLASS)
        val legacy = original.toProto().toBuilder().setSchemaVersion(1).clearClockRows()
            .clearPresetMigrationAcknowledged().build()
        val migrated = UserPreferences.fromProto(legacy)
        assertEquals(original.copy(clockRows = listOf(ClockRow.JD, ClockRow.PDD),
            presetMigrationAcknowledged = false), migrated)
        assertEquals(migrated, UserPreferences.fromProto(migrated.toProto()))
        val public = migrated.usePublicClock()
        assertEquals(migrated.platformOffsetsMillis, public.platformOffsetsMillis)
        assertEquals(migrated.overlay, public.overlay)
        assertTrue(UserPreferences.fromProto(public.toProto()).presetMigrationAcknowledged)
        assertEquals(listOf(ClockRow.PUBLIC), public.clockRows)
    }

    @Test fun publicRowCountsTowardThreeAndHasNoPlatformAlias() {
        assertNull(ClockRow.PUBLIC.preset)
        val normalized = UserPreferences(clockRows = listOf(ClockRow.PUBLIC, ClockRow.JD,
            ClockRow.PUBLIC, ClockRow.PDD, ClockRow.DOUYIN)).normalized()
        assertEquals(listOf(ClockRow.PUBLIC, ClockRow.JD, ClockRow.PDD), normalized.clockRows)
        assertEquals(listOf(ClockRow.PUBLIC), normalized.copy(clockRows = emptyList()).normalized().clockRows)
        assertTrue(ClockRow.JD.label().contains("手动预设"))
    }

    @Test fun platformOrderIsDeduplicatedBoundedAndCombined() {
        val proto = UserSettings.newBuilder().addAllSelectedPlatformIds(listOf("JD", "TAOBAO_TMALL", "JD", "BAD", "PDD", "MEITUAN")).build()
        assertEquals(listOf(PlatformId.JD, PlatformId.TAOBAO_TMALL, PlatformId.PDD), UserPreferences.fromProto(proto).overlay.platforms)
        assertEquals(listOf(PlatformId.TAOBAO_TMALL), UserPreferences.fromProto(proto.toBuilder().clearSelectedPlatformIds().build()).overlay.platforms)
    }

    @Test fun invalidNumbersZoneAndEnumsAreRepaired() {
        val proto = UserSettings.newBuilder().setZoneId("invalid/zone").setGlobalOffsetMillis(Long.MAX_VALUE)
            .setFontSizeSp(Float.NaN).setBackgroundOpacity(Float.POSITIVE_INFINITY).setPositionXRatio(-10f)
            .setDisplayMode("bad").setSkin("bad").setSourceChoice("bad").build()
        val value = UserPreferences.fromProto(proto)
        assertEquals("Asia/Shanghai", value.zoneId)
        assertEquals(86_400_000L, value.globalOffsetMillis)
        assertEquals(27f, value.fontSizeSp)
        assertEquals(1f, value.backgroundOpacity)
        assertEquals(0f, value.positionXRatio)
        assertEquals(DisplayMode.FULL, value.overlay.mode)
        assertEquals(SourceChoice.AUTO, value.sourceChoice)
    }

    @Test fun unsafeUrlsAreRejectedAndCannotBecomeActive() {
        listOf("http://example.com", "https://user:secret@example.com", "https://example.com/?token=x", "https://example.com/#secret", "https://example.com:99999", "https:///bad").forEach {
            assertNull(validHttpsUrl(it))
            assertEquals(SourceChoice.AUTO, UserPreferences(sourceChoice = SourceChoice.HTTP, httpUrl = it).normalized().sourceChoice)
        }
        assertEquals("https://example.com/time", validHttpsUrl(" https://example.com/time "))
    }

    @Test fun stylesAndModesRoundTripWithoutChangingPlatformSource() {
        for (style in VisualStyle.entries) for (mode in DisplayMode.entries) {
            val value = UserPreferences(sourceChoice = SourceChoice.DEMO, overlay = OverlayConfig(mode = mode)).preset(style)
            assertEquals(value, UserPreferences.fromProto(value.toProto()))
        }
    }

    @Test fun autoChoiceRemembersManualSource() {
        val value = UserPreferences().chooseSource(SourceChoice.NTP_BACKUP).chooseSource(SourceChoice.AUTO)
        assertEquals(SourceChoice.NTP_BACKUP, UserPreferences.fromProto(value.toProto()).manualSource)
    }

    @Test fun settingsSurviveRepositoryRestart() = runBlocking {
        val file = temporary.newFolder().resolve("settings.pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val value = UserPreferences(overlay = OverlayConfig(listOf(PlatformId.JD, PlatformId.TAOBAO_TMALL), DisplayMode.COMPACT),
            globalOffsetMillis = -123, platformOffsetsMillis = PlatformId.entries.associateWith { it.ordinal * 10L },
            zoneId = "America/New_York", sourceChoice = SourceChoice.HTTP, httpUrl = "https://example.com/date", font = ClockFont.SERIF,
            positionXRatio = 0.8f).preset(VisualStyle.LIGHT)
        SettingsRepository.open(file, firstScope).update { value }
        firstScope.coroutineContext[Job]!!.cancelAndJoin()
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { assertEquals(value, SettingsRepository.open(file, secondScope).preferences.first()) }
        finally { secondScope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun concurrentUpdatesDoNotLoseOtherFields() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = SettingsRepository.open(temporary.newFolder().resolve("settings.pb"), scope)
            coroutineScope { repeat(100) { launch { repository.update { it.copy(globalOffsetMillis = it.globalOffsetMillis + 1) } } } }
            assertEquals(100L, repository.preferences.first().globalOffsetMillis)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun corruptedProtobufIsReplacedWithDefaults() = runBlocking {
        val file = temporary.newFile("broken.pb").apply { writeBytes(byteArrayOf(0x0a, 0x7f, 0x01)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            assertEquals(UserPreferences(), SettingsRepository.open(file, scope).preferences.first())
            assertEquals(UserPreferences(), UserPreferences.fromProto(UserSettings.parseFrom(file.readBytes())))
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun missingFileIsCreatedOnFirstAtomicUpdate() = runBlocking {
        val file = temporary.newFolder().resolve("settings.pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = SettingsRepository.open(file, scope)
            assertEquals(UserPreferences(), repository.preferences.first())
            repository.update { it.copy(zoneId = "UTC") }
            assertEquals("UTC", UserSettings.parseFrom(file.readBytes()).zoneId)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun numericFieldsDoNotAcceptOverflowOrMalformedColors() {
        assertNull(offsetValue("9223372036854775808")); assertNull(offsetValue("86400001"))
        assertEquals(-86_400_000L, offsetValue("-86400000"))
        assertNull(colorValue("#ABC")); assertNull(colorValue("transparent"))
        assertEquals(0xffaabbcc.toInt(), colorValue("#AABBCC"))
    }

    @Test fun diagnosticRecordsDoNotLeakRequestPathsOrExceptionSecrets() {
        val state = PlatformTimeState(PlatformId.JD, "http-date:https://example.com/private?token=secret", TimeSourceType.HTTP_ESTIMATE,
            CalibrationStatus.RETRYING, failureReason = "Cookie=secret request failed")
        val records = diagnosticRecords(state, null, 123)
        assertEquals(5, records.size)
        assertTrue(records.all { it.sourceId == "https://example.com" && it.failureReason == "SOURCE_FAILURE" && it.roundTripNanos == null && it.measuredErrorNanos == null })
    }

    @Test fun failedAttemptDoesNotReuseOldSuccessMetrics() {
        val success = CalibrationResult.Success("ntp:time.cloudflare.com:123", TimeSourceType.NTP, TimeAnchor(1_000_000, 0), 1,
            estimatedOffsetNanos = 0, roundTripNanos = 0)
        val state = PlatformTimeState(PlatformId.JD, success.sourceId, success.sourceType, CalibrationStatus.RETRYING,
            lastSuccess = success, failureReason = "超时")
        val record = diagnosticRecords(state, null, 100).first()
        assertNull(record.roundTripNanos); assertNull(record.estimatedOffsetNanos)
        assertEquals(1L, record.lastSuccessUtcMillis); assertEquals("TIMEOUT", record.failureReason)
        assertEquals(0L, diagnosticRecords(state.copy(status = CalibrationStatus.SYNCED, failureReason = null), null, 100).first().roundTripNanos)
    }

    @Test fun csvPreservesQuotesNewlinesAndNeutralizesFormulas() {
        assertEquals("\"a,\"\"b\"\"\n中\"", csvCell("a,\"b\"\n中"))
        listOf("=1+1", " +1", "-cmd", "@cmd", "\tdata", "\nline").forEach { assertTrue(csvCell(it).startsWith("\"'")) }
        assertEquals("-123", csvCell(-123L)); assertEquals("", csvCell(null))
    }
}
