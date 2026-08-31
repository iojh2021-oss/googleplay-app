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

class BleRadar(
    context: Context,
    private val onUpdate: (List<DeviceObservation>) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner
    private val tracker = MultiDeviceTracker(maxTrackedDevices = 128, staleAfterMs = 12_000L, historySize = 50)
    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var activeScanner: BluetoothLeScanner? = null
    private var consecutiveFailures = 0

    private val publishRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            publishSnapshot()
            handler.postDelayed(this, 250L)
        }
    }
    private val restartRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            restartScanWindow()
            handler.postDelayed(this, SCAN_WINDOW_RESTART_MS)
        }
    }
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try { ingest(result); consecutiveFailures = 0 }
            catch (_: SecurityException) { fail("مجوز Bluetooth برای خواندن نتیجه کافی نیست") }
            catch (t: Throwable) { fail("خطا هنگام پردازش Bluetooth: ${t.javaClass.simpleName}") }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            try { results.forEach(::ingest); consecutiveFailures = 0 }
            catch (_: SecurityException) { fail("مجوز Bluetooth برای خواندن نتایج کافی نیست") }
            catch (t: Throwable) { fail("خطا هنگام پردازش نتایج Bluetooth: ${t.javaClass.simpleName}") }
        }
        override fun onScanFailed(errorCode: Int) {
            consecutiveFailures++
            if (running.get() && consecutiveFailures < 4) {
                handler.postDelayed({ if (running.get()) restartScanWindow() }, 500L * consecutiveFailures)
                return
            }
            val reason = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "اسکن از قبل فعال است"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "ثبت اسکنر Bluetooth توسط Android رد شد"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "خطای داخلی Bluetooth Android"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "این گوشی BLE Scan را پشتیبانی نمی‌کند"
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
        if (!startScanWindow()) return
        handler.post(publishRunnable)
        handler.postDelayed(restartRunnable, SCAN_WINDOW_RESTART_MS)
    }

    @SuppressLint("MissingPermission")
    private fun startScanWindow(): Boolean {
        val s = try { scanner } catch (_: SecurityException) {
            fail("مجوز Bluetooth برای دسترسی به اسکنر کافی نیست"); return false
        }
        if (s == null) { fail("اسکنر BLE روی این گوشی در دسترس نیست"); return false }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()
        return try {
            s.startScan(null, settings, callback)
            activeScanner = s
            true
        } catch (_: SecurityException) { fail("مجوز Bluetooth برای شروع اسکن کافی نیست"); false
        } catch (_: IllegalArgumentException) { fail("تنظیمات اسکن Bluetooth پشتیبانی نمی‌شود"); false
        } catch (_: IllegalStateException) { false }
    }

    @SuppressLint("MissingPermission")
    private fun restartScanWindow() {
        if (!running.get()) return
        try { activeScanner?.stopScan(callback) } catch (_: Throwable) {}
        if (!startScanWindow() && running.get()) handler.postDelayed({ restartScanWindow() }, 1500L)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running.getAndSet(false)) return
        handler.removeCallbacks(publishRunnable)
        handler.removeCallbacks(restartRunnable)
        try { activeScanner?.stopScan(callback) } catch (_: Throwable) {}
        activeScanner = null
    }

    fun clear() { tracker.clear(); onUpdate(emptyList()) }

    private fun ingest(result: ScanResult) {
        val ephemeral = sessionKey(result.device.address)
        tracker.update(ephemeral, result.rssi, txPower(result), null, phoneCandidateScore(result))
    }

    private fun publishSnapshot() {
        onUpdate(tracker.snapshot().map { t ->
            DeviceObservation(t.key, t.displayLabel, t.rssi, t.estimate, t.phoneCandidateScore, t.lastSeenMs)
        })
    }

    private fun fail(message: String) {
        running.set(false)
        handler.removeCallbacks(publishRunnable)
        handler.removeCallbacks(restartRunnable)
        try { activeScanner?.stopScan(callback) } catch (_: Throwable) {}
        activeScanner = null
        onError(message)
    }

    private fun sessionKey(address: String): String = UUID.nameUUIDFromBytes((sessionSalt + address).toByteArray()).toString().take(10)

    private fun phoneCandidateScore(result: ScanResult): Int {
        val record = result.scanRecord ?: return 25
        var score = 30
        if (record.serviceUuids?.isNotEmpty() == true) score += 5
        if (record.manufacturerSpecificData.size() > 0) score += 5
        return score.coerceIn(0, 95)
    }

    @SuppressLint("MissingPermission")
    private fun txPower(result: ScanResult): Int? = if (Build.VERSION.SDK_INT >= 26) result.txPower.takeIf { it in -100..20 } else null

    companion object {
        private const val SCAN_WINDOW_RESTART_MS = 120_000L
        private val sessionSalt = UUID.randomUUID().toString()
    }
}
