package com.iojh.blindphoneradar

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Fuses only measurements that actually exist. It never invents UWB/RTT/Channel
 * Sounding measurements for a passive phone that is not cooperating.
 */
object RangingFusion {
    fun fuse(rssiEstimate: DistanceEstimate, measurements: List<RangingMeasurement>): DistanceEstimate {
        if (measurements.isEmpty()) return rssiEstimate

        val valid = measurements.filter { it.distanceMeters.isFinite() && it.distanceMeters > 0.0 && it.distanceMeters <= 100.0 }
        if (valid.isEmpty()) return rssiEstimate

        var weightedDistance = 0.0
        var totalWeight = 0.0
        valid.forEach { m ->
            val weight = (m.confidence.coerceIn(1, 100) / 100.0) * sourceWeight(m.source)
            weightedDistance += m.distanceMeters * weight
            totalWeight += weight
        }
        if (totalWeight <= 0.0) return rssiEstimate

        val precise = weightedDistance / totalWeight
        val bestConfidence = valid.maxOf { it.confidence.coerceIn(1, 100) }
        val fusedConfidence = if (rssiEstimate.meters == null) {
            bestConfidence
        } else {
            val agreement = valid.map { abs(it.distanceMeters - rssiEstimate.meters) / max(rssiEstimate.meters, 1.0) }.average()
            (bestConfidence * (1.0 - min(agreement, 0.7) * 0.55)).toInt().coerceIn(20, 99)
        }
        val radius = when {
            fusedConfidence >= 90 -> 0.15
            fusedConfidence >= 75 -> 0.30
            fusedConfidence >= 55 -> 0.60
            else -> 1.00
        }
        return DistanceEstimate(
            meters = precise,
            minMeters = max(0.1, precise - radius),
            maxMeters = precise + radius,
            confidence = fusedConfidence,
            quality = "${valid.maxBy { it.confidence }.source.name.lowercase()}_fused"
        )
    }

    private fun sourceWeight(source: RangingSource): Double = when (source) {
        RangingSource.UWB -> 1.00
        RangingSource.BLE_CHANNEL_SOUNDING -> 0.95
        RangingSource.WIFI_RTT -> 0.80
        RangingSource.WIFI_AWARE -> 0.80
        RangingSource.BLE_RSSI -> 0.35
    }
}

enum class RangingSource {
    BLE_RSSI,
    BLE_CHANNEL_SOUNDING,
    UWB,
    WIFI_RTT,
    WIFI_AWARE
}

data class RangingMeasurement(
    val source: RangingSource,
    val distanceMeters: Double,
    val confidence: Int
)
