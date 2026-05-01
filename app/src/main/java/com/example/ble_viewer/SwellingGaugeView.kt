package com.example.ble_viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class SwellingGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(38, 50, 56)
        style = Paint.Style.FILL
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(38, 50, 56)
        style = Paint.Style.FILL
    }

    private val segmentColors = intArrayOf(
        Color.rgb(45, 126, 234),  // none
        Color.rgb(255, 214, 64),  // mild
        Color.rgb(245, 132, 51),  // moderate
        Color.rgb(229, 57, 53)    // severe
    )

    private var label: String = "calibrating"

    fun setSwellingLabel(rawLabel: String) {
        label = SwellingHistoryStore.normalizeLabel(rawLabel) ?: "calibrating"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Keep gauge geometry stable regardless of any surrounding header text.
        // We reserve small top/bottom padding and size the arc from the remaining height.
        val topPad = h * 0.06f
        val bottomPad = h * 0.06f
        val usableH = (h - topPad - bottomPad).coerceAtLeast(1f)

        val stroke = min(w, usableH * 1.6f) * 0.16f
        arcPaint.strokeWidth = stroke

        val radius = min(w * 0.38f, usableH)
        val cx = w / 2f
        // Center of the circle sits at the bottom of the usable area.
        val cy = topPad + usableH
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        val gap = 3f
        val sweep = 180f / segmentColors.size
        for (i in segmentColors.indices) {
            arcPaint.color = segmentColors[i]
            canvas.drawArc(oval, 180f + i * sweep + gap / 2f, sweep - gap, false, arcPaint)
        }

        for (i in 1 until segmentColors.size) {
            val angle = Math.toRadians((180f + i * sweep).toDouble())
            val rOuter = radius + stroke * 0.45f
            val rInner = radius - stroke * 0.45f
            canvas.drawLine(
                cx + cos(angle).toFloat() * rInner,
                cy + sin(angle).toFloat() * rInner,
                cx + cos(angle).toFloat() * rOuter,
                cy + sin(angle).toFloat() * rOuter,
                tickPaint
            )
        }

        val angleDeg = when (label) {
            "none" -> 202.5f
            "mild" -> 247.5f
            "moderate" -> 292.5f
            "severe" -> 337.5f
            else -> 202.5f
        }
        val angle = Math.toRadians(angleDeg.toDouble())
        val needleLength = radius * 0.72f
        val needleWidth = stroke * 0.18f
        val tipX = cx + cos(angle).toFloat() * needleLength
        val tipY = cy + sin(angle).toFloat() * needleLength
        val leftAngle = angle + Math.PI / 2.0
        val rightAngle = angle - Math.PI / 2.0

        val path = Path().apply {
            moveTo(tipX, tipY)
            lineTo(
                cx + cos(leftAngle).toFloat() * needleWidth,
                cy + sin(leftAngle).toFloat() * needleWidth
            )
            lineTo(
                cx + cos(rightAngle).toFloat() * needleWidth,
                cy + sin(rightAngle).toFloat() * needleWidth
            )
            close()
        }
        canvas.drawPath(path, needlePaint)
        canvas.drawCircle(cx, cy, stroke * 0.22f, hubPaint)
    }
}
