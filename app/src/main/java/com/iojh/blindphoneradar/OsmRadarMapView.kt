package com.iojh.blindphoneradar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Real street map (OpenStreetMap tiles, no API key required). Shows the
 * user's live GPS position as a person marker with a heading arrow.
 * BLE targets are drawn as phone icons placed on their distance ring at a
 * stable (device-id-based) angle — not a real bearing, since RSSI alone
 * cannot provide direction — each with a permanent label showing the
 * approximate distance.
 */
class OsmRadarMapView(context: Context) : MapView(context) {
    private var userPoint: GeoPoint? = null
    private var targets: List<DeviceObservation> = emptyList()
    private var headingDeg: Float = 0f

    private val ringPaintCache = HashMap<Int, Paint>()

    private val radarOverlay = object : Overlay() {
        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            val user = userPoint ?: return
            val userScreen = mapView.projection.toPixels(user, null)
            val ux = userScreen.x.toFloat()
            val uy = userScreen.y.toFloat()

            targets.take(10).forEach { item ->
                val meters = item.estimate.meters ?: return@forEach
                val edgeGeoPoint = destinationPoint(user, meters, 0.0)
                val edgeScreen = mapView.projection.toPixels(edgeGeoPoint, null)
                val radiusPx = abs(edgeScreen.y - userScreen.y).toFloat().coerceAtLeast(30f)

                val ringColor = when {
                    item.estimate.confidence >= 70 -> Color.parseColor("#2E9E5B")
                    item.estimate.confidence >= 40 -> Color.parseColor("#E0A11E")
                    else -> Color.parseColor("#9AA5AD")
                }
                val ringPaint = ringPaintCache.getOrPut(ringColor) {
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 4f
                        color = ringColor
                    }
                }
                canvas.drawCircle(ux, uy, radiusPx, ringPaint)

                val angleDeg = stableAngleFor(item.key)
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val px = ux + radiusPx * sin(angleRad).toFloat()
                val py = uy - radiusPx * cos(angleRad).toFloat()

                drawPhoneMarker(canvas, px, py, ringColor)
                drawDistanceLabel(canvas, px, py, meters)
            }

            drawUserMarker(canvas, ux, uy, headingDeg)
        }
    }

    init {
        Configuration.getInstance().userAgentValue = context.packageName
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        controller.setZoom(18.0)
        overlays.add(radarOverlay)
    }

    fun setUserLocation(lat: Double, lon: Double, firstFix: Boolean) {
        val point = GeoPoint(lat, lon)
        userPoint = point
        if (firstFix) controller.setCenter(point) else controller.animateTo(point)
        invalidate()
    }

    fun setTargets(items: List<DeviceObservation>) {
        targets = items
        invalidate()
    }

    fun setHeading(degrees: Float) {
        headingDeg = degrees
        invalidate()
    }

    fun recenterAndZoom() {
        val point = userPoint ?: return
        controller.animateTo(point, 19.0, 600L)
    }

    /** Stable pseudo-angle derived from the device key so a given phone
     *  keeps the same on-screen position across redraws (cosmetic layout
     *  only — not a real compass bearing). */
    private fun stableAngleFor(key: String): Int {
        val hash = key.hashCode()
        return abs(hash) % 360
    }

    private fun drawUserMarker(canvas: Canvas, cx: Float, cy: Float, heading: Float) {
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#33000000")
        }
        canvas.drawOval(RectF(cx - 16f, cy + 20f, cx + 16f, cy + 28f), shadowPaint)

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = android.graphics.RadialGradient(
                cx, cy - 26f, 26f,
                Color.parseColor("#4C8CF0"), Color.parseColor("#1552B8"),
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.WHITE
        }
        // Head
        canvas.drawCircle(cx, cy - 20f, 12f, bodyPaint)
        canvas.drawCircle(cx, cy - 20f, 12f, outlinePaint)
        // Body (rounded rect)
        val bodyRect = RectF(cx - 14f, cy - 6f, cx + 14f, cy + 22f)
        canvas.drawRoundRect(bodyRect, 10f, 10f, bodyPaint)
        canvas.drawRoundRect(bodyRect, 10f, 10f, outlinePaint)

        // Heading arrow above the head
        canvas.save()
        canvas.rotate(heading, cx, cy - 20f)
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#FF5A1F")
        }
        val arrow = Path().apply {
            moveTo(cx, cy - 48f)
            lineTo(cx - 9f, cy - 30f)
            lineTo(cx + 9f, cy - 30f)
            close()
        }
        canvas.drawPath(arrow, arrowPaint)
        val arrowOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.WHITE
        }
        canvas.drawPath(arrow, arrowOutline)
        canvas.restore()
    }

    private fun drawPhoneMarker(canvas: Canvas, x: Float, y: Float, phoneColor: Int) {
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#2A000000")
        }
        canvas.drawOval(RectF(x - 10f, y + 13f, x + 10f, y + 19f), shadowPaint)

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = android.graphics.LinearGradient(
                x - 9f, y - 15f, x + 9f, y + 15f,
                Color.parseColor("#4A4A4A"), Color.parseColor("#141414"),
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = android.graphics.LinearGradient(
                x - 6f, y - 11f, x + 6f, y + 8f,
                lighten(phoneColor), phoneColor,
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.WHITE
        }
        val phoneRect = RectF(x - 9f, y - 15f, x + 9f, y + 15f)
        canvas.drawRoundRect(phoneRect, 5f, 5f, bodyPaint)
        canvas.drawRoundRect(phoneRect, 5f, 5f, outlinePaint)
        val screenRect = RectF(x - 6f, y - 11f, x + 6f, y + 8f)
        canvas.drawRect(screenRect, screenPaint)
    }

    private fun lighten(color: Int): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) / 2)
        val g = (Color.green(color) + (255 - Color.green(color)) / 2)
        val b = (Color.blue(color) + (255 - Color.blue(color)) / 2)
        return Color.rgb(r, g, b)
    }

    private fun drawDistanceLabel(canvas: Canvas, x: Float, y: Float, meters: Double) {
        val text = "≈%.1fm".format(Locale.US, meters)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1A1A")
            textSize = 26f
            isFakeBoldText = true
        }
        val textWidth = textPaint.measureText(text)
        val labelX = x + 16f
        val labelY = y - 16f
        val bgRect = RectF(
            labelX - 8f,
            labelY - 30f,
            labelX + textWidth + 8f,
            labelY + 8f
        )
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            setShadowLayer(6f, 0f, 2f, Color.parseColor("#40000000"))
        }
        canvas.drawRoundRect(bgRect, 8f, 8f, bgPaint)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#DDDDDD")
        }
        canvas.drawRoundRect(bgRect, 8f, 8f, borderPaint)
        canvas.drawText(text, labelX, labelY, textPaint)
    }

    /** Moves a distance in meters due north from an origin — used only to size
     *  the on-screen ring radius, not to claim a real bearing to the target. */
    private fun destinationPoint(origin: GeoPoint, meters: Double, bearingDeg: Double): GeoPoint {
        val earthRadius = 6_371_000.0
        val bearing = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(origin.latitude)
        val lon1 = Math.toRadians(origin.longitude)
        val angularDistance = meters / earthRadius
        val lat2 = Math.asin(
            Math.sin(lat1) * Math.cos(angularDistance) +
                Math.cos(lat1) * Math.sin(angularDistance) * Math.cos(bearing)
        )
        val lon2 = lon1 + Math.atan2(
            Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(lat1),
            Math.cos(angularDistance) - Math.sin(lat1) * Math.sin(lat2)
        )
        return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }
}
