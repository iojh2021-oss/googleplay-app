package com.iojh.blindphoneradar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Typeface
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline schematic radar map (not a street map). Shows the user's real GPS
 * path and real compass heading. BLE targets are placed on their measured
 * distance ring but spread evenly around it only to avoid overlap — no
 * fabricated bearing is ever drawn for them.
 */
class RadarMapView(context: Context) : View(context) {
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE3EAF0.toInt(); strokeWidth = 1.5f }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f }
    private val ringLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = 0xFF7C8A96.toInt() }
    private val userPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E88E5.toInt(); style = Paint.Style.FILL }
    private val userHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x331E88E5; style = Paint.Style.FILL }
    private val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E88E5.toInt(); style = Paint.Style.FILL }
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x551E88E5; style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f; color = 0xFF20303D.toInt(); typeface = Typeface.DEFAULT_BOLD }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; color = 0xFF8A97A2.toInt() }

    private var targets: List<DeviceObservation> = emptyList()
    private var latitude: Double? = null
    private var longitude: Double? = null
    private var headingDeg: Float = 0f
    private val path = ArrayDeque<Pair<Double, Double>>()

    fun setData(items: List<DeviceObservation>, lat: Double?, lon: Double?) {
        targets = items.sortedBy { it.estimate.meters ?: Double.MAX_VALUE }.take(10)
        if (lat != null && lon != null) {
            val last = path.lastOrNull()
            if (last == null || distanceMeters(last.first, last.second, lat, lon) > 1.5) {
                path.addLast(lat to lon)
                if (path.size > 60) path.removeFirst()
            }
            latitude = lat
            longitude = lon
        }
        invalidate()
    }

    fun setHeading(degrees: Float) {
        headingDeg = degrees
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = max(90f, min(w, h) * 0.40f)

        canvas.drawColor(0xFFF7FAFC.toInt())
        for (i in -5..5) {
            val x = cx + i * radius / 2.5f
            canvas.drawLine(x, 0f, x, h, gridPaint)
            val y = cy + i * radius / 2.5f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        val maxRange = (targets.mapNotNull { it.estimate.maxMeters.takeIf { m -> m > 0 } }
            .maxOrNull()?.times(1.15)) ?: 6.0
        val effectiveMax = maxRange.coerceIn(4.0, 40.0)
        val pxPerMeter = radius / effectiveMax

        val ringFractions = floatArrayOf(0.25f, 0.5f, 0.75f, 1f)
        ringFractions.forEachIndexed { i, f ->
            val alpha = 255 - i * 35
            ringPaint.color = Color.argb(alpha, 0x9F, 0xB7, 0xC9)
            canvas.drawCircle(cx, cy, radius * f, ringPaint)
            val meters = effectiveMax * f
            canvas.drawText("%.0fm".format(meters), cx + 10f, cy - radius * f + 22f, ringLabelPaint)
        }

        if (path.size >= 2 && latitude != null && longitude != null) {
            val refLat = latitude!!
            val refLon = longitude!!
            val pts = path.map { (la, lo) -> toLocalXY(la, lo, refLat, refLon, pxPerMeter, cx, cy) }
            for (i in 1 until pts.size) {
                pathPaint.alpha = (60 + (i * 140 / pts.size)).coerceIn(30, 180)
                canvas.drawLine(pts[i - 1].x, pts[i - 1].y, pts[i].x, pts[i].y, pathPaint)
            }
        }

        canvas.drawCircle(cx, cy, 30f, userHaloPaint)
        canvas.drawCircle(cx, cy, 14f, userPaint)
        drawHeadingArrow(canvas, cx, cy)

        val loc = if (latitude != null && longitude != null) "GPS %.5f, %.5f".format(latitude, longitude)
        else "در حال دریافت موقعیت GPS"
        canvas.drawText(loc, 20f, 30f, notePaint)

        if (targets.isEmpty()) {
            canvas.drawText("رادار: هدفی در محدوده دیده نمی‌شود", 20f, h - 50f, labelPaint)
        } else {
            val n = targets.size
            targets.forEachIndexed { index, item ->
                val meters = (item.estimate.meters ?: effectiveMax).coerceIn(0.3, effectiveMax)
                val r = (meters * pxPerMeter).coerceAtMost(radius.toDouble()).toFloat()
                val angle = (2 * PI * index / n) - PI / 2
                val tx = cx + r * cos(angle).toFloat()
                val ty = cy + r * sin(angle).toFloat()
                val conf = item.estimate.confidence
                val color = when {
                    conf >= 70 -> 0xFF2E9E5B.toInt()
                    conf >= 40 -> 0xFFE0A11E.toInt()
                    else -> 0xFF9AA5AD.toInt()
                }
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
                canvas.drawCircle(tx, ty, 16f, dotPaint)
                val label = item.estimate.meters?.let { "%.1fm".format(it) } ?: "؟"
                canvas.drawText(label, tx + 20f, ty + 8f, labelPaint)
            }
        }

        canvas.drawText("موقعیت اهداف روی حلقه فاصله است؛ جهت واقعی BLE مشخص نیست.", 20f, h - 20f, notePaint)
    }

    private fun drawHeadingArrow(canvas: Canvas, cx: Float, cy: Float) {
        val len = 26f
        val rad = Math.toRadians(headingDeg.toDouble() - 90.0)
        val tipX = cx + len * cos(rad).toFloat()
        val tipY = cy + len * sin(rad).toFloat()
        val leftRad = rad + Math.toRadians(150.0)
        val rightRad = rad - Math.toRadians(150.0)
        val leftX = cx + (len * 0.5f) * cos(leftRad).toFloat()
        val leftY = cy + (len * 0.5f) * sin(leftRad).toFloat()
        val rightX = cx + (len * 0.5f) * cos(rightRad).toFloat()
        val rightY = cy + (len * 0.5f) * sin(rightRad).toFloat()
        val arrow = Path().apply {
            moveTo(tipX, tipY)
            lineTo(leftX, leftY)
            lineTo(rightX, rightY)
            close()
        }
        canvas.drawPath(arrow, headingPaint)
    }

    private fun toLocalXY(lat: Double, lon: Double, refLat: Double, refLon: Double, pxPerMeter: Double, cx: Float, cy: Float): PointF {
        val dLat = (lat - refLat) * 110_540.0
        val dLon = (lon - refLon) * 111_320.0 * cos(Math.toRadians(refLat))
        val x = cx + (dLon * pxPerMeter).toFloat()
        val y = cy - (dLat * pxPerMeter).toFloat()
        return PointF(x, y)
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * 110_540.0
        val dLon = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians(lat1))
        return sqrt(dLat * dLat + dLon * dLon)
    }
}
