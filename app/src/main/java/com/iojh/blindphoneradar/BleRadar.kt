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

/** Stable BLE scanner for dense environments. All state is session-only. */
class BleRadar(
    context: Context,
    private val onUpdate: (List<DeviceObservation>) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner
    private val tracker = MultiDeviceTracker(maxTrackedDevices = 128, staleAfterMs = 9_000L, historySize = 40)
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
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try { ingest(result) }
            catch (_: SecurityException) { fail("مجوز Bluetooth برای خواندن نتیجه اسکن کافی نیست") }
            catch (t: Throwable) { fail("خطا هنگام پردازش دستگاه Bluetooth: ${t.javaClass.simpleName}") }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            try { results.forEach(::ingest) }
            catch (_: SecurityException) { fail("مجوز Bluetooth برای خواندن نتیجه اسکن کافی نیست") }
            catch (t: Throwable) { fail("خطا هنگام پردازش نتایج Bluetooth: ${t.javaClass.simpleName}") }
        }
        override fun onScanFailed(errorCode: Int) {
            val reason = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "اسکن از قبل فعال است"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "ثبت اسکنر Bluetooth توسط Android رد شد"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "خطای داخلی Bluetooth Android"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "این گوشی قابلیت BLE Scan لازم را ندارد"
                else -> "اسکن Bluetooth با خطای $errorCode متوقف شد"
            }
            fail(reason)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (adapter == null) { fail("این گوشی Bluetooth ندارد"); return }
        if (!adapter.isEnabled) { fail("Bluetooth خاموش است"); return }
        val s = try { scanner } catch (_: SecurityException) {
            fail("مجوز Bluetooth برای دسترسی به اسکنر کافی نیست"); return
        }
        if (s == null) { fail("اسکنر BLE روی این گوشی در دسترس نیست"); return }

        // Do not force PHY_LE_ALL_SUPPORTED: some vendor Bluetooth stacks reject it
        // even though ordinary BLE scanning is supported.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()
        try {
            s.startScan(null, settings, callback)
            handler.post(publishRunnable)
        } catch (_: SecurityException) { fail("مجوز Bluetooth برای شروع اسکن کافی نیست")
        } catch (_: IllegalArgumentException) { fail("تنظیمات اسکن Bluetooth توسط این گوشی پشتیبانی نمی‌شود")
        } catch (_: IllegalStateException) { fail("Bluetooth فعلاً آماده اسکن نیست") }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running.getAndSet(false)) return
        handler.removeCallbacks(publishRunnable)
        try { scanner?.stopScan(callback) } catch (_: SecurityException) {} catch (_: IllegalStateException) {}
    }

    fun clear() { tracker.clear(); onUpdate(emptyList()) }

    private fun ingest(result: ScanResult) {
        val ephemeral = sessionKey(result.device.address)
        tracker.update(
            key = ephemeral,
            rssi = result.rssi,
            txPower = txPower(result),
            name = null,
            phoneScore = phoneCandidateScore(result)
        )
    }

    private fun publishSnapshot() {
        onUpdate(tracker.snapshot().map {
            DeviceObservation(it.key, it.displayLabel, it.rssi, it.txPower, it.firstSeenMs,
                it.lastSeenMs, it.samples, it.phoneCandidateScore, it.estimate)
        })
    }

    private fun fail(message: String) {
        running.set(false)
        handler.removeCallbacks(publishRunnable)
        onError(message)
    }

    private fun sessionKey(address: String): String =
        UUID.nameUUIDFromBytes((sessionSalt + address).toByteArray()).toString().take(10)

    private fun phoneCandidateScore(result: ScanResult): Int {
        val record = result.scanRecord ?: return 25
        var score = 30
        if (record.serviceUuids?.isNotEmpty() == true) score += 5
        if (record.manufacturerSpecificData.size() > 0) score += 5
        return score.coerceIn(0, 95)
    }

    @SuppressLint("MissingPermission")
    private fun txPower(result: ScanResult): Int? =
        if (Build.VERSION.SDK_INT >= 26) result.txPower.takeIf { it in -100..20 } else null

    companion object { private val sessionSalt = UUID.randomUUID().toString() }
}
