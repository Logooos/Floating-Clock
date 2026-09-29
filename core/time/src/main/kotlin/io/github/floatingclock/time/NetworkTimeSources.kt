package io.github.floatingclock.time

import kotlinx.coroutines.*
import java.net.*
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** I/O workers never publish after cancellation; closing the resource unblocks receive/read. */
internal suspend fun <T> networkIo(close: () -> Unit, operation: () -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { runCatching(close) }
    Dispatchers.IO.dispatch(continuation.context, Runnable {
        try {
            if (continuation.isActive) continuation.resume(operation())
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        } finally { runCatching(close) }
    })
}

/** DNS is injectable: Android supplies cancellable DnsResolver; JVM tests use loopback. */
typealias AddressResolver = suspend (String) -> InetAddress

class SystemNetworkTimeSource(
    private val clock: ClockProvider,
    private val readEpochMillis: () -> Long,
) : TimeSource {
    override val sourceId = "android:network-clock"
    override val type = TimeSourceType.SYSTEM_NETWORK
    override suspend fun calibrate(): CalibrationResult = sourceResult(sourceId) {
        withContext(Dispatchers.IO) {
            val before = clock.elapsedRealtimeNanos()
            val epochNanos = Math.multiplyExact(readEpochMillis(), 1_000_000L)
            val after = clock.elapsedRealtimeNanos()
            val elapsed = checkedElapsed(before, after)
            require(elapsed <= 100_000_000) { "System clock read took too long" }
            CalibrationResult.Success(sourceId, type, TimeAnchor(epochNanos, before + elapsed / 2),
                resolutionNanos = 1_000_000, measurementNotes = "Android network estimate; may jump; accuracy and cache age unknown")
        }
    }
}

/** An individual host is a source. Backup selection belongs only to initial session selection. */
class NtpTimeSource(
    private val host: String,
    private val clock: ClockProvider,
    private val wallEpochMillis: () -> Long,
    private val resolve: AddressResolver,
    private val port: Int = 123,
    private val timeoutMillis: Int = 2_000,
) : TimeSource {
    init { require(host.isNotBlank() && port in 1..65535 && timeoutMillis in 1..10_000) }
    override val sourceId = "ntp:$host:$port"
    override val type = TimeSourceType.NTP

    override suspend fun calibrate(): CalibrationResult = sourceResult(sourceId) {
        withTimeout(timeoutMillis.toLong()) {
            val remote = resolve(host)
            currentCoroutineContext().ensureActive()
            val socket = DatagramSocket()
            networkIo(socket::close) {
                socket.soTimeout = timeoutMillis
                socket.connect(remote, port)
                val start = clock.elapsedRealtimeNanos()
                val localUtc = Math.multiplyExact(wallEpochMillis(), 1_000_000L)
                val request = NtpPacket.request(localUtc)
                socket.send(DatagramPacket(request, request.size))
                val response = DatagramPacket(ByteArray(513), 513)
                socket.receive(response)
                val end = clock.elapsedRealtimeNanos()
                require(response.address == remote && response.port == port) { "Unexpected NTP peer" }
                val sample = NtpPacket.parse(request, response.data.copyOf(response.length), localUtc, start, end)
                CalibrationResult.Success(sourceId, type, TimeAnchor(sample.utcAtReceiptNanos, end),
                    resolutionNanos = sample.resolutionNanos, estimatedOffsetNanos = sample.offsetNanos,
                    roundTripNanos = sample.roundTripNanos, endpoint = "${remote.hostAddress}:$port",
                    measurementNotes = "Unauthenticated NTP; symmetric-path offset estimate, not measured error")
            }
        }
    }
}

internal suspend fun sourceResult(id: String, operation: suspend () -> CalibrationResult.Success): CalibrationResult = try {
    operation()
} catch (_: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive()
    CalibrationResult.Failure(id, "请求超时；UDP 123 或当前来源可能不可访问")
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    // No URL query, response body, cookies, or exception message containing user input is logged.
    CalibrationResult.Failure(id, "来源不可用或响应未通过验证（${error.javaClass.simpleName}）")
}

internal fun checkedElapsed(start: Long, end: Long): Long {
    require(start >= 0 && end >= start) { "Invalid monotonic measurement" }
    return Math.subtractExact(end, start)
}

class HttpDateTimeSource(
    private val url: URL,
    private val clock: ClockProvider,
    private val timeoutMillis: Int = 2_000,
    allowLoopbackHttp: Boolean = false,
) : TimeSource {
    init {
        require(timeoutMillis in 1..10_000)
        require(url.host.isNotBlank() && (url.port == -1 || url.port in 1..65535))
        require(url.userInfo == null && url.query == null && url.ref == null)
        require(url.protocol == "https" || (allowLoopbackHttp && url.protocol == "http" && url.host == "127.0.0.1"))
    }
    override val sourceId = "http-date:${url.toExternalForm()}"
    override val type = TimeSourceType.HTTP_ESTIMATE // UI name: HTTP_ESTIMATED; retain the existing domain contract.

    override suspend fun calibrate(): CalibrationResult = sourceResult(sourceId) {
        withTimeout(timeoutMillis.toLong()) {
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "HEAD"
            connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
            connection.setRequestProperty("Pragma", "no-cache")
            connection.setRequestProperty("User-Agent", "FloatingClock (HTTP-Date estimate)")
            networkIo(connection::disconnect) {
                val start = clock.elapsedRealtimeNanos()
                val status = connection.responseCode
                val headers = connection.headerFields.filterKeys { it != null }
                val end = clock.elapsedRealtimeNanos()
                HttpDateSample.parse(sourceId, status, headers, start, end)
            }
        }
    }
}

object HttpDateSample {
    fun parse(id: String, status: Int, headers: Map<String?, List<String>>, start: Long, end: Long): CalibrationResult.Success {
        val elapsed = checkedElapsed(start, end)
        require(elapsed <= 2_000_000_000L && status == 200) { "HTTP status or delay rejected" }
        fun values(name: String) = headers.entries.filter { it.key.equals(name, true) }.flatMap { it.value }
        val dates = values("Date")
        require(dates.size == 1 && dates.single().endsWith(" GMT")) { "A single HTTP Date is required" }
        val age = values("Age")
        require(age.isEmpty() || (age.size == 1 && age.single().toLongOrNull() == 0L)) { "Cached response" }
        require(values("Via").isEmpty() && values("Warning").isEmpty()) { "Intermediary freshness cannot be verified" }
        require((values("X-Cache") + values("CF-Cache-Status")).none { it.contains("hit", true) || it.contains("stale", true) })
        val control = values("Cache-Control").joinToString(",").lowercase().split(',').map { it.trim() }
        require(control.any { it == "no-store" || it == "no-cache" || it == "max-age=0" }) { "No freshness directive" }
        val instant = ZonedDateTime.parse(dates.single(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        val dateNanos = Math.multiplyExact(instant.epochSecond, 1_000_000_000L)
        return CalibrationResult.Success(id, TimeSourceType.HTTP_ESTIMATE,
            TimeAnchor(Math.addExact(dateNanos, elapsed / 2), end), resolutionNanos = 1_000_000_000,
            roundTripNanos = elapsed, endpoint = id.removePrefix("http-date:"),
            measurementNotes = "HTTP_ESTIMATED: second-quantized Date; CDN/generation position unknown; no proven uncertainty")
    }
}
