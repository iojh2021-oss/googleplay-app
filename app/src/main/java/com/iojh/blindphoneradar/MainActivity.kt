package com.iojh.blindphoneradar

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener, SensorEventListener {
    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var cellular: TextView
    private lateinit var map: OsmRadarMapView
    private lateinit var startButton: Button
    private lateinit var tts: TextToSpeech
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var running = false
    private var lastItems: List<DeviceObservation> = emptyList()
    private var latitude: Double? = null
    private var longitude: Double? = null
    private var lastSpokenKey = ""
    private var lastSpokenAt = 0L
    private var receiverRegistered = false

    private val radarReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                RadarKeepAliveService.ACTION_UPDATE -> {
                    val encoded = intent.getStringArrayListExtra(RadarKeepAliveService.EXTRA_ITEMS) ?: arrayListOf()
                    val items = encoded.mapNotNull(::decodeObservation)
                    lastItems = items
                    running = true
                    status.text = "● رادار فعال است — ${items.size} دستگاه در tracker"
                    render(items)
                }
                RadarKeepAliveService.ACTION_STATUS -> {
                    status.text = intent.getStringExtra(RadarKeepAliveService.EXTRA_STATUS) ?: "وضعیت نامشخص"
                }
            }
        }
    }

    private var firstLocationFix = true
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latitude = location.latitude
            longitude = location.longitude
            map.setUserLocation(location.latitude, location.longitude, firstLocationFix)
            firstLocationFix = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Blind Phone Radar"
        tts = TextToSpeech(this, this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        registerRadarReceiver()
        requestLocationUpdatesIfAllowed()
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onStop() {
        unregisterRadarReceiver()
        try { locationManager.removeUpdates(locationListener) } catch (_: Throwable) {}
        sensorManager.unregisterListener(this)
        super.onStop()
    }

    private fun buildUi() {
        val root = FrameLayout(this)

        map = OsmRadarMapView(this)
        root.addView(map, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(28), dp(14), dp(28), dp(14))
            text = "آماده رادار"
            background = pillDrawable(Color.WHITE)
            elevation = 14f
            setOnClickListener { showDeviceDetailsDialog() }
        }
        root.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(28)
        })

        val infoButton = circularButton("ⓘ") { showCapabilitiesDialog() }
        root.addView(infoButton, FrameLayout.LayoutParams(dp(56), dp(56)).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = dp(28); leftMargin = dp(20)
        })

        val clearButton = circularButton("⟲") {
            lastItems = emptyList()
            map.setTargets(emptyList())
            results.text = ""
        }
        root.addView(clearButton, FrameLayout.LayoutParams(dp(56), dp(56)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(28); rightMargin = dp(20)
        })

        val locateButton = circularButton("◎") {
            val lat = latitude; val lon = longitude
            if (lat != null && lon != null) map.setUserLocation(lat, lon, true)
        }
        root.addView(locateButton, FrameLayout.LayoutParams(dp(56), dp(56)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = dp(230); rightMargin = dp(20)
        })

        val sheet = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(28))
            background = sheetDrawable()
            elevation = 24f
        }
        cellular = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#8A97A2"))
            text = "شبکه سیم‌کارت: برای بررسی ضربه بزنید"
            setOnClickListener { readCellular() }
        }
        results = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(10), 0, dp(10))
            maxLines = 6
        }
        val resultsScroll = ScrollView(this).apply { addView(results) }
        startButton = Button(this).apply {
            text = "▶  شروع رادار"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = startButtonDrawable(active = false)
            setPadding(0, dp(26), 0, dp(26))
            isAllCaps = false
            setOnClickListener { toggle() }
        }

        sheet.addView(cellular)
        sheet.addView(resultsScroll, android.widget.LinearLayout.LayoutParams(-1, dp(150)))
        sheet.addView(startButton, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        root.addView(sheet, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })

        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun pillDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(28).toFloat()
        setColor(color)
    }

    private fun sheetDrawable(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = floatArrayOf(
            dp(24).toFloat(), dp(24).toFloat(),
            dp(24).toFloat(), dp(24).toFloat(),
            0f, 0f, 0f, 0f
        )
        setColor(Color.WHITE)
    }

    private fun startButtonDrawable(active: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setColor(if (active) Color.parseColor("#E53935") else Color.parseColor("#2E9E5B"))
    }

    private fun circularButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#1A1A1A"))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        elevation = 14f
        setOnClickListener { onClick() }
    }

    private fun showDeviceDetailsDialog() {
        val message = if (lastItems.isEmpty()) {
            "هنوز دستگاهی شناسایی نشده است."
        } else {
            buildString {
                lastItems.take(30).forEachIndexed { index, item ->
                    val d = item.estimate
                    append("${index + 1}. ${item.displayLabel}\n")
                    if (d.meters != null) append("   فاصله تقریبی: %.1f m (اطمینان ${d.confidence}%%)\n".format(Locale.US, d.meters))
                    else append("   فاصله نامشخص\n")
                    append("\n")
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("دستگاه\u200cهای شناسایی\u200cشده")
            .setMessage(message)
            .setPositiveButton("باشه", null)
            .show()
    }

    private fun showCapabilitiesDialog() {
        AlertDialog.Builder(this)
            .setTitle("قابلیت‌های گوشی")
            .setMessage(CapabilityProbe(this).summary())
            .setPositiveButton("باشه", null)
            .show()
    }

    private fun toggle() {
        if (running) stopRadar() else requestAndStart()
    }

    private fun requestAndStart() {
        val manager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = try { manager?.adapter } catch (_: SecurityException) { null }
        if (adapter == null) { status.text = "Bluetooth روی این گوشی در دسترس نیست"; return }

        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.BLUETOOTH_SCAN
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.ACCESS_FINE_LOCATION
        if (missing.isNotEmpty()) {
            requestPermissions(missing.distinct().toTypedArray(), REQUEST_RADAR_PERMISSIONS)
            return
        }

        try {
            if (!adapter.isEnabled) {
                startActivityForResult(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT)
                status.text = "Bluetooth را روشن کنید"
                return
            }
        } catch (_: SecurityException) {
            status.text = "مجوز Bluetooth کافی نیست"
            return
        }
        startRadar()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            requestAndStart()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RADAR_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) requestAndStart()
            else status.text = "برای رادار واقعی، Bluetooth و موقعیت مکانی لازم است"
        } else if (requestCode == REQUEST_CELLULAR_PERMISSION) readCellular()
    }

    private fun startRadar() {
        try {
            val intent = Intent(this, RadarKeepAliveService::class.java).setAction(RadarKeepAliveService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            running = true
            status.text = "● رادار فعال شد — تا توقف دستی ادامه دارد"
            startButton.text = "■  توقف رادار"
            startButton.background = startButtonDrawable(active = true)
            requestLocationUpdatesIfAllowed()
        } catch (t: Throwable) {
            running = false
            status.text = "شروع رادار ناموفق بود: ${t.javaClass.simpleName}"
        }
    }

    private fun stopRadar() {
        running = false
        try {
            startService(Intent(this, RadarKeepAliveService::class.java).setAction(RadarKeepAliveService.ACTION_STOP))
        } catch (_: Throwable) {}
        status.text = "■ رادار متوقف شد"
        startButton.text = "▶  شروع رادار"
        startButton.background = startButtonDrawable(active = false)
    }

    private fun registerRadarReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(RadarKeepAliveService.ACTION_UPDATE)
            addAction(RadarKeepAliveService.ACTION_STATUS)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(radarReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(radarReceiver, filter)
        receiverRegistered = true
    }

    private fun unregisterRadarReceiver() {
        if (!receiverRegistered) return
        try { unregisterReceiver(radarReceiver) } catch (_: Throwable) {}
        receiverRegistered = false
    }

    private fun requestLocationUpdatesIfAllowed() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, locationListener)
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(locationListener::onLocationChanged)
            }
        } catch (_: SecurityException) {}
    }

    private fun readCellular() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_CELLULAR_PERMISSION)
            return
        }
        cellular.text = CellularDiagnostics(this).read().summary
    }

    private fun render(items: List<DeviceObservation>) {
        map.setTargets(items)
        if (items.isEmpty()) {
            results.text = "در این لحظه BLE advertisement قابل مشاهده‌ای نیست.\nاین به معنی نبودن گوشی در اطراف نیست؛ دستگاه مقابل باید رادیوی BLE قابل مشاهده داشته باشد."
            return
        }
        val phoneCandidates = items.count { it.phoneCandidateScore >= 50 }
        results.text = buildString {
            append("${items.size} دستگاه track موقت | ${phoneCandidates} کاندید گوشی\n")
            append("اسکن: پیوسته | سقف tracker: 128 | داده دائمی: خیر\n\n")
            items.take(30).forEachIndexed { index, item ->
                val d = item.estimate
                append("${index + 1}. ${item.displayLabel}\n")
                if (d.meters != null) append("   حدود %.1f m | بازه %.1f–%.1f m | اطمینان ${d.confidence}%%\n".format(Locale.US, d.meters, d.minMeters, d.maxMeters))
                else append("   فاصله نامشخص\n")
                append("   RSSI ${item.rssi} | امتیاز گوشی ${item.phoneCandidateScore}% | ${d.method}\n\n")
            }
        }
        speakNearest(items)
    }

    private fun decodeObservation(value: String): DeviceObservation? {
        val p = value.split('\t')
        if (p.size < 10) return null
        return try {
            val meters = p[5].takeIf { it.isNotEmpty() }?.toDouble()
            DeviceObservation(
                key = p[0],
                displayLabel = p[1],
                rssi = p[2].toInt(),
                estimate = DistanceEstimate(
                    meters = meters,
                    minMeters = p[6].toDouble(),
                    maxMeters = p[7].toDouble(),
                    confidence = p[8].toInt(),
                    method = p[9]
                ),
                phoneCandidateScore = p[3].toInt(),
                lastSeenMs = p[4].toLong()
            )
        } catch (_: Throwable) { null }
    }

    private fun speakNearest(items: List<DeviceObservation>) {
        val nearest = items.firstOrNull { it.phoneCandidateScore >= 50 } ?: return
        val d = nearest.estimate.meters ?: return
        if (nearest.estimate.confidence < 45) return
        val now = System.currentTimeMillis()
        val band = when {
            d < 1.0 -> "کمتر از یک متر"
            d < 2.0 -> "حدود یک تا دو متر"
            d < 3.5 -> "حدود دو تا سه متر"
            d < 5.5 -> "حدود چهار تا پنج متر"
            else -> "بیش از پنج متر"
        }
        val key = "${nearest.key}:$band"
        if (key != lastSpokenKey || now - lastSpokenAt > 5000L) {
            tts.speak("نزدیک‌ترین گوشی احتمالی، $band", TextToSpeech.QUEUE_FLUSH, null, "nearest")
            lastSpokenKey = key
            lastSpokenAt = now
        }
    }

    override fun onInit(statusCode: Int) {
        if (statusCode == TextToSpeech.SUCCESS) tts.language = Locale("fa", "IR")
    }

    override fun onDestroy() {
        try { tts.stop(); tts.shutdown() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val azimuth = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
        map.setHeading(azimuth)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val REQUEST_RADAR_PERMISSIONS = 100
        private const val REQUEST_ENABLE_BT = 101
        private const val REQUEST_CELLULAR_PERMISSION = 200
    }
}
