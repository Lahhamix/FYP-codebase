package com.example.ble_viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

class DashboardMetricDecorationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val kind = tag?.toString().orEmpty()
        val accent = when (kind) {
            "heart" -> Color.rgb(236, 77, 117)
            "spo2" -> Color.rgb(45, 126, 234)
            "bp" -> Color.rgb(49, 86, 125)
            "steps" -> Color.rgb(126, 62, 234)
            "swelling" -> Color.rgb(47, 170, 150)
            "ataxia" -> Color.rgb(245, 132, 51)
            else -> Color.rgb(49, 86, 125)
        }
        paint.color = accent.withAlpha(72)
        fillPaint.color = accent.withAlpha(42)

        when (kind) {
            "heart" -> drawEcg(canvas)
            "spo2" -> drawWave(canvas)
            "bp" -> drawDashes(canvas)
            "steps" -> drawBars(canvas)
            "swelling" -> drawFilledWave(canvas)
            "ataxia" -> drawDots(canvas)
            else -> drawWave(canvas)
        }
    }

    private fun drawEcg(canvas: Canvas) {
        val y = height * 0.52f
        val step = width / 12f
        val p = Path().apply {
            moveTo(0f, y)
            lineTo(step * 2f, y)
            lineTo(step * 2.4f, y - height * 0.35f)
            lineTo(step * 2.8f, y + height * 0.22f)
            lineTo(step * 3.3f, y)
            lineTo(step * 6f, y)
            lineTo(step * 6.5f, y - height * 0.25f)
            lineTo(step * 7f, y + height * 0.18f)
            lineTo(step * 7.5f, y)
            lineTo(width.toFloat(), y)
        }
        canvas.drawPath(p, paint)
    }

    private fun drawWave(canvas: Canvas) {
        val p = Path()
        for (i in 0..48) {
            val x = width * i / 48f
            val y = height * 0.52f + sin(i / 3.0).toFloat() * height * 0.18f
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        canvas.drawPath(p, paint)
    }

    private fun drawDashes(canvas: Canvas) {
        val y = height * 0.55f
        val dashW = width / 10f
        for (i in 0 until 6) {
            val x = i * dashW * 1.55f
            canvas.drawLine(x, y, x + dashW, y, paint)
        }
    }

    private fun drawBars(canvas: Canvas) {
        val barW = width / 12f
        for (i in 0 until 8) {
            val h = height * (0.18f + ((i * 7) % 5) * 0.11f)
            val x = i * barW * 1.45f
            canvas.drawRoundRect(x, height - h, x + barW, height.toFloat(), 4f, 4f, fillPaint)
        }
    }

    private fun drawFilledWave(canvas: Canvas) {
        val p = Path().apply {
            moveTo(0f, height.toFloat())
        }
        for (i in 0..36) {
            val x = width * i / 36f
            val y = height * 0.58f + sin(i / 4.0).toFloat() * height * 0.16f
            p.lineTo(x, y)
        }
        p.lineTo(width.toFloat(), height.toFloat())
        p.close()
        canvas.drawPath(p, fillPaint)
    }

    private fun drawDots(canvas: Canvas) {
        val cy = height * 0.58f
        val r = height * 0.13f
        for (i in 0 until 7) {
            fillPaint.alpha = 80 - i * 8
            canvas.drawCircle(width * (0.12f + i * 0.13f), cy, r, fillPaint)
        }
        fillPaint.alpha = 255
    }

    private fun Int.withAlpha(alpha: Int): Int {
        return (alpha.coerceIn(0, 255) shl 24) or (this and 0x00FFFFFF)
    }
}
