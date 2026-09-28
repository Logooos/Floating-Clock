package io.github.floatingclock.time

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NetworkProtocolTest {
    private val utc = 1_767_225_600_000_000_000L
    private val request = NtpPacket.request(utc)
    private fun response() = ntpResponse(request, utc + 250_000_000, utc + 500_000_000)
    private fun parse(bytes: ByteArray) = NtpPacket.parse(request, bytes, utc, 0, 750_000_000)
    private fun rejected(change: (ByteArray) -> Unit) { assertThrows(IllegalArgumentException::class.java) { parse(response().also(change)) } }

    @Test fun ntpFourTimestampArithmeticAndRequestMode() {
        assertEquals(0x23, request[0].toInt())
        val sample = parse(response())
        assertEquals(500_000_000, sample.roundTripNanos)
        assertEquals(0, sample.offsetNanos)
        assertEquals(utc + 750_000_000, sample.utcAtReceiptNanos)
    }
    @Test fun ntpPositiveAndNegativeOffsets() {
        for (offset in listOf(-1_000_000_000L, 1_000_000_000L)) {
            assertEquals(offset, parse(ntpResponse(request, utc + 250_000_000 + offset, utc + 500_000_000 + offset)).offsetNanos)
        }
    }
    @Test fun ntpRejectsModeVersionLeapAndKissOfDeath() {
        for (flags in listOf(0x23, 0x14, 0xe4)) rejected { it[0] = flags.toByte() }
        for (stratum in listOf(0, 16, 255)) rejected { it[1] = stratum.toByte() }
    }
    @Test fun ntpRejectsWrongOriginZeroAndFutureReference() {
        rejected { it[24] = (it[24].toInt() xor 1).toByte() }
        rejected { it.fill(0, 32, 40) }
        rejected { it.fill(0, 40, 48) }
        rejected { it.fill(0, 16, 24) }
        rejected { NtpPacket.putTimestamp(it, 16, utc + 1_000_000_000) }
    }
    @Test fun ntpRejectsInvalidProcessingAndRootMetrics() {
        rejected { NtpPacket.putTimestamp(it, 40, utc) }
        rejected { NtpPacket.putTimestamp(it, 40, utc + 2_000_000_000) }
        rejected { it[4] = 127 }
        rejected { it[8] = 127 }
        rejected { it[3] = 10 }
    }
    @Test fun ntpRejectsLengthsHighDelayRollbackAndOverflow() {
        assertThrows(IllegalArgumentException::class.java) { parse(response().copyOf(49)) }
        assertThrows(IllegalArgumentException::class.java) { parse(response().copyOf(20)) }
        assertThrows(IllegalArgumentException::class.java) { NtpPacket.parse(request, response(), utc, 0, 2_000_000_001) }
        assertThrows(IllegalArgumentException::class.java) { NtpPacket.parse(request, response(), utc, 3, 2) }
        assertThrows(ArithmeticException::class.java) { NtpPacket.parse(request, response(), Long.MAX_VALUE, 0, 750_000_000) }
    }
    @Test fun ntpEraRolloverAndSubsecondRoundTrip() {
        for (date in listOf("2036-02-07T06:28:15Z", "2036-02-07T06:28:17Z", "2026-01-01T00:00:00Z")) {
            val time = Instant.parse(date).epochSecond * 1_000_000_000 + 123_456_789
            val bytes = ByteArray(48); NtpPacket.putTimestamp(bytes, 40, time)
            assertTrue(NtpPacket.timestamp(bytes, 40, time) in time - 1..time)
        }
    }

    private fun headers() = mutableMapOf<String?, List<String>>("Date" to listOf("Thu, 01 Jan 2026 00:00:00 GMT"), "Cache-Control" to listOf("no-store"))
    @Test fun httpDateIsSecondQuantizedWithUnknownAccuracy() {
        val sample = HttpDateSample.parse("http-date:test", 200, headers(), 10, 100_000_010)
        assertEquals(utc + 50_000_000, sample.anchor.serverUtcEpochNanos)
        assertEquals(100_000_010, sample.anchor.localMonotonicNanos)
        assertEquals(1_000_000_000, sample.resolutionNanos)
        assertEquals(TimeSourceType.HTTP_ESTIMATE, sample.sourceType)
        val state = PlatformTimeState(PlatformId.JD, lastSuccess = sample)
        assertFalse(state.accuracyVerified); assertNull(sample.estimatedUncertaintyNanos); assertNull(state.measuredErrorNanos)
    }
    @Test fun httpRejectsCachedAndIntermediateResponses() {
        for ((key, value) in listOf("Age" to "1", "Age" to "unknown", "Via" to "proxy", "Warning" to "110 stale",
            "X-Cache" to "HIT", "CF-Cache-Status" to "STALE", "Cache-Control" to "max-age=3600")) {
            assertThrows(IllegalArgumentException::class.java) { HttpDateSample.parse("test", 200, headers().apply { put(key, listOf(value)) }, 0, 1) }
        }
    }
    @Test fun httpRejectsMissingDuplicateInvalidDateStatusAndLatency() {
        for (dates in listOf(emptyList(), listOf("bad date"), headers()["Date"]!! + headers()["Date"]!!)) {
            assertThrows(RuntimeException::class.java) { HttpDateSample.parse("test", 200, headers().apply { put("Date", dates) }, 0, 1) }
        }
        for (status in listOf(301, 403, 429, 500)) assertThrows(IllegalArgumentException::class.java) { HttpDateSample.parse("test", status, headers(), 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { HttpDateSample.parse("test", 200, headers(), 0, 2_000_000_001) }
    }
    @Test fun httpHeadersAreCaseInsensitiveAndAgeZeroAllowed() {
        val sample = HttpDateSample.parse("test", 200, mapOf("date" to headers()["Date"]!!, "cache-control" to listOf("max-age=0"), "age" to listOf("0")), 0, 0)
        assertEquals(utc, sample.anchor.serverUtcEpochNanos)
    }
}

internal fun ntpResponse(request: ByteArray, receiveUtc: Long, transmitUtc: Long) = ByteArray(48).apply {
    this[0] = 0x24; this[1] = 2; this[3] = -20
    request.copyInto(this, 24, 40, 48)
    NtpPacket.putTimestamp(this, 16, receiveUtc - 1_000_000_000)
    NtpPacket.putTimestamp(this, 32, receiveUtc)
    NtpPacket.putTimestamp(this, 40, transmitUtc)
}
