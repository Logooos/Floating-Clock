package io.github.floatingclock.time

import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlin.math.ceil
import kotlin.math.pow

/** RFC 5905 client packet validation and four-timestamp arithmetic; no Android or socket dependency. */
object NtpPacket {
    private const val EPOCH_SECONDS = 2_208_988_800L
    private const val ERA = 4_294_967_296L
    private val random = SecureRandom()
    data class Sample(val utcAtReceiptNanos: Long, val offsetNanos: Long, val roundTripNanos: Long, val resolutionNanos: Long)

    fun request(localUtcNanos: Long): ByteArray = ByteArray(48).apply {
        this[0] = 0x23 // LI=0, VN=4, client mode=3.
        putTimestamp(this, 40, localUtcNanos)
        // Unpredictable low fraction bits improve request correlation; <60 ns, not authentication.
        this[47] = random.nextInt(256).toByte()
    }

    fun putTimestamp(packet: ByteArray, at: Int, unixNanos: Long) {
        val seconds = Math.floorDiv(unixNanos, 1_000_000_000L) + EPOCH_SECONDS
        val fraction = Math.floorMod(unixNanos, 1_000_000_000L) * ERA / 1_000_000_000L
        ByteBuffer.wrap(packet).putInt(at, seconds.toInt()).putInt(at + 4, fraction.toInt())
    }

    /** NTP era is unfolded nearest the initial local date (must be within 68 years). */
    fun timestamp(packet: ByteArray, at: Int, referenceUnixNanos: Long): Long {
        val buffer = ByteBuffer.wrap(packet)
        val seconds = buffer.getInt(at).toLong() and 0xffffffffL
        val fraction = buffer.getInt(at + 4).toLong() and 0xffffffffL
        val reference = Math.floorDiv(referenceUnixNanos, 1_000_000_000L) + EPOCH_SECONDS
        val era = Math.floorDiv(reference - seconds + ERA / 2, ERA)
        val unixSeconds = Math.addExact(seconds, Math.multiplyExact(era, ERA)) - EPOCH_SECONDS
        return Math.addExact(Math.multiplyExact(unixSeconds, 1_000_000_000L), fraction * 1_000_000_000L / ERA)
    }

    fun parse(request: ByteArray, response: ByteArray, localUtcNanos: Long, start: Long, end: Long): Sample {
        require(request.size == 48 && response.size == 48) { "Unsupported NTP packet length/extensions" }
        val flags = response[0].toInt() and 255
        require(flags and 7 == 4 && (flags shr 3 and 7) in 3..4 && flags shr 6 != 3) { "Invalid mode, version or leap state" }
        require((response[1].toInt() and 255) in 1..15) { "Unsynchronized server or Kiss-o'-Death" }
        require((0..7).all { response[24 + it] == request[40 + it] }) { "Origin timestamp mismatch" }
        require((32..39).any { response[it] != 0.toByte() } && (40..47).any { response[it] != 0.toByte() })
        val buffer = ByteBuffer.wrap(response)
        require(buffer.getInt(4).toLong() in -65_536L..655_360L) { "Excessive root delay" }
        require((buffer.getInt(8).toLong() and 0xffffffffL) <= 655_360L) { "Excessive root dispersion" }
        val elapsed = checkedElapsed(start, end)
        require(elapsed <= 2_000_000_000L) { "High round-trip time" }
        val t2 = timestamp(response, 32, localUtcNanos)
        val t3 = timestamp(response, 40, localUtcNanos)
        require((16..23).any { response[it] != 0.toByte() } && timestamp(response, 16, localUtcNanos) <= t3) { "Invalid reference timestamp" }
        val processing = Math.subtractExact(t3, t2)
        require(processing >= 0 && processing <= elapsed) { "Invalid server processing interval" }
        val rtt = elapsed - processing
        val t4 = Math.addExact(localUtcNanos, elapsed)
        // Equivalent to ((t2-t1)+(t3-t4))/2, avoiding addition of large epoch values.
        val atReceipt = Math.addExact(t3, rtt / 2)
        val offset = Math.subtractExact(atReceipt, t4)
        val resolution = ceil(2.0.pow(response[3].toInt()) * 1_000_000_000.0)
        require(resolution.isFinite() && resolution in 1.0..1_000_000_000.0) { "Invalid advertised precision" }
        return Sample(atReceipt, offset, rtt, resolution.toLong())
    }
}
