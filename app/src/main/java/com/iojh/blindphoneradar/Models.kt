package com.iojh.blindphoneradar

import kotlin.math.abs
import kotlin.math.pow

data class DistanceEstimate(
    val meters: Double?,
    val minMeters: Double,
    val maxMeters: Double,
    val confidence: Int,
    val method: String
)

data class DeviceObservation(
    val key: String,
    val displayLabel: String,
    val rssi: Int,
    val estimate: DistanceEstimate,
    val phoneCandidateScore: Int,
    val lastSeenMs: Long
)

data class RangingMeasurement(
    val source: RangingSource,
    val distanceMeters: Double,
    val confidence: Int
)

enum class RangingSource {
    BLE_RSSI,
    BLE_CHANNEL_SOUNDING,
    UWB,
    WIFI_RTT,
    WIFI_AWARE
}

object DistanceEstimator {
    fun estimate(samples: List<Int>, txPower: Int? = null): DistanceEstimate {
        if (samples.isEmpty()) return DistanceEstimate(null, 0.0, 0.0, 0, "none")
        val sorted = samples.sorted().map(Int::toDouble)
        val median = sorted[sorted.size / 2]
        val deviations = sorted.map { abs(it - median) }.sorted()
        val mad = deviations[deviations.size / 2]
        val meanAbsDeviation = deviations.average()
        val reference = txPower?.takeIf { it in -100..20 }?.toDouble() ?: -59.0
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
        val confidenceBonus = if (txPower != null) 10.0 else 0.0
        val confidence = (sampleConfidence - stabilityPenalty + confidenceBonus).toInt().coerceIn(10, 95)
        val uncertainty = when {
            confidence >= 80 -> 0.30
            confidence >= 65 -> 0.45
            confidence >= 45 -> 0.70
            else -> 1.00
        }
        return DistanceEstimate(
            meters,
            (meters - uncertainty).coerceAtLeast(0.2),
            (meters + uncertainty).coerceAtMost(50.0),
            confidence,
            "BLE RSSI"
        )
    }
}

object RangingFusion {
    fun fuse(rssiEstimate: DistanceEstimate, measurements: List<RangingMeasurement>): DistanceEstimate {
        val valid = measurements.filter { it.distanceMeters.isFinite() && it.distanceMeters > 0.0 && it.distanceMeters <= 100.0 }
        if (valid.isEmpty()) return rssiEstimate
        var weightedDistance = 0.0
        var totalWeight = 0.0
        valid.forEach { m ->
            val weight = m.confidence.coerceIn(1, 100).toDouble()
            weightedDistance += m.distanceMeters * weight
            totalWeight += weight
        }
        if (totalWeight <= 0.0) return rssiEstimate
        val meters = weightedDistance / totalWeight
        val accuracy = valid.minOf { it.distanceMeters.coerceAtLeast(0.05) * 0.10 }.coerceIn(0.05, 20.0)
        val confidence = valid.maxOf { it.confidence }.coerceIn(1, 99)
        return DistanceEstimate(meters, (meters - accuracy).coerceAtLeast(0.05), (meters + accuracy).coerceAtMost(100.0), confidence, "Ranging fusion")
    }
}
