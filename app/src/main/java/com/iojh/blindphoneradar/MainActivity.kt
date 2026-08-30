package com.iojh.blindphoneradar

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : android.app.Activity(), TextToSpeech.OnInitListener {
    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var cellular: TextView
    private lateinit var radar: BleRadar
    private lateinit var tts: TextToSpeech
    private var running = false
    private var lastSpokenKey = ""
    private var lastSpokenAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Blind Phone Radar"
        tts = TextToSpeech(this, this)
        buildUi()
        radar = BleRadar(
            this,
            onUpdate = { observations -> runOnUiThread { render(observations) } },
            onError = { message -> runOnUiThread {
                running = false
                status.text = message
                stopRadarKeepAlive()
            } }
        )
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        status = TextView(this).apply { textSize = 20f; text = "آماده رادار" }
        val start = Button(this).apply { text = "▶ شروع رادار"; setOnClickListener { toggle() } }
        val clear = Button(this).apply { text = "پاک‌سازی حافظه موقت"; setOnClickListener { radar.clear(); results.text = "" } }
        val cellButton = Button(this).apply { text = "بررسی شبکه سیم‌کارت"; setOnClickListener { readCellular() } }
        val capabilities = TextView(this).apply {
            textSize = 14f
            text = CapabilityProbe(this@MainActivity).summary()
            setPadding(0, 12, 0, 12)
        }
        cellular = TextView(this).apply {
            textSize = 13f
            text = "شبکه سیم‌کارت: برای بررسی دکل‌های قابل مشاهده دکمه بالا را بزنید"
            setPadding(0, 8, 0, 8)
        }
        results = TextView(this).apply { textSize = 18f; setPadding(0, 16, 0, 16) }
        root.addView(status)
        root.addView(capabilities)
        root.addView(start)
        root.addView(cellButton)
        root.addView(clear)
        root.addView(cellular)
        root.addView(ScrollView(this).apply { addView(results) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun toggle() { if (running) stopRadar() else requestAndStart() }

    private fun requestAndStart() {
        val manager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null) { status.text = "Bluetooth روی این گوشی وجود ندارد"; return }

        if (Build.VERSION.SDK_INT >= 31) {
            val missing = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), 100); return }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
            return
        }

        try {
            if (!adapter.isEnabled) {
                startActivityForResult(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), 101)
                status.text = "Bluetooth را روشن کنید"
                return
            }
        } catch (_: SecurityException) {
            status.text = "مجوز دسترسی به وضعیت Bluetooth کافی نیست"
            return
        }
        startRadar()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101) {
            val manager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
            try {
                if (manager?.adapter?.isEnabled == true) startRadar()
                else status.text = "Bluetooth روشن نشد"
            } catch (_: SecurityException) { status.text = "مجوز دسترسی به Bluetooth کافی نیست" }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) requestAndStart()
            else status.text = "مجوز Bluetooth برای شروع رادار لازم است"
        } else if (requestCode == 200) {
            readCellular()
        }
    }

    private fun startRadar() {
        try {
            running = true
            status.text = "● رادار فعال است — اسکن مداوم تا زدن توقف"
            startRadarKeepAlive()
            radar.start()
        } catch (t: Throwable) {
            running = false
            stopRadarKeepAlive()
            status.text = "شروع رادار ناموفق بود: ${t.javaClass.simpleName}"
        }
    }

    private fun stopRadar() {
        running = false
        radar.stop()
        stopRadarKeepAlive()
        status.text = "■ رادار متوقف شد"
    }

    private fun startRadarKeepAlive() {
        try {
            val intent = Intent(this, RadarKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (t: Throwable) {
            status.text = "رادار شروع شد؛ سرویس پس‌زمینه فعال نشد: ${t.javaClass.simpleName}"
        }
    }

    private fun stopRadarKeepAlive() {
        try { stopService(Intent(this, RadarKeepAliveService::class.java)) } catch (_: Throwable) {}
    }

    private fun readCellular() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 200)
            return
        }
        cellular.text = CellularDiagnostics(this).read().summary
    }

    private fun render(items: List<DeviceObservation>) {
        if (items.isEmpty()) {
            results.text = "در این لحظه تبلیغ BLE قابل مشاهده‌ای پیدا نشده است.\n\nاین به معنی نبودن گوشی در اطراف نیست؛ گوشی مقابل باید یک BLE advertisement قابل دریافت ارسال کند."
            return
        }
        val phoneCandidates = items.count { it.phoneCandidateScore >= 50 }
        val text = buildString {
            append("${items.size} دستگاه در حال track | ${phoneCandidates} کاندید گوشی\n")
            append("رادار: اسکن پیوسته | حداکثر tracker: 128\n\n")
            items.take(20).forEachIndexed { index, item ->
                val d = item.estimate
                append("${index + 1}. ${item.displayLabel}\n")
                if (d.meters != null) {
                    append("   حدود %.1f m | بازه %.1f–%.1f m\n".format(Locale.US, d.meters, d.minMeters, d.maxMeters))
                    append("   اطمینان ${d.confidence}% | RSSI ${item.rssi}\n")
                } else append("   فاصله نامشخص | RSSI ${item.rssi}\n")
                append("   امتیاز گوشی ${item.phoneCandidateScore}%\n\n")
            }
            if (items.size > 20) append("… و ${items.size - 20} دستگاه دیگر در tracker فعال هستند.\n")
        }
        results.text = text
        speakNearest(items)
    }

    private fun speakNearest(items: List<DeviceObservation>) {
        val nearest = items.firstOrNull { it.phoneCandidateScore >= 50 } ?: return
        val d = nearest.estimate.meters ?: return
        if (nearest.estimate.confidence < 45) return
        val now = System.currentTimeMillis()
        val roundedBand = when {
            d < 1.0 -> "کمتر از یک متر"
            d < 2.0 -> "حدود یک تا دو متر"
            d < 3.5 -> "حدود دو تا سه متر"
            d < 5.5 -> "حدود چهار تا پنج متر"
            else -> "بیش از پنج متر"
        }
        val key = "${nearest.key}:$roundedBand"
        if (key != lastSpokenKey || now - lastSpokenAt > 5000L) {
            tts.speak("نزدیک‌ترین گوشی احتمالی، $roundedBand", TextToSpeech.QUEUE_FLUSH, null, "nearest")
            lastSpokenKey = key
            lastSpokenAt = now
        }
    }

    override fun onInit(statusCode: Int) {
        if (statusCode == TextToSpeech.SUCCESS) tts.language = Locale("fa", "IR")
    }

    override fun onDestroy() {
        if (::radar.isInitialized) radar.stop()
        stopRadarKeepAlive()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }
}
