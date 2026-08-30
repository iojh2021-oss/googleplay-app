package com.iojh.blindphoneradar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BleRadar(context: Context, private val onUpdate: (List<DeviceObservation>) -> Unit) {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner
    private val observations = ConcurrentHashMap<String, MutableObservation>()

    private class MutableObservation(
        val key: String,
        var firstSeenMs: Long,
        var lastSeenMs: Long,
        var rssi: Int,
        var txPower: Int?,
        var name: String?,
        val samples: ArrayDeque<Int> = ArrayDeque()
    )

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            ingest(result)
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::ingest)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val s = scanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()
        s.startScan(null, settings, callback)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        scanner?.stopScan(callback)
    }

    fun clear() {
        observations.clear()
        onUpdate(emptyList())
    }

    private fun ingest(result: ScanResult) {
        val device = result.device
        // Do not retain the real MAC address. Hash only in RAM for this session.
        val ephemeral = sessionKey(device.address)
        val now = SystemClock.elapsedRealtime()
        val item = observations.computeIfAbsent(ephemeral) {
            MutableObservation(ephemeral, now, now, result.rssi, txPower(result), device.name)
        }
        synchronized(item) {
            item.lastSeenMs = now
            item.rssi = result.rssi
            item.txPower = txPower(result) ?: item.txPower
            item.name = result.device.name ?: item.name
            if (item.samples.size >= 25) item.samples.removeFirst()
            item.samples.addLast(result.rssi)
        }
        prune(now)
        publish()
    }

    private fun prune(now: Long) {
        observations.entries.removeIf { now - it.value.lastSeenMs > 8_000L }
    }

    private fun publish() {
        val list = observations.values.map { item ->
            val samples = synchronized(item) { item.samples.toList() }
            val estimate = DistanceEstimator.estimate(samples, item.txPower)
            val score = phoneCandidateScore(item.name)
            DeviceObservation(
                key = item.key,
                displayLabel = if (score >= 50) "Phone candidate" else "BLE device",
                rssi = item.rssi,
                txPower = item.txPower,
                firstSeenMs = item.firstSeenMs,
                lastSeenMs = item.lastSeenMs,
                samples = samples,
                phoneCandidateScore = score,
                estimate = estimate
            )
        }.sortedWith(compareBy<DeviceObservation> { it.estimate.meters ?: Double.MAX_VALUE }.thenByDescending { it.rssi })
        onUpdate(list)
    }

    private fun sessionKey(address: String): String {
        // Per-installation/per-process random namespace: not suitable for tracking a person.
        return UUID.nameUUIDFromBytes((sessionSalt + address).toByteArray()).toString().take(10)
    }

    private fun phoneCandidateScore(name: String?): Int {
        val n = name?.lowercase() ?: return 20
        val hints = listOf("iphone", "android", "pixel", "galaxy", "phone", "redmi", "xiaomi", "oneplus", "huawei", "oppo", "vivo", "motorola", "nothing")
        return if (hints.any(n::contains)) 85 else 35
    }

    @SuppressLint("MissingPermission")
    private fun txPower(result: ScanResult): Int? = if (android.os.Build.VERSION.SDK_INT >= 33) result.txPower.takeIf { it != ScanResult.TX_POWER_NO_PREFERENCE } else null

    companion object {
        private val sessionSalt = UUID.randomUUID().toString()
    }
}
