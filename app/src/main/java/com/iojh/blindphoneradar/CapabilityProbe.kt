package com.iojh.blindphoneradar

import android.content.Context
import android.os.Build

class CapabilityProbe(private val context: Context) {
    fun summary(): String = buildString {
        append(RadarCapabilityDetector.detect(context).userSummary())
        append("\nAndroid: ").append(Build.VERSION.SDK_INT)
        append("\nحالت دقت: passive-safe")
        append("\nUWB/RTT/Channel Sounding فقط در صورت وجود peer سازگار وارد ranging می‌شوند.")
    }
}
