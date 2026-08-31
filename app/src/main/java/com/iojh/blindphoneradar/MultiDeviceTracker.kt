package com.iojh.blindphoneradar

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * In-memory tracker for dense BLE environments.
 *
 * Designed for dozens of simultaneous observations rather than a single nearest device.
 * It deliberately stores only an ephemeral session key; no MAC address or identity is kept.
 */
class MultiDeviceTracker(
    private val maxTrackedDevices: Int = 64,
    private val staleAfterMs: Long = 9_000L,
    private val historySize: Int = 40
) {
    private data class Track(
        val key: String,
        var firstSeenMs: Long,
        var lastSeenMs: Long,
        var rssi: Double,
        var txPower: Int?,
        var name: String?,
        var phoneScore: Int,
        var previousDistance: Double? = null,
        var velocityMps: Double = 0.0,
        val rssiHistory: ArrayDeque<Int> = ArrayDeque()
    )

    private val tracks = ConcurrentHashMap<String, Track>()

    @Synchronized
    fun update(
        key: String,
        rssi: Int,
        txPower: Int?,
        name: String?,
        phoneScore: Int,
        nowMs: Long = SystemClock.elapsedRealtime()
    ) {
        val track = tracks[key]
        if (track == null) {
            if (tracks.size >= maxTrackedDevices) evictLeastUseful(nowMs)
            tracks[key] = Track(
                key = key,
                firstSeenMs = nowMs,
                lastSeenMs = nowMs,
                rssi = rssi.toDouble(),
                txPower = txPower,
                name = name,
                phoneScore = phoneScore
            ).also { it.rssiHistory.addLast(rssi) }
            return
        }

        val dt = ((nowMs - track.lastSeenMs).coerceAtLeast(1L)) / 1000.0
        // Adaptive exponential smoothing. Stronger smoothing in unstable RF environments.
        val innovation = abs(rssi - track.rssi)
        val alpha = when {
            innovation > 15 -> 0.12
            innovation > 8 -> 0.20
            else -> 0.32
        }
        track.rssi += alpha * (rssi - track.rssi)
        track.lastSeenMs = nowMs
        track.txPower = txPower ?: track.txPower
        track.name = name ?: track.name
        track.phoneScore = max(track.phoneScore, phoneScore)
        if (track.rssiHistory.size >= historySize) track.rssiHistory.removeFirst()
        track.rssiHistory.addLast(rssi)

        val distance = DistanceEstimator.estimate(track.rssiHistory.toList(), track.txPower).meters
        if (distance != null && track.previousDistance != null) {
            val rawVelocity = (track.previousDistance!! - distance) / dt
            track.velocityMps = (0.7 * track.velocityMps + 0.3 * rawVelocity).coerceIn(-8.0, 8.0)
        }
        if (distance != null) track.previousDistance = distance
    }

    @Synchronized
    fun snapshot(): List<TrackedObservation> {
        val now = SystemClock.elapsedRealtime()
        prune(now)
        return tracks.values.map { t ->
            val samples = t.rssiHistory.toList()
            val estimate = DistanceEstimator.estimate(samples, t.txPower)
            TrackedObservation(
                key = t.key,
                displayLabel = t.name ?: (if (t.phoneScore >= 50) "Phone candidate" else "BLE device"),
                rssi = t.rssi.toInt().coerceIn(-127, 126),
                txPower = t.txPower,
                firstSeenMs = t.firstSeenMs,
                lastSeenMs = t.lastSeenMs,
                samples = samples,
                phoneCandidateScore = t.phoneScore,
                estimate = estimate,
                approachSpeedMps = t.velocityMps,
                approaching = t.velocityMps > 0.20,
                receding = t.velocityMps < -0.20
            )
        }.sortedWith(
            compareBy<TrackedObservation> { it.estimate.meters ?: Double.MAX_VALUE }
                .thenByDescending { it.phoneCandidateScore }
                .thenByDescending { it.rssi }
        )
    }

    @Synchronized
    fun clear() = tracks.clear()

    private fun prune(nowMs: Long) {
        tracks.entries.removeIf { nowMs - it.value.lastSeenMs > staleAfterMs }
    }

    private fun evictLeastUseful(nowMs: Long) {
        prune(nowMs)
        val victim = tracks.values.minWithOrNull(
            compareBy<Track> { it.phoneScore }
                .thenBy { it.lastSeenMs }
                .thenBy { abs(it.rssi) }
        )
        if (victim != null) tracks.remove(victim.key)
    }
}

data class TrackedObservation(
    val key: String,
    val displayLabel: String,
    val rssi: Int,
    val txPower: Int?,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val samples: List<Int>,
    val phoneCandidateScore: Int,
    val estimate: DistanceEstimate,
    val approachSpeedMps: Double,
    val approaching: Boolean,
    val receding: Boolean
)
