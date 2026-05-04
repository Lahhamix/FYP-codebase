package com.example.ble_viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class StepDonutView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(229, 233, 239)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(126, 62, 234)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(14, 59, 102)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(110, 119, 133)
        textAlign = Paint.Align.CENTER
    }
    private val arcBounds = RectF()

    private var steps: Int = 0
    private var goal: Int = 10000

    fun setSteps(value: Int, dailyGoal: Int = goal) {
        steps = value.coerceAtLeast(0)
        goal = dailyGoal.coerceAtLeast(1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val stroke = size * 0.095f
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke

        val pad = stroke / 2f + 3f
        arcBounds.set(pad, pad, width - pad, height - pad)
        canvas.drawArc(arcBounds, -90f, 360f, false, trackPaint)
        canvas.drawArc(arcBounds, -90f, 360f * (steps.toFloat() / goal).coerceIn(0f, 1f), false, progressPaint)

        valuePaint.textSize = size * 0.22f
        labelPaint.textSize = size * 0.105f
        val centerX = width / 2f
        val centerY = height / 2f
        canvas.drawText(steps.toString(), centerX, centerY - size * 0.015f, valuePaint)
        canvas.drawText("steps", centerX, centerY + size * 0.145f, labelPaint)
    }
}
