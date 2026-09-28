package io.github.floatingclock

import io.github.floatingclock.time.PlatformId

internal enum class DisplayMode { FULL, COMPACT, MINIMAL }

/** In-memory configuration; ordering and selection share one source of truth. */
internal data class OverlayConfig(
    val platforms: List<PlatformId> = listOf(PlatformId.TAOBAO_TMALL),
    val mode: DisplayMode = DisplayMode.FULL,
) {
    init {
        require(platforms.size in 1..3 && platforms.distinct().size == platforms.size)
    }

    fun toggle(platform: PlatformId): OverlayConfig = when {
        platform in platforms && platforms.size > 1 -> copy(platforms = platforms - platform)
        platform !in platforms && platforms.size < 3 -> copy(platforms = platforms + platform)
        else -> this
    }

    fun move(platform: PlatformId, direction: Int): OverlayConfig {
        require(direction == -1 || direction == 1)
        val from = platforms.indexOf(platform)
        val to = from + direction
        if (from < 0 || to !in platforms.indices) return this
        val ordered = platforms.toMutableList()
        ordered[from] = ordered[to]
        ordered[to] = platform
        return copy(platforms = ordered)
    }
}

internal fun PlatformId.label(): String = when (this) {
    PlatformId.TAOBAO_TMALL -> "淘宝／天猫"
    PlatformId.JD -> "京东"
    PlatformId.MEITUAN -> "美团"
    PlatformId.PDD -> "拼多多"
    PlatformId.DOUYIN -> "抖音"
}

/** Counts completed draws, not the requested display refresh rate. */
internal class FrameStats {
    private var firstNanos = -1L
    private var frames = 0
    var fps = 0.0
        private set

    fun reset() { firstNanos = -1; frames = 0; fps = 0.0 }

    fun drawn(nowNanos: Long): Boolean {
        if (firstNanos < 0 || nowNanos < firstNanos) {
            firstNanos = nowNanos
            frames = 0
            return false
        }
        frames++
        val elapsed = nowNanos - firstNanos
        if (elapsed < 1_000_000_000L) return false
        fps = frames * 1_000_000_000.0 / elapsed
        firstNanos = nowNanos
        frames = 0
        return true
    }
}