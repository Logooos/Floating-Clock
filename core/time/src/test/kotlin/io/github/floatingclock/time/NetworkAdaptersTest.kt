package io.github.floatingclock.time

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.time.DateTimeException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NetworkAdaptersTest {
    private val utc = 1_767_225_600_000_000_000L
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test fun systemNetworkBracketsReadAndDoesNotClaimAccuracy() = runBlocking {
        val clock = FakeClock()
        val source = SystemNetworkTimeSource(clock) { clock.advanceNanos(4_000_000); utc / 1_000_000 }
        val result = source.calibrate() as CalibrationResult.Success
        assertEquals(2_000_000, result.anchor.localMonotonicNanos)
        assertEquals(utc, result.anchor.serverUtcEpochNanos)
        assertNull(result.estimatedUncertaintyNanos); assertFalse(PlatformTimeState(PlatformId.JD, lastSuccess = result).accuracyVerified)
    }
    @Test fun systemNetworkUnavailableSlowReadAndOverflowFail() = runBlocking {
        val clock = FakeClock()
        assertTrue(SystemNetworkTimeSource(clock) { throw DateTimeException("unavailable") }.calibrate() is CalibrationResult.Failure)
        assertTrue(SystemNetworkTimeSource(clock) { clock.advanceNanos(100_000_001); 0 }.calibrate() is CalibrationResult.Failure)
        assertTrue(SystemNetworkTimeSource(clock) { Long.MAX_VALUE }.calibrate() is CalibrationResult.Failure)
        assertTrue(SystemNetworkTimeSource(clock) { clock.monotonicNanos.set(0); 0 }.calibrate() is CalibrationResult.Failure)
    }
    @Test fun systemNetworkDiscontinuityIsAnewAnchorNotWallInterpolation() = runBlocking {
        val clock = FakeClock(); var millis = 1000L
        val source = SystemNetworkTimeSource(clock) { millis }
        val first = source.calibrate() as CalibrationResult.Success
        millis = 500; clock.advanceNanos(1_000_000)
        val second = source.calibrate() as CalibrationResult.Success
        assertTrue(second.anchor.serverUtcEpochNanos < first.anchor.serverUtcEpochNanos)
        assertEquals(1_000_000, second.anchor.localMonotonicNanos)
    }
    @Test fun udpLoopbackValidatesPeerAndIgnoresChangingWallClock() = runBlocking {
        val clock = FakeClock(); clock.wallUtcEpochMillis = utc / 1_000_000
        DatagramSocket(0, loopback).use { server ->
            val worker = async(Dispatchers.IO) {
                server.soTimeout = 3000
                val incoming = DatagramPacket(ByteArray(48), 48); server.receive(incoming)
                val bytes = ntpResponse(incoming.data, utc + 250_000_000, utc + 500_000_000)
                // The connected client must not accept a response from a different UDP port.
                DatagramSocket(0, loopback).use { stranger -> stranger.send(DatagramPacket(bytes, 48, incoming.address, incoming.port)) }
                clock.wallUtcEpochMillis += 86_400_000
                clock.advanceNanos(750_000_000)
                server.send(DatagramPacket(bytes, 48, incoming.address, incoming.port))
            }
            val source = NtpTimeSource("local", clock, { clock.wallUtcEpochMillis }, { loopback }, server.localPort)
            val result = source.calibrate() as CalibrationResult.Success
            worker.await()
            assertEquals(utc + 750_000_000, result.anchor.serverUtcEpochNanos)
            assertEquals(500_000_000L, result.roundTripNanos)
            assertEquals("127.0.0.1:${server.localPort}", result.endpoint)
        }
    }
    @Test fun udpTimeoutIsFailureAndCancellationDoesNotPublish() = runBlocking {
        DatagramSocket(0, loopback).use { server ->
            val source = NtpTimeSource("local", FakeClock(), { 0 }, { loopback }, server.localPort, 100)
            assertTrue(source.calibrate() is CalibrationResult.Failure)
        }
        DatagramSocket(0, loopback).use { server ->
            val received = CompletableDeferred<Unit>()
            val worker = launch(Dispatchers.IO) {
                server.soTimeout = 3000; server.receive(DatagramPacket(ByteArray(48), 48)); received.complete(Unit)
            }
            var published = false
            val job = launch { NtpTimeSource("local", FakeClock(), { 0 }, { loopback }, server.localPort).calibrate(); published = true }
            withTimeout(3000) { received.await() }; job.cancelAndJoin(); worker.join()
            assertFalse(published)
        }
    }
    @Test fun dnsFailureAndMalformedUdpAreExplicitFailures() = runBlocking {
        assertTrue(NtpTimeSource("invalid", FakeClock(), { 0 }, { throw UnknownHostException() }).calibrate() is CalibrationResult.Failure)
        DatagramSocket(0, loopback).use { server ->
            val worker = launch(Dispatchers.IO) {
                server.soTimeout = 3000
                val incoming = DatagramPacket(ByteArray(48), 48); server.receive(incoming)
                server.send(DatagramPacket(byteArrayOf(1, 2), 2, incoming.address, incoming.port))
            }
            assertTrue(NtpTimeSource("local", FakeClock(), { 0 }, { loopback }, server.localPort).calibrate() is CalibrationResult.Failure)
            worker.join()
        }
    }
    @Test fun httpLoopbackUsesHeadAndDoesNotFollowRedirects() = runBlocking {
        for (status in listOf(200, 302)) {
            val clock = FakeClock()
            ServerSocket(0, 1, loopback).use { server ->
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val worker = executor.submit {
                        server.soTimeout = 3000
                        server.accept().use { socket ->
                            socket.soTimeout = 3000
                            val input = socket.getInputStream().bufferedReader()
                            assertEquals("HEAD / HTTP/1.1", input.readLine())
                            while (!input.readLine().isNullOrEmpty()) { }
                            clock.advanceNanos(100_000_000)
                            socket.getOutputStream().write(("HTTP/1.1 $status Test\r\nDate: Thu, 01 Jan 2026 00:00:00 GMT\r\nCache-Control: no-store\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                        }
                    }
                    val result = HttpDateTimeSource(URL("http://127.0.0.1:${server.localPort}/"), clock, allowLoopbackHttp = true).calibrate()
                    worker.get(3, TimeUnit.SECONDS)
                    if (status == 200) assertEquals(utc + 50_000_000, (result as CalibrationResult.Success).anchor.serverUtcEpochNanos)
                    else assertTrue(result is CalibrationResult.Failure)
                } finally { executor.shutdownNow() }
            }
        }
    }
    @Test fun httpRejectsPlaintextSecretsQueriesAndFragments() {
        for (url in listOf("http://example.com", "https://user:secret@example.com", "https://example.com?key=secret", "https://example.com#key", "https://", "https://example.com:65536")) {
            assertThrows(IllegalArgumentException::class.java) { HttpDateTimeSource(URL(url), FakeClock()) }
        }
    }

    @Test fun httpTimeoutAndCancellationDoNotPublishLateResponses() = runBlocking {
        for (cancel in listOf(false, true)) {
            ServerSocket(0, 1, loopback).use { server ->
                val accepted = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val worker = launch(Dispatchers.IO) {
                    server.soTimeout = 3000
                    server.accept().use { accepted.complete(Unit); release.await() }
                }
                val request = async {
                    HttpDateTimeSource(URL("http://127.0.0.1:${server.localPort}/"), FakeClock(),
                        timeoutMillis = if (cancel) 2000 else 200, allowLoopbackHttp = true).calibrate()
                }
                try {
                    withTimeout(3000) { accepted.await() }
                    if (cancel) { request.cancelAndJoin(); assertTrue(request.isCancelled) }
                    else assertTrue(request.await() is CalibrationResult.Failure)
                } finally { release.complete(Unit); worker.join() }
            }
        }
    }
}
