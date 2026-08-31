package com.iojh.blindphoneradar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon

/**
 * Real street map (OpenStreetMap tiles, no API key required). Shows the
 * user's live GPS position. BLE targets are drawn as distance-ring
 * polygons around the user (not a fabricated point on the map) because
 * RSSI does not provide a real bearing.
 */
class OsmRadarMapView(context: Context) : MapView(context) {
    private var userPoint: GeoPoint? = null
    private var targets: List<DeviceObservation> = emptyList()
    private val ringOverlay = object : Overlay() {
        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            val user = userPoint ?: return
            val userScreen = mapView.projection.toPixels(user, null)
            targets.take(10).forEach { item ->
                val meters = item.estimate.meters ?: return@forEach
                val edgeGeoPoint = destinationPoint(user, meters, 0.0)
                val edgeScreen = mapView.projection.toPixels(edgeGeoPoint, null)
                val radiusPx = kotlin.math.abs(edgeScreen.y - userScreen.y).toFloat()
                    .coerceAtLeast(8f)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                    color = when {
                        item.estimate.confidence >= 70 -> Color.parseColor("#2E9E5B")
                        item.estimate.confidence >= 40 -> Color.parseColor("#E0A11E")
                        else -> Color.parseColor("#9AA5AD")
                    }
                }
                canvas.drawCircle(userScreen.x.toFloat(), userScreen.y.toFloat(), radiusPx, paint)
            }
        }
    }

    init {
        Configuration.getInstance().userAgentValue = context.packageName
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        controller.setZoom(18.0)
        overlays.add(ringOverlay)
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

    private var headingDeg: Float = 0f

    fun setHeading(degrees: Float) {
        headingDeg = degrees
        // Reserved for a future overlay that rotates the user marker;
        // not used to draw a fabricated bearing to BLE targets.
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
