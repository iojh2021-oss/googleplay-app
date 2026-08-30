package com.iojh.blindphoneradar

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
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
        radar = BleRadar(this) { observations -> runOnUiThread { render(observations) } }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        status = TextView(this).apply { textSize = 20f; text = "آماده اسکن" }
        val start = Button(this).apply { text = "شروع تشخیص گوشی‌های اطراف"; setOnClickListener { toggle() } }
        val clear = Button(this).apply { text = "پاک‌سازی حافظه موقت"; setOnClickListener { radar.clear(); results.text = "" } }
        val capabilities = TextView(this).apply {
            textSize = 14f
            text = CapabilityProbe(this@MainActivity).summary()
            setPadding(0, 12, 0, 12)
        }
        results = TextView(this).apply { textSize = 18f; setPadding(0, 16, 0, 16) }
        root.addView(status)
        root.addView(capabilities)
        root.addView(start)
        root.addView(clear)
        root.addView(ScrollView(this).apply { addView(results) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun toggle() {
        if (running) stopRadar() else requestAndStart()
    }

    private fun requestAndStart() {
        if (!BluetoothAdapter.getDefaultAdapter().isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        val permissions = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= 31) permissions += Manifest.permission.BLUETOOTH_SCAN
        if (android.os.Build.VERSION.SDK_INT >= 31) permissions += Manifest.permission.BLUETOOTH_CONNECT
        if (android.os.Build.VERSION.SDK_INT <= 30) permissions += Manifest.permission.ACCESS_FINE_LOCATION
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 100) else startRadar()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startRadar()
        else status.text = "مجوز Bluetooth برای اسکن لازم است"
    }

    private fun startRadar() {
        running = true
        radar.start()
        status.text = "در حال اسکن چنددستگاهی…"
    }

    private fun stopRadar() {
        running = false
        radar.stop()
        status.text = "اسکن متوقف شد"
    }

    private fun render(items: List<DeviceObservation>) {
        if (items.isEmpty()) {
            results.text = "هیچ دستگاه BLE قابل مشاهده‌ای پیدا نشد.\n\nاین سیستم فقط دستگاه‌هایی را می‌بیند که در آن لحظه سیگنال قابل اسکن دارند."
            return
        }
        val phoneCandidates = items.count { it.phoneCandidateScore >= 50 }
        val text = buildString {
            append("${items.size} دستگاه در حال track | ${phoneCandidates} کاندید گوشی\n")
            append("حداکثر tracker فعال: 64 دستگاه\n\n")
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
        val nearest = items.firstOrNull { it.phoneCandidateScore >= 50 } ?: items.firstOrNull() ?: return
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
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }
}