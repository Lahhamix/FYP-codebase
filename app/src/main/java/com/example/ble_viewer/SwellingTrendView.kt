package com.example.ble_viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

class SwellingTrendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(13, 59, 102)
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 13, 59, 102)
        strokeWidth = 1f
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(107, 116, 128)
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    private var samples: List<SwellingHistoryStore.Sample> = emptyList()
    private var windowMs: Long = 24L * 60L * 60_000L

    fun setSamples(newSamples: List<SwellingHistoryStore.Sample>, windowMs: Long = 24L * 60L * 60_000L) {
        // Preprocess for a smooth "trend" look:
        // - bucket in time to avoid spikes from rapid label flips
        // - apply a light EMA to reduce jaggedness further
        samples = smoothAndBucket(newSamples, windowMs)
        this.windowMs = windowMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val left = 8f
        val top = 10f
        val right = w - 8f
        val bottom = h - 14f

        for (i in 1..3) {
            val y = top + (bottom - top) * i / 4f
            canvas.drawLine(left, y, right, y, gridPaint)
        }

        if (samples.isEmpty()) {
            canvas.drawText("No swelling history yet", w / 2f, h / 2f, emptyPaint)
            return
        }

        val now = System.currentTimeMillis()
        val start = now - windowMs
        val plotted = if (samples.size == 1) {
            listOf(
                samples.first().copy(epochMs = start),
                samples.first().copy(epochMs = now)
            )
        } else {
            samples
        }

        val points = plotted.map { sample ->
            val xFrac = ((sample.epochMs - start).toFloat() / windowMs.toFloat()).coerceIn(0f, 1f)
            val score = SwellingHistoryStore.severityScore(sample.label).coerceIn(0, 3)
            val yFrac = score / 3f
            val x = left + (right - left) * xFrac
            val y = bottom - (bottom - top) * yFrac
            x to y
        }

        val line = buildSmoothPath(points)
        val fill = Path(line).apply {
            // Close down to baseline for the fill.
            lineTo(points.last().first, bottom)
            lineTo(points.first().first, bottom)
            close()
        }

        fillPaint.shader = LinearGradient(
            0f,
            top,
            0f,
            bottom,
            Color.argb(92, 13, 59, 102),
            Color.argb(0, 13, 59, 102),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fill, fillPaint)
        fillPaint.shader = null
        canvas.drawPath(line, linePaint)
    }

    private fun smoothAndBucket(
        raw: List<SwellingHistoryStore.Sample>,
        windowMs: Long
    ): List<SwellingHistoryStore.Sample> {
        val sorted = raw.sortedBy { it.epochMs }
        if (sorted.isEmpty()) return emptyList()

        // Target ~80–120 points max depending on window.
        val bucketMs = (windowMs / 96L).coerceIn(5 * 60_000L, 30 * 60_000L)
        val buckets = ArrayList<Pair<Long, Float>>() // epochMs, scoreFloat

        var i = 0
        while (i < sorted.size) {
            val bucketStart = sorted[i].epochMs
            val bucketEnd = bucketStart + bucketMs
            var sum = 0f
            var count = 0
            var lastTs = bucketStart
            while (i < sorted.size && sorted[i].epochMs < bucketEnd) {
                val s = SwellingHistoryStore.severityScore(sorted[i].label).coerceIn(0, 3).toFloat()
                sum += s
                count++
                lastTs = sorted[i].epochMs
                i++
            }
            if (count > 0) buckets.add(lastTs to (sum / count.toFloat()))
        }

        if (buckets.isEmpty()) return emptyList()

        // EMA on the bucketed severity score.
        val alpha = 0.25f
        val ema = ArrayList<Pair<Long, Float>>(buckets.size)
        var prev = buckets.first().second
        ema.add(buckets.first().first to prev)
        for (k in 1 until buckets.size) {
            val cur = buckets[k].second
            val v = alpha * cur + (1f - alpha) * prev
            prev = v
            ema.add(buckets[k].first to v)
        }

        // Convert back to labels by rounding; view uses severityScore(label) anyway.
        // We keep it as strings matching normalizeLabel values.
        return ema.map { (ts, scoreF) ->
            val score = scoreF.coerceIn(0f, 3f).toInt()
            val label = when (score) {
                0 -> "none"
                1 -> "mild"
                2 -> "moderate"
                else -> "severe"
            }
            SwellingHistoryStore.Sample(epochMs = ts, label = label)
        }
    }

    private fun buildSmoothPath(points: List<Pair<Float, Float>>): Path {
        val path = Path()
        if (points.isEmpty()) return path
        path.moveTo(points[0].first, points[0].second)
        if (points.size == 1) return path

        // Quadratic smoothing between points.
        for (i in 1 until points.size) {
            val (x0, y0) = points[i - 1]
            val (x1, y1) = points[i]
            val mx = (x0 + x1) / 2f
            val my = (y0 + y1) / 2f
            if (i == 1) {
                path.quadTo(x0, y0, mx, my)
            } else {
                path.quadTo(x0, y0, mx, my)
            }
            if (i == points.lastIndex) {
                path.quadTo(x1, y1, x1, y1)
            }
        }
        return path
    }
}
