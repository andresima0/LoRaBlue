package com.android.lorablue.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Minimal line chart drawn directly with Canvas — no external charting
 * library. Plots (timestamp, value) points left-to-right by time, with a
 * value axis on the left and up to 4 time labels along the bottom.
 *
 * Deliberately simple: no zoom/pan, no animation, no legend. This is
 * enough for a single-series 10-minute telemetry window; if richer
 * interaction is needed later, swapping in a real charting library
 * remains an option once the Kotlin/KSP plugin classpath issue is
 * resolved at the project level.
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var points: List<Pair<Long, Double>> = emptyList()

    private val linePaint = Paint().apply {
        color = Color.parseColor("#3F51B5")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val pointPaint = Paint().apply {
        color = Color.parseColor("#3F51B5")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val axisPaint = Paint().apply {
        color = Color.parseColor("#CCCCCC")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor("#888888")
        textSize = 28f
        isAntiAlias = true
    }

    private val emptyTextPaint = Paint().apply {
        color = Color.parseColor("#AAAAAA")
        textSize = 32f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val paddingLeft = 90f
    private val paddingBottom = 60f
    private val paddingTop = 30f
    private val paddingRight = 20f

    fun setPoints(newPoints: List<Pair<Long, Double>>) {
        points = newPoints
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (points.size < 2) {
            canvas.drawText(
                if (points.isEmpty()) "No data yet" else "Waiting for more data...",
                width / 2f,
                height / 2f,
                emptyTextPaint
            )
            return
        }

        val chartLeft = paddingLeft
        val chartRight = width - paddingRight
        val chartTop = paddingTop
        val chartBottom = height - paddingBottom

        val minTime = points.first().first
        val maxTime = points.last().first
        val timeRange = (maxTime - minTime).coerceAtLeast(1L)

        val minValue = points.minOf { it.second }
        val maxValue = points.maxOf { it.second }
        // Avoid a zero-height range (e.g. a perfectly flat line) collapsing
        // everything onto one pixel row — pad the value range symmetrically.
        val valueRange = (maxValue - minValue).let { if (it < 0.0001) 1.0 else it }
        val valuePadding = valueRange * 0.1
        val displayMin = minValue - valuePadding
        val displayMax = maxValue + valuePadding
        val displayRange = (displayMax - displayMin).coerceAtLeast(0.0001)

        fun xFor(timestamp: Long): Float =
            chartLeft + ((timestamp - minTime).toFloat() / timeRange) * (chartRight - chartLeft)

        fun yFor(value: Double): Float =
            chartBottom - ((value - displayMin) / displayRange).toFloat() * (chartBottom - chartTop)

        // Axes
        canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, axisPaint)
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, axisPaint)

        // Y-axis labels (min/max of the displayed range)
        canvas.drawText(String.format("%.1f", displayMax), 4f, chartTop + 24f, textPaint)
        canvas.drawText(String.format("%.1f", displayMin), 4f, chartBottom, textPaint)

        // X-axis labels: first, middle, last timestamp
        val labelIndices = listOf(0, points.size / 2, points.size - 1).distinct()
        labelIndices.forEach { i ->
            val (timestamp, _) = points[i]
            val label = timeFormatter.format(timestamp)
            val x = xFor(timestamp)
            canvas.drawText(label, (x - 60f).coerceAtLeast(chartLeft), height - 15f, textPaint)
        }

        // Line connecting every point
        for (i in 0 until points.size - 1) {
            val (t1, v1) = points[i]
            val (t2, v2) = points[i + 1]
            canvas.drawLine(xFor(t1), yFor(v1), xFor(t2), yFor(v2), linePaint)
        }

        // Dots on top, skipped when there are too many points to avoid clutter
        if (points.size <= 60) {
            points.forEach { (t, v) ->
                canvas.drawCircle(xFor(t), yFor(v), 6f, pointPaint)
            }
        }
    }
}