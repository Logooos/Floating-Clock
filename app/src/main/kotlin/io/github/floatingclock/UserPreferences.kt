package io.github.floatingclock

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.google.protobuf.InvalidProtocolBufferException
import io.github.floatingclock.storage.UserSettings
import io.github.floatingclock.time.PlatformId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.time.ZoneId

internal enum class VisualStyle(val label: String) {
    DARK("简约深色"), LIGHT("浅色卡片"), GLASS("半透明磨砂玻璃"), DIGITS("无背景纯数字")
}
internal enum class ClockFont(val label: String) { MONOSPACE("等宽"), SANS("无衬线"), SERIF("衬线") }

internal data class UserPreferences(
    val overlay: OverlayConfig = OverlayConfig(),
    val clockRows: List<ClockRow> = listOf(ClockRow.PUBLIC),
    val presetMigrationAcknowledged: Boolean = true,
    val globalOffsetMillis: Long = 0,
    val platformOffsetsMillis: Map<PlatformId, Long> = PlatformId.entries.associateWith { 0L },
    val zoneId: String = "Asia/Shanghai",
    val sourceChoice: SourceChoice = SourceChoice.AUTO,
    val manualSource: SourceChoice = SourceChoice.NTP_PRIMARY,
    val httpUrl: String = "",
    val style: VisualStyle = VisualStyle.DARK,
    val font: ClockFont = ClockFont.MONOSPACE,
    val fontSizeSp: Float = 27f,
    val textColorArgb: Int = 0xffffffff.toInt(),
    val backgroundColorArgb: Int = 0xff191d23.toInt(),
    val backgroundOpacity: Float = 1f,
    val positionXRatio: Float = 0.05f,
    val positionYRatio: Float = 0.2f,
) {
    fun chooseSource(choice: SourceChoice) = copy(sourceChoice = choice, manualSource = if (choice == SourceChoice.AUTO) manualSource else choice)
    fun preset(value: VisualStyle) = copy(style = value,
        textColorArgb = if (value == VisualStyle.LIGHT) 0xff17202a.toInt() else 0xffffffff.toInt(),
        backgroundColorArgb = if (value == VisualStyle.LIGHT) 0xfff4f6fa.toInt() else 0xff191d23.toInt(),
        backgroundOpacity = when (value) { VisualStyle.GLASS -> 0.65f; VisualStyle.DIGITS -> 0f; else -> 1f })

    fun usePublicClock() = copy(clockRows = listOf(ClockRow.PUBLIC), presetMigrationAcknowledged = true)

    fun normalized(): UserPreferences = copy(
        clockRows = clockRows.distinct().take(3).ifEmpty { listOf(ClockRow.PUBLIC) },
        globalOffsetMillis = globalOffsetMillis.coerceIn(-MAX_OFFSET_MILLIS, MAX_OFFSET_MILLIS),
        platformOffsetsMillis = PlatformId.entries.associateWith { (platformOffsetsMillis[it] ?: 0).coerceIn(-MAX_OFFSET_MILLIS, MAX_OFFSET_MILLIS) },
        zoneId = runCatching { ZoneId.of(zoneId).id }.getOrDefault("Asia/Shanghai"),
        httpUrl = validHttpsUrl(httpUrl) ?: "",
        sourceChoice = if (sourceChoice == SourceChoice.HTTP && validHttpsUrl(httpUrl) == null) SourceChoice.AUTO else sourceChoice,
        manualSource = if (manualSource == SourceChoice.AUTO) SourceChoice.NTP_PRIMARY else manualSource,
        fontSizeSp = fontSizeSp.finiteOr(27f).coerceIn(16f, 64f),
        textColorArgb = textColorArgb or 0xff000000.toInt(),
        backgroundColorArgb = backgroundColorArgb or 0xff000000.toInt(),
        backgroundOpacity = backgroundOpacity.finiteOr(1f).coerceIn(0f, 1f),
        positionXRatio = positionXRatio.finiteOr(0.05f).coerceIn(0f, 1f),
        positionYRatio = positionYRatio.finiteOr(0.2f).coerceIn(0f, 1f),
    )

    fun toProto(): UserSettings = UserSettings.newBuilder().setSchemaVersion(2)
        .addAllClockRows(clockRows.map { it.name }).setPresetMigrationAcknowledged(presetMigrationAcknowledged)
        .addAllSelectedPlatformIds(overlay.platforms.map { it.name }).setDisplayMode(overlay.mode.name)
        .setGlobalOffsetMillis(globalOffsetMillis).putAllPlatformOffsetMillis(platformOffsetsMillis.mapKeys { it.key.name })
        .setZoneId(zoneId).setSourceChoice(sourceChoice.name).setManualSource(manualSource.name).setHttpUrl(httpUrl)
        .setSkin(style.name).setFontFamily(font.name).setFontSizeSp(fontSizeSp).setTextColorArgb(textColorArgb)
        .setBackgroundColorArgb(backgroundColorArgb).setBackgroundOpacity(backgroundOpacity)
        .setPositionXRatio(positionXRatio).setPositionYRatio(positionYRatio).build()

    companion object {
        const val MAX_OFFSET_MILLIS = 86_400_000L // Explicit UI bound: +/- one day per offset.
        fun fromProto(value: UserSettings): UserPreferences {
            val defaults = UserPreferences()
            val platforms = value.selectedPlatformIdsList.mapNotNull { enumOrNull<PlatformId>(it) }.distinct().take(3).ifEmpty { defaults.overlay.platforms }
            return UserPreferences(
                overlay = OverlayConfig(platforms, enumOrNull<DisplayMode>(value.displayMode) ?: DisplayMode.FULL),
                clockRows = if (value.schemaVersion < 2) platforms.map(ClockRow::forPreset)
                    else value.clockRowsList.mapNotNull { enumOrNull<ClockRow>(it) },
                presetMigrationAcknowledged = value.schemaVersion >= 2 && value.presetMigrationAcknowledged,
                globalOffsetMillis = value.globalOffsetMillis,
                platformOffsetsMillis = PlatformId.entries.associateWith { value.platformOffsetMillisMap[it.name] ?: 0L },
                zoneId = value.zoneId,
                sourceChoice = enumOrNull<SourceChoice>(value.sourceChoice) ?: SourceChoice.AUTO,
                manualSource = enumOrNull<SourceChoice>(value.manualSource) ?: SourceChoice.NTP_PRIMARY,
                httpUrl = value.httpUrl,
                style = enumOrNull<VisualStyle>(value.skin) ?: VisualStyle.DARK,
                font = enumOrNull<ClockFont>(value.fontFamily) ?: ClockFont.MONOSPACE,
                fontSizeSp = if (value.hasFontSizeSp()) value.fontSizeSp else defaults.fontSizeSp,
                textColorArgb = if (value.hasTextColorArgb()) value.textColorArgb else defaults.textColorArgb,
                backgroundColorArgb = if (value.hasBackgroundColorArgb()) value.backgroundColorArgb else defaults.backgroundColorArgb,
                backgroundOpacity = if (value.hasBackgroundOpacity()) value.backgroundOpacity else defaults.backgroundOpacity,
                positionXRatio = if (value.hasPositionXRatio()) value.positionXRatio else defaults.positionXRatio,
                positionYRatio = if (value.hasPositionYRatio()) value.positionYRatio else defaults.positionYRatio,
            ).normalized()
        }
    }
}

private fun Float.finiteOr(default: Float) = if (isFinite()) this else default
private inline fun <reified T : Enum<T>> enumOrNull(name: String) = enumValues<T>().firstOrNull { it.name == name }
internal fun validHttpsUrl(text: String): String? = runCatching {
    val uri = URI(text.trim())
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    require(uri.port == -1 || uri.port in 1..65535)
    require(text.length <= 2048)
    uri.toASCIIString()
}.getOrNull()

internal object SettingsSerializer : Serializer<UserSettings> {
    override val defaultValue = UserPreferences().toProto()
    override suspend fun readFrom(input: InputStream): UserSettings = try { UserSettings.parseFrom(input) }
        catch (error: InvalidProtocolBufferException) { throw CorruptionException("Invalid settings protobuf", error) }
    override suspend fun writeTo(t: UserSettings, output: OutputStream) = t.writeTo(output)
}

/** Exactly one instance per file; callers update fields against the latest atomic transaction. */
internal class SettingsRepository(private val store: DataStore<UserSettings>) {
    val preferences = store.data.map(UserPreferences::fromProto).distinctUntilChanged()
    suspend fun update(change: (UserPreferences) -> UserPreferences): UserPreferences = UserPreferences.fromProto(
        store.updateData { change(UserPreferences.fromProto(it)).normalized().toProto() })

    companion object {
        fun open(file: File, scope: CoroutineScope) = SettingsRepository(DataStoreFactory.create(
            serializer = SettingsSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { SettingsSerializer.defaultValue },
            scope = scope, produceFile = { file },
        ))
    }
}
