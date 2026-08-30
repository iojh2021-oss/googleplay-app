package com.iojh.blindphoneradar

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build

/** Reports capabilities available on the user's phone without probing or identifying nearby phones. */
data class RadarCapabilities(
    val ble: Boolean,
    val uwb: Boolean,
    val wifiRtt: Boolean,
    val wifiAware: Boolean,
    val bleExtendedAdvertising: Boolean
) {
    fun userSummary(): String = buildString {
        append("BLE: ").append(if (ble) "OK" else "N/A")
        append("\nUWB: ").append(if (uwb) "OK" else "N/A")
        append("\nWi‑Fi RTT: ").append(if (wifiRtt) "OK" else "N/A")
        append("\nWi‑Fi Aware: ").append(if (wifiAware) "OK" else "N/A")
        append("\nBLE extended advertising: ").append(if (bleExtendedAdvertising) "OK" else "N/A")
    }
}

object RadarCapabilityDetector {
    fun detect(context: Context): RadarCapabilities {
        val pm = context.packageManager
        val bluetooth = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val adapter = bluetooth?.adapter
        return RadarCapabilities(
            ble = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
            uwb = Build.VERSION.SDK_INT >= 31 && pm.hasSystemFeature(PackageManager.FEATURE_UWB),
            wifiRtt = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT),
            wifiAware = Build.VERSION.SDK_INT >= 26 && pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE),
            bleExtendedAdvertising = Build.VERSION.SDK_INT >= 26 && adapter?.isLeExtendedAdvertisingSupported == true
        )
    }
}
