package com.iojh.blindphoneradar

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

class CapabilityProbe(private val context: Context) {
    fun summary(): String {
        val pm = context.packageManager
        val ble = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val uwb = if (Build.VERSION.SDK_INT >= 31) pm.hasSystemFeature(PackageManager.FEATURE_UWB) else false
        val rtt = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
        return buildString {
            append("BLE: ").append(if (ble) "available" else "unavailable")
            append("\nUWB: ").append(if (uwb) "available" else "not available")
            append("\nWi-Fi RTT: ").append(if (rtt) "available" else "not available")
            append("\nAndroid: ").append(Build.VERSION.SDK_INT)
        }
    }
}
