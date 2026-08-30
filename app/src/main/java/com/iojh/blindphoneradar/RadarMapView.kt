package com.iojh.blindphoneradar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.max

/**
 * Offline-first radar map. It deliberately does not invent a bearing for BLE RSSI.
 * Targets are represented by measured range rings; a target is placed on a map only
 * when a future ranging provider supplies a real angle.
 */
class RadarMapView(context: Context) : View(context) {
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val user = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; typeface = Typeface.DEFAULT_BOLD }
    private var targets: List<DeviceObservation> = emptyList()
    private var latitude: Double? = null
    private var longitude: Double? = null

    fun setData(items: List<DeviceObservation>, lat: Double?, lon: Double?) {
        targets = items.sortedBy { it.estimate.meters ?: Double.MAX_VALUE }.take(12)
        latitude = lat
        longitude = lon
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = max(80f, minOf(w, h) * 0.38f)

        canvas.drawColor(0xFFF4F7FA.toInt())
        grid.color = 0xFFDCE3EA.toInt()
        grid.strokeWidth = 1f
        for (i in -4..4) {
            val x = cx + i * radius / 2.0f
            canvas.drawLine(x, 0f, x, h, grid)
            val y = cy + i * radius / 2.0f
            canvas.drawLine(0f, y, w, y, grid)
        }

        ring.color = 0xFF9FB7C9.toInt()
        val scales = floatArrayOf(0.25f, 0.5f, 0.75f, 1f)
        scales.forEachIndexed { index, scale ->
            canvas.drawCircle(cx, cy, radius * scale, ring)
            text.color = 0xFF526575.toInt()
            text.textSize = 22f
            canvas.drawText("${(index + 1) * 2}m", cx + 8f, cy - radius * scale + 24f, text)
        }

        user.color = 0xFF1769AA.toInt()
        canvas.drawCircle(cx, cy, 16f, user)
        text.color = 0xFF17324D.toInt()
        text.textSize = 28f
        canvas.drawText("YOU", cx - 28f, cy + 48f, text)

        text.color = 0xFF263746.toInt()
        text.textSize = 22f
        val loc = if (latitude != null && longitude != null) {
            "GPS %.5f, %.5f".format(latitude, longitude)
        } else "GPS: در حال دریافت موقعیت"
        canvas.drawText(loc, 20f, 32f, text)

        var y = 64f
        if (targets.isEmpty()) {
            canvas.drawText("رادار: هدف قابل مشاهده نیست", 20f, y, text)
        } else {
            targets.forEachIndexed { index, item ->
                val d = item.estimate.meters
                val label = if (d != null) "T${index + 1}  ~%.1fm  %d%%".format(d, item.estimate.confidence)
                else "T${index + 1}  فاصله نامشخص"
                canvas.drawText(label, 20f, y, text)
                y += 30f
            }
        }

        text.textSize = 18f
        text.color = 0xFF667784.toInt()
        canvas.drawText("جهت هدف BLE مشخص نیست؛ موقعیت جعلی روی نقشه نمایش داده نمی‌شود.", 20f, h - 22f, text)
    }
}
