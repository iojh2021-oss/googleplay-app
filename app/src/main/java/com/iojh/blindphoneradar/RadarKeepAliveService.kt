package com.iojh.blindphoneradar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/** Owns the continuous BLE radar so scanning is not tied to Activity lifetime. */
class RadarKeepAliveService : Service() {
    private var radar: BleRadar? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Blind Phone Radar")
            .setContentText("رادار در حال اسکن مداوم دستگاه‌های اطراف است")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else startForeground(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            sendStatus("خطای شروع سرویس رادار: ${t.javaClass.simpleName}")
            stopSelf()
            return
        }

        radar = BleRadar(
            applicationContext,
            onUpdate = { items -> broadcastSnapshot(items) },
            onError = { message -> broadcastStatus(message) }
        )
        startRawCounterTicker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRadar()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START || intent == null) {
            try {
                radar?.start()
                broadcastStatus("● رادار فعال است — اسکن مداوم")
            } catch (t: Throwable) {
                broadcastStatus("شروع رادار ناموفق بود: ${t.javaClass.simpleName}")
                stopSelf()
            }
        }
        return START_STICKY
    }

    private val tickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val rawCounterTick = object : Runnable {
        override fun run() {
            val count = radar?.rawPacketsSeen() ?: 0
            sendBroadcast(Intent(ACTION_RAW_COUNT).setPackage(packageName).putExtra(EXTRA_RAW_COUNT, count))
            tickHandler.postDelayed(this, 1000L)
        }
    }

    private fun startRawCounterTicker() {
        tickHandler.removeCallbacks(rawCounterTick)
        tickHandler.post(rawCounterTick)
    }

    private fun stopRadar() {
        tickHandler.removeCallbacks(rawCounterTick)
        try { radar?.stop() } catch (_: Throwable) {}
        radar = null
    }

    private fun broadcastSnapshot(items: List<DeviceObservation>) {
        val intent = Intent(ACTION_UPDATE).setPackage(packageName)
        val encoded = ArrayList<String>(items.size)
        items.forEach { item ->
            val d = item.estimate
            encoded.add(listOf(
                item.key,
                item.displayLabel,
                item.rssi.toString(),
                item.phoneCandidateScore.toString(),
                item.lastSeenMs.toString(),
                d.meters?.toString() ?: "",
                d.minMeters.toString(),
                d.maxMeters.toString(),
                d.confidence.toString(),
                d.method
            ).joinToString("\t"))
        }
        intent.putStringArrayListExtra(EXTRA_ITEMS, encoded)
        sendBroadcast(intent)
    }

    private fun broadcastStatus(message: String) = sendStatus(message)

    private fun sendStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
    }

    override fun onDestroy() {
        stopRadar()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Radar scanning", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.iojh.blindphoneradar.START"
        const val ACTION_STOP = "com.iojh.blindphoneradar.STOP"
        const val ACTION_UPDATE = "com.iojh.blindphoneradar.UPDATE"
        const val ACTION_STATUS = "com.iojh.blindphoneradar.STATUS"
        const val EXTRA_ITEMS = "items"
        const val EXTRA_STATUS = "status"
        const val ACTION_RAW_COUNT = "com.iojh.blindphoneradar.RAW_COUNT"
        const val EXTRA_RAW_COUNT = "raw_count"
        const val CHANNEL_ID = "radar_scanning"
        const val NOTIFICATION_ID = 1001
    }
}
