// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A hand-drawn Compose-Canvas telemetry line chart, no external chart library (see the app's HUD
 * instruments, all drawn the same way). Plots one series as a filled area + line, auto-scaling Y to
 * the data, with the peak value marked and labeled. X is just even sample spacing (the caller
 * decides what the samples mean, elapsed time or sample index). Designed to tile cleanly in the
 * single-page flight-report dashboard and to render live (pass a rolling window of recent values).
 *
 * [values] the series (empty → an "n/a" placeholder). [color] the line/area hue. [label] top-left
 * caption. [unit] appended to the peak readout. [fmt] formats a value for the peak label.
 */
@Composable
fun TelemetryChart(
    values: List<Float>,
    color: Color,
    label: String,
    unit: String,
    modifier: Modifier = Modifier,
    fmt: (Float) -> String = { "%.0f".format(it) },
    yFloorZero: Boolean = true,
) {
    // The caller sizes this via [modifier] (a fixed .height() or a .weight() inside a column), so
    // the same chart tiles cleanly in a fixed live panel and flexes in the report dashboard.
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            drawChart(values, color, label, unit, fmt, yFloorZero)
        }
    }
}

private fun DrawScope.drawChart(
    values: List<Float>,
    color: Color,
    label: String,
    unit: String,
    fmt: (Float) -> String,
    yFloorZero: Boolean,
) {
    val w = size.width
    val h = size.height
    val padL = 4.dp.toPx()
    val padR = 4.dp.toPx()
    val padT = 14.dp.toPx()   // room for the label row
    val padB = 4.dp.toPx()
    val plotW = (w - padL - padR).coerceAtLeast(1f)
    val plotH = (h - padT - padB).coerceAtLeast(1f)

    // Faint horizontal gridlines (3) + baseline, an instrument-panel grid the line rides on.
    for (g in 1..3) {
        val gy = padT + plotH * (g / 4f)
        drawLine(Color.White.copy(alpha = 0.05f), Offset(padL, gy), Offset(w - padR, gy), strokeWidth = 0.8.dp.toPx())
    }
    drawLine(color.copy(alpha = 0.20f), Offset(padL, h - padB), Offset(w - padR, h - padB), strokeWidth = 1.dp.toPx())

    fun labelPaint(sizeSp: Float, c: Int, bold: Boolean) = android.graphics.Paint().apply {
        this.color = c
        textSize = sizeSp.dp.toPx()
        isAntiAlias = true
        isFakeBoldText = bold
        setShadowLayer(3.dp.toPx(), 0f, 0f, android.graphics.Color.argb(200, 0, 0, 0))
    }

    // Caption (top-left).
    drawContext.canvas.nativeCanvas.drawText(
        label, padL, 10.dp.toPx(), labelPaint(9f, android.graphics.Color.argb(210, 210, 210, 210), true),
    )
    // Current / latest value (top-right), the live reading on the Telemetry screen, the landing
    // value on a saved report. Peak is still marked on the curve below.
    values.lastOrNull()?.let { cur ->
        drawContext.canvas.nativeCanvas.drawText(
            "${fmt(cur)}$unit", w - padR, 10.dp.toPx(),
            labelPaint(10f, color.toArgb(), true).apply { textAlign = android.graphics.Paint.Align.RIGHT },
        )
    }

    if (values.size < 2) {
        drawContext.canvas.nativeCanvas.drawText(
            ", ", w / 2f, h / 2f + 4.dp.toPx(),
            labelPaint(11f, color.copy(alpha = 0.5f).toArgb(), true).apply { textAlign = android.graphics.Paint.Align.CENTER },
        )
        return
    }

    val maxV = values.max()
    val minV = if (yFloorZero) 0f else values.min()
    val range = (maxV - minV).takeIf { it > 1e-4f } ?: 1f
    val n = values.size
    fun x(i: Int) = padL + plotW * (i / (n - 1).toFloat())
    fun y(v: Float) = padT + plotH * (1f - (v - minV) / range)

    // Filled area under the curve (gradient fade to transparent).
    val area = Path().apply {
        moveTo(x(0), h - padB)
        for (i in values.indices) lineTo(x(i), y(values[i]))
        lineTo(x(n - 1), h - padB)
        close()
    }
    drawPath(
        area,
        brush = Brush.verticalGradient(
            0f to color.copy(alpha = 0.34f), 1f to color.copy(alpha = 0.02f),
            startY = padT, endY = h - padB,
        ),
    )

    // The line itself, a dark under-stroke for contrast on any background, then the colored line.
    val line = Path().apply {
        moveTo(x(0), y(values[0]))
        for (i in 1 until n) lineTo(x(i), y(values[i]))
    }
    drawPath(line, color = Color.Black.copy(alpha = 0.4f), style = Stroke(width = 3f.dp.toPx()))
    drawPath(line, color = color, style = Stroke(width = 1.8.dp.toPx()))

    // Peak marker + value.
    val peakIdx = values.indices.maxByOrNull { values[it] } ?: 0
    val px = x(peakIdx); val py = y(values[peakIdx])
    drawCircle(Color.Black.copy(alpha = 0.5f), radius = 3.2.dp.toPx(), center = Offset(px, py))
    drawCircle(color, radius = 2.2.dp.toPx(), center = Offset(px, py))
    val peakText = "${fmt(maxV)}$unit"
    val pp = labelPaint(10f, color.toArgb(), true).apply {
        textAlign = if (px > w * 0.6f) android.graphics.Paint.Align.RIGHT else android.graphics.Paint.Align.LEFT
    }
    val tx = if (px > w * 0.6f) (px - 4.dp.toPx()) else (px + 5.dp.toPx())
    drawContext.canvas.nativeCanvas.drawText(peakText, tx, (py - 5.dp.toPx()).coerceAtLeast(padT + 8.dp.toPx()), pp)
}
