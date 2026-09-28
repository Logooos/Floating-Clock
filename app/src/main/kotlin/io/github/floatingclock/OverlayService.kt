package io.github.floatingclock

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.floatingclock.time.*
import kotlinx.coroutines.*

/** Process-local state, accessed only on the main thread. No service or anchor is persisted. */
internal object OverlayState {
    var config by mutableStateOf(OverlayConfig())
        private set
    var running by mutableStateOf(false)
    var requested by mutableStateOf(false)
    var message by mutableStateOf("未启动")
    var fps by mutableStateOf(0.0)
    var sourceChoice by mutableStateOf(SourceChoice.AUTO)
    var httpUrl by mutableStateOf("")
    var timeState by mutableStateOf(PlatformTimeState(PlatformId.TAOBAO_TMALL))
    var syncDetails by mutableStateOf("尚未校准，实际误差未知")
    var onSyncNow: (() -> Unit)? = null
    var onConfigChanged: (() -> Unit)? = null
    fun configure(value: OverlayConfig) { config = value; onConfigChanged?.invoke() }
}

class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var synchronizer: TimeSynchronizer? = null
    private lateinit var windows: WindowManager
    private lateinit var session: OverlaySession
    internal var clockView: OverlayClockView? = null
        private set
    private var windowAdded = false
    private var receiverRegistered = false
    private var watchingPermissions = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val permissionListener = AppOpsManager.OnOpChangedListener { _, packageName ->
        if (packageName == this.packageName) mainHandler.post {
            if (watchingPermissions && !Settings.canDrawOverlays(this)) shutdown("悬浮窗权限已撤销")
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) shutdown("锁屏／息屏已停止，需手动重新启动")
        }
    }
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = 16; y = 160
        preferredRefreshRate = 120f // Advisory only; the display and power policy remain in control.
        title = "Floating Clock"
    }

    override fun onCreate() {
        super.onCreate()

        session = OverlaySession(::promote, ::attachWindow, ::releaseResources)
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START || !OverlayState.requested) {
            shutdown("已停止")
            return START_NOT_STICKY
        }
        if (session.start(Settings.canDrawOverlays(this), interactive())) {
            OverlayState.running = true
            OverlayState.message = if (OverlayState.sourceChoice == SourceChoice.DEMO) "运行中 · 演示数据" else "运行中 · 网络校时"
        } else shutdown(session.failure ?: "无法启动")
        return START_NOT_STICKY
    }

    private fun interactive(): Boolean = getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun promote() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("overlay", "悬浮时钟", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val home = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "overlay")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(if (OverlayState.sourceChoice == SourceChoice.DEMO) "Floating Clock · 演示数据" else "Floating Clock · 网络时间")
            .setContentText("实际误差未验证；可停止或返回首页查看来源")
            .setContentIntent(home).setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, notification)
    }

    private fun attachWindow() {
        val display = getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val context = createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        windows = context.getSystemService(WindowManager::class.java)
        val clock = ClockProvider(SystemClock::elapsedRealtimeNanos)
        val engine = TimeEngine(clock)
        val candidates = NetworkSources.create(OverlayState.sourceChoice, OverlayState.httpUrl, clock)
        OverlayState.timeState = engine.state(PlatformId.TAOBAO_TMALL)
        OverlayState.syncDetails = "初次选源中；未获得锚点前不显示时间"
        val view = OverlayClockView(context, engine,
            allowed = { Settings.canDrawOverlays(this) && interactive() },
            stop = { shutdown("权限被撤销或屏幕不可交互，已停止") },
            move = { dx, dy -> params.x += dx; params.y += dy; updateWindow() },
            resize = { updateWindow() },
        )
        clockView = view
        windows.addView(view, params)
        windowAdded = true
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        receiverRegistered = true
        getSystemService(AppOpsManager::class.java).startWatchingMode(
            AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, permissionListener)
        watchingPermissions = true
        check(Settings.canDrawOverlays(this) && interactive())
        OverlayState.onConfigChanged = { view.configurationChanged(); updateWindow() }
        synchronizer = TimeSynchronizer(engine, clock, candidates, onUpdate = { state, adjustment, interval ->
            OverlayState.timeState = state
            val sample = state.lastSuccess
            OverlayState.syncDetails = buildString {
                append(state.sourceLabel()); append(" · "); append(state.sourceId ?: "尚未选定")
                append("\n"); append(state.statusLabel())
                sample?.roundTripNanos?.let { append("\n往返延迟（估计）：${it / 1_000_000.0} ms") }
                sample?.estimatedOffsetNanos?.let { append("\n相对请求时本机墙钟估计偏移：${it / 1_000_000.0} ms") }
                sample?.let { append("\n来源标称分辨率（非精度）：${it.resolutionNanos} ns；端点：${it.endpoint ?: state.sourceId}") }
                sample?.let { append("\n最后成功基准 UTC：${java.time.Instant.ofEpochSecond(Math.floorDiv(it.anchor.serverUtcEpochNanos, 1_000_000_000), Math.floorMod(it.anchor.serverUtcEpochNanos, 1_000_000_000L))}") }
                adjustment?.let { append("\n本次校准跳变量：${it / 1_000_000.0} ms（非实测误差）") }
                state.failureReason?.let { append("\n$it") }
                append("\n不确定度／实测误差：未知；下次自动同步约 ${interval / 1000} 秒")
            }
            view.refreshSourceDetails()
        }).also { it.start(scope) }
        OverlayState.onSyncNow = {
            synchronizer?.syncNow()
            OverlayState.message = "已请求立即同步；每来源至少间隔 30 秒"
        }
        updateWindow()
    }

    private fun updateWindow() {
        val view = clockView ?: return
        if (!view.isAttachedToWindow) return
        val bounds = windows.currentWindowMetrics.bounds
        val insets = windows.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        params.x = params.x.coerceIn(0, (bounds.width() - insets.left - insets.right - view.desiredWidth).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (bounds.height() - insets.top - insets.bottom - view.desiredHeight).coerceAtLeast(0))
        try { windows.updateViewLayout(view, params) }
        catch (_: RuntimeException) { shutdown("窗口更新失败，已停止") }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        clockView?.configurationChanged()
        updateWindow()
    }

    private fun releaseResources() {
        synchronizer?.stop()
        synchronizer = null
        OverlayState.onSyncNow = null
        OverlayState.onConfigChanged = null
        clockView?.let { view ->
            view.dispose()
            try { if (windowAdded) windows.removeViewImmediate(view) }
            catch (_: IllegalArgumentException) { /* Already removed by the system. */ }
        }
        windowAdded = false
        clockView = null
        if (receiverRegistered) { unregisterReceiver(screenReceiver); receiverRegistered = false }
        if (watchingPermissions) {
            getSystemService(AppOpsManager::class.java).stopWatchingMode(permissionListener)
            watchingPermissions = false
        }
        mainHandler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun shutdown(message: String) {
        OverlayState.requested = false
        OverlayState.running = false
        OverlayState.message = message
        OverlayState.fps = 0.0
        scope.cancel()
        session.stop()
        stopSelf()
    }

    override fun onDestroy() {
        shutdown(if (OverlayState.running) "已停止" else OverlayState.message)
        super.onDestroy()
    }

    companion object {
        internal const val ACTION_START = "io.github.floatingclock.START"
        internal const val ACTION_STOP = "io.github.floatingclock.STOP"
    }
}
