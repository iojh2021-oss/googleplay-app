package com.iojh.blindphoneradar

import kotlin.math.abs
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
        val median = if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        } else sorted[sorted.size / 2].toDouble()
        val deviations = sorted.map { abs(it - median) }.sorted()
        val mad = deviations[deviations.size / 2]
        val meanAbsDeviation = deviations.average()
        val reference = txPower?.takeIf { it in -100..20 } ?: -59

        // Passive BLE RSSI is an estimate, not a precision range sensor.
        // The model adapts its path-loss exponent to RF instability and reports an interval.
        val n = when {
            mad <= 2.0 -> 2.0
            mad <= 5.0 -> 2.35
            mad <= 9.0 -> 2.8
            else -> 3.2
        }
        val raw = 10.0.pow((reference - median) / (10.0 * n))
        val meters = raw.coerceIn(0.3, 50.0)

        val sampleConfidence = (15.0 + minOf(samples.size, 20) * 3.5).coerceAtMost(85.0)
        val stabilityPenalty = (mad * 5.5 + meanAbsDeviation * 2.0).coerceAtMost(55.0)
        val confidence = (sampleConfidence - stabilityPenalty + if (txPower != null) 10 else 0.0)
            .toInt().coerceIn(10, 95)
        val uncertainty = when {
            confidence >= 80 -> 0.30
            confidence >= 65 -> 0.45
            confidence >= 45 -> 0.70
            else -> 1.00
        }
        val quality = when {
            confidence >= 80 -> "good"
            confidence >= 60 -> "fair"
            else -> "low"
        }
        return DistanceEstimate(
            meters = meters,
            minMeters = max(0.3, meters * (1.0 - uncertainty)),
            maxMeters = meters * (1.0 + uncertainty),
            confidence = confidence,
            quality = quality
        )
    }
}
