package com.iojh.blindphoneradar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-density BLE radar for crowded environments.
 * Tracks up to 64 simultaneous ephemeral devices and emits throttled snapshots.
 */
class BleRadar(
    context: Context,
    private val onUpdate: (List<DeviceObservation>) -> Unit
) {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner
    private val tracker = MultiDeviceTracker(maxTrackedDevices = 64, staleAfterMs = 9_000L, historySize = 40)
    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    private val publishRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            publishSnapshot()
            handler.postDelayed(this, 250L)
        }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = ingest(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach(::ingest) }
        override fun onScanFailed(errorCode: Int) {
            running.set(false)
            handler.removeCallbacks(publishRunnable)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val s = scanner ?: run { running.set(false); return }
        val builder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }
        s.startScan(null, builder.build(), callback)
        handler.post(publishRunnable)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running.getAndSet(false)) return
        handler.removeCallbacks(publishRunnable)
        scanner?.stopScan(callback)
    }

    fun clear() {
        tracker.clear()
        onUpdate(emptyList())
    }

    private fun ingest(result: ScanResult) {
        val ephemeral = sessionKey(result.device.address)
        tracker.update(
            key = ephemeral,
            rssi = result.rssi,
            txPower = txPower(result),
            name = result.device.name,
            phoneScore = phoneCandidateScore(result)
        )
    }

    private fun publishSnapshot() {
        // Do not discard low-confidence devices here: in a crowded street an unnamed phone
        // is still a useful candidate. The score is exposed so the UI/voice layer can decide
        // how aggressively to announce it.
        val list = tracker.snapshot().map {
            DeviceObservation(
                key = it.key,
                displayLabel = it.displayLabel,
                rssi = it.rssi,
                txPower = it.txPower,
                firstSeenMs = it.firstSeenMs,
                lastSeenMs = it.lastSeenMs,
                samples = it.samples,
                phoneCandidateScore = it.phoneCandidateScore,
                estimate = it.estimate
            )
        }
        onUpdate(list)
    }

    private fun sessionKey(address: String): String =
        UUID.nameUUIDFromBytes((sessionSalt + address).toByteArray()).toString().take(10)

    /** Conservative heuristic only; BLE advertisements do not prove that a device is a phone. */
    private fun phoneCandidateScore(result: ScanResult): Int {
        val n = result.device.name?.lowercase().orEmpty()
        val hints = listOf(
            "iphone", "android", "pixel", "galaxy", "phone", "redmi", "xiaomi",
            "oneplus", "huawei", "oppo", "vivo", "motorola", "nothing", "samsung"
        )
        var score = if (hints.any(n::contains)) 85 else 35
        val record = result.scanRecord
        if (record != null && (record.manufacturerSpecificData.size() > 0 || record.serviceUuids?.isNotEmpty() == true)) {
            score += 5
        }
        return score.coerceIn(0, 95)
    }

    @SuppressLint("MissingPermission")
    private fun txPower(result: ScanResult): Int? =
        if (Build.VERSION.SDK_INT >= 26) result.txPower.takeIf { it in -100..20 } else null

    companion object {
        private val sessionSalt = UUID.randomUUID().toString()
    }
}
