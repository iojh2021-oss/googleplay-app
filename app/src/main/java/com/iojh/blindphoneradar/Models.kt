package com.iojh.blindphoneradar

import kotlin.math.max
import kotlin.math.pow

/** A privacy-preserving in-memory observation. No address/name is persisted. */
data class DeviceObservation(
    val key: String,
    val displayLabel: String,
    val rssi: Int,
    val txPower: Int?,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val samples: List<Int>,
    val phoneCandidateScore: Int,
    val estimate: DistanceEstimate
)

data class DistanceEstimate(
    val meters: Double?,
    val minMeters: Double?,
    val maxMeters: Double?,
    val confidence: Int,
    val quality: String
)

object DistanceEstimator {
    fun estimate(samples: List<Int>, txPower: Int?): DistanceEstimate {
        if (samples.isEmpty()) return DistanceEstimate(null, null, null, 0, "unknown")
        val sorted = samples.sorted()
        val median = sorted[sorted.size / 2]
        val spread = sorted.map { kotlin.math.abs(it - median) }.average()
        val reference = txPower?.takeIf { it in -100..20 } ?: -59

        // Log-distance path-loss model. This is deliberately reported as a range,
        // because RSSI is not a precise distance sensor and is affected by bodies,
        // pockets, antenna orientation and reflections.
        val n = when {
            spread < 3.0 -> 2.0
            spread < 7.0 -> 2.4
            else -> 3.0
        }
        val raw = 10.0.pow((reference - median) / (10.0 * n))
        val meters = raw.coerceIn(0.5, 30.0)
        val uncertainty = (0.25 + spread / 18.0).coerceIn(0.25, 0.75)
        val confidence = (100 - spread * 8 - if (samples.size < 5) 25 else 0).toInt().coerceIn(15, 95)
        val quality = when {
            confidence >= 80 -> "good"
            confidence >= 55 -> "fair"
            else -> "low"
        }
        return DistanceEstimate(
            meters,
            max(0.5, meters * (1.0 - uncertainty)),
            meters * (1.0 + uncertainty),
            confidence,
            quality
        )
    }
}
