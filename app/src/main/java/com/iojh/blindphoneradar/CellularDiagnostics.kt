package com.iojh.blindphoneradar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoGsm
import android.telephony.CellInfoWcdma
import android.telephony.CellInfoTdscdma
import android.telephony.TelephonyManager

/**
 * Reads only the local phone's modem/cell measurements.
 * It intentionally does NOT claim these measurements belong to nearby phones:
 * Android exposes serving/neighboring cells, not the SIM radio signal of other
 * handsets. Therefore no distance-to-nearby-phone is fabricated from cellular RSSI.
 */
class CellularDiagnostics(private val context: Context) {
    data class Snapshot(
        val supported: Boolean,
        val permissionMissing: Boolean,
        val cellCount: Int,
        val registeredCount: Int,
        val summary: String
    )

    fun read(): Snapshot {
        val hasRadio = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS)
        if (!hasRadio) return Snapshot(false, false, 0, 0, "رادیوی سیم‌کارت روی این گوشی در دسترس نیست")

        val needsLocation = Build.VERSION.SDK_INT >= 29
        val locationMissing = needsLocation && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        if (locationMissing) return Snapshot(true, true, 0, 0, "برای خواندن Cell Info مجوز موقعیت لازم است")

        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val cells = tm.allCellInfo ?: emptyList()
            val registered = cells.count { it.isRegistered }
            val descriptions = cells.take(6).map { describe(it) }
            Snapshot(
                supported = true,
                permissionMissing = false,
                cellCount = cells.size,
                registeredCount = registered,
                summary = buildString {
                    append("شبکه سلولار: ${cells.size} سلول قابل مشاهده؛ $registered سلول سرویس‌دهنده/ثبت‌شده\n")
                    if (descriptions.isNotEmpty()) append(descriptions.joinToString("\n"))
                    append("\n\nاین داده‌ها سیگنال دکل‌های شبکه هستند، نه سیگنال گوشی‌های اطراف؛ از آن‌ها فاصله افراد محاسبه نمی‌شود.")
                }
            )
        } catch (e: SecurityException) {
            Snapshot(true, true, 0, 0, "Android دسترسی به Cell Info را رد کرد")
        } catch (e: Throwable) {
            Snapshot(true, false, 0, 0, "Cell Info در دسترس نیست: ${e.javaClass.simpleName}")
        }
    }

    private fun describe(cell: CellInfo): String {
        val registered = if (cell.isRegistered) "سرویس" else "همسایه"
        return when (cell) {
            is CellInfoLte -> "LTE $registered | ${cell.cellSignalStrength.dbm} dBm"
            is CellInfoNr -> "5G NR $registered | ${cell.cellSignalStrength.dbm} dBm"
            is CellInfoGsm -> "GSM $registered | ${cell.cellSignalStrength.dbm} dBm"
            is CellInfoWcdma -> "WCDMA $registered | ${cell.cellSignalStrength.dbm} dBm"
            is CellInfoTdscdma -> "TD-SCDMA $registered | ${cell.cellSignalStrength.dbm} dBm"
            else -> "Cell $registered"
        }
    }
}
