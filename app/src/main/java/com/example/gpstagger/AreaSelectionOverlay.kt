package com.example.gpstagger

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.view.MotionEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Rubber-band selection overlay. While [isActive], single-finger drag draws a
 * translucent selection rectangle. On ACTION_UP the bounding box (in geo-coordinates)
 * is delivered to [onAreaSelected].
 */
class AreaSelectionOverlay(
    private val mapView: MapView,
    private val onAreaSelected: (BoundingBox) -> Unit
) : Overlay() {

    var isActive = false
        set(value) {
            field = value
            if (!value) {
                startPoint = null
                currentPoint = null
            }
            mapView.invalidate()
        }

    private var startPoint: PointF? = null
    private var currentPoint: PointF? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 33, 150, 243)
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(33, 150, 243)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
        if (!isActive) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startPoint = PointF(event.x, event.y)
                currentPoint = PointF(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentPoint = PointF(event.x, event.y)
                mapView.invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                currentPoint = PointF(event.x, event.y)
                val s = startPoint ?: return true
                val e = currentPoint ?: return true
                val proj = mapView.projection
                val startGeo = proj.fromPixels(s.x.toInt(), s.y.toInt())
                val endGeo   = proj.fromPixels(e.x.toInt(), e.y.toInt())
                val bbox = BoundingBox(
                    maxOf(startGeo.latitude, endGeo.latitude),
                    maxOf(startGeo.longitude, endGeo.longitude),
                    minOf(startGeo.latitude, endGeo.latitude),
                    minOf(startGeo.longitude, endGeo.longitude)
                )
                startPoint = null
                currentPoint = null
                mapView.invalidate()
                onAreaSelected(bbox)
                return true
            }
        }
        return false
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (!isActive) return
        val s = startPoint ?: return
        val c = currentPoint ?: return
        val left   = minOf(s.x, c.x)
        val top    = minOf(s.y, c.y)
        val right  = maxOf(s.x, c.x)
        val bottom = maxOf(s.y, c.y)
        canvas.drawRect(left, top, right, bottom, fillPaint)
        canvas.drawRect(left, top, right, bottom, strokePaint)
    }
}
