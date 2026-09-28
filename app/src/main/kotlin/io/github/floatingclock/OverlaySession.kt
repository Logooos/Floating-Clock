package io.github.floatingclock

/** Main-thread session owner. Cleanup also runs after a partially completed start. */
internal class OverlaySession(
    private val promote: () -> Unit,
    private val attach: () -> Unit,
    private val release: () -> Unit,
) {
    var running = false
        private set
    var failure: String? = null
        private set
    private var acquired = false

    fun start(overlayGranted: Boolean, interactive: Boolean): Boolean {
        if (!overlayGranted || !interactive) {
            stop()
            failure = if (!overlayGranted) "需要悬浮窗权限" else "锁屏或屏幕关闭，已停止"
            return false
        }
        if (running) return true
        return try {
            acquired = true
            promote()
            attach()
            if (!acquired) return false
            running = true
            failure = null
            true
        } catch (_: RuntimeException) {
            stop()
            failure = "前台服务或窗口创建失败，请检查系统权限后重试"
            false
        }
    }

    fun stop() {
        running = false
        if (acquired) {
            acquired = false
            release()
        }
    }
}