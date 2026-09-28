package io.github.floatingclock

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import io.github.floatingclock.time.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class SourceChoice(val label: String) {
    AUTO("自动：系统网络时间 → 公共 NTP"), SYSTEM("Android 系统网络时间（API 33+）"),
    NTP_PRIMARY("公共 NTP：Cloudflare"), NTP_BACKUP("公共 NTP：Google（闰秒平滑）"),
    HTTP("HTTP_ESTIMATED：公开 HTTPS Date"), DEMO("演示数据（不访问网络）"),
}

internal object NetworkSources {
    // Instrumentation injection; release builds never consult it.
    internal var testSource: TimeSource? = null
    private val clock = ClockProvider(SystemClock::elapsedRealtimeNanos)
    private val budget = RequestBudget(clock)
    private val executor = Executor { Dispatchers.IO.dispatch(EmptyCoroutineContext, it) }
    private suspend fun resolve(host: String): InetAddress = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        if (continuation.isActive) DnsResolver.getInstance().query(null, host, DnsResolver.FLAG_EMPTY, executor, cancellation,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    if (!continuation.isActive) return
                    if (rcode == 0 && answer.isNotEmpty()) continuation.resume(answer.first())
                    else continuation.resumeWithException(java.net.UnknownHostException("DNS lookup failed"))
                }
                override fun onError(error: DnsResolver.DnsException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            })
    }

    fun create(choice: SourceChoice, httpUrl: String, clock: ClockProvider): List<TimeSource> {
        if (BuildConfig.DEBUG) testSource?.let { return listOf(it) }
        if (choice == SourceChoice.DEMO) return listOf(DemoTimeSource(clock))
        val system = if (Build.VERSION.SDK_INT >= 33) SystemNetworkTimeSource(clock) {
            SystemClock.currentNetworkTimeClock().millis()
        } else null
        val primary = NtpTimeSource("time.cloudflare.com", clock, System::currentTimeMillis, ::resolve)
        val backup = NtpTimeSource("time.google.com", clock, System::currentTimeMillis, ::resolve)
        val sources = when (choice) {
            SourceChoice.AUTO -> initialSourceOrder(Build.VERSION.SDK_INT, system, primary, backup)
            SourceChoice.SYSTEM -> listOf(requireNotNull(system) { "Android 13+ required" })
            SourceChoice.NTP_PRIMARY -> listOf(primary)
            SourceChoice.NTP_BACKUP -> listOf(backup)
            SourceChoice.HTTP -> listOf(HttpDateTimeSource(URL(httpUrl), clock))
            SourceChoice.DEMO -> error("Handled above")
        }
        return sources.map { BudgetedTimeSource(it, budget) }
    }
}

internal fun PlatformTimeState.sourceLabel(): String = when (sourceType) {
    TimeSourceType.NTP -> if (sourceId?.startsWith("demo:") == true) "模拟 NTP" else "公共 NTP（非平台官方）"
    TimeSourceType.SYSTEM_NETWORK -> "Android 系统网络时间"
    TimeSourceType.HTTP_ESTIMATE -> "HTTP_ESTIMATED · 秒级 Date"
    TimeSourceType.OFFICIAL_API -> "官方接口"
    null -> "尚未选定来源"
}

internal fun PlatformTimeState.statusLabel(): String = when {
    isCalibrating -> "正在校准 · 精度未验证"
    status == CalibrationStatus.SYNCED -> "已校准 · 精度未验证"
    status == CalibrationStatus.RETRYING -> "校准失败，保留旧基准 · 重试中"
    status == CalibrationStatus.STALE -> "校时失效 · 旧基准／无可信时间"
    else -> "尚未校准 · 无可信时间"
}
