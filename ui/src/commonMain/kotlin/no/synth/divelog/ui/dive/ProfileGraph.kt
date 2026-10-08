package no.synth.divelog.ui.dive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.synth.divelog.core.logbook.format.Format
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.Sample
import no.synth.divelog.core.model.units.UnitSystem

/**
 * Dive profile: depth against time with a temperature trace, event markers and a
 * draggable scrub cursor that reads out values at the touch point. Depth runs
 * downward (0 at the top).
 */
@Composable
fun ProfileGraph(
    samples: List<Sample>,
    events: List<Event>,
    unitSystem: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val points = samples.filter { it.depthMm != null }
    // Parallel to [points]: each one's depth, non-null by the filter above.
    val depths = points.mapNotNull { it.depthMm }
    if (points.size < 2) {
        Text("No profile samples", Modifier.padding(16.dp))
        return
    }

    val maxDepth = depths.max().coerceAtLeast(1)
    val maxTime = points.maxOf { it.timeOffsetSeconds }.coerceAtLeast(1)
    val temps = points.mapNotNull { it.temperatureMk }
    val minTemp = temps.minOrNull()
    val maxTemp = temps.maxOrNull()

    val depthColor = MaterialTheme.colorScheme.primary
    val tempColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val gasColor = MaterialTheme.colorScheme.secondary
    val warnColor = MaterialTheme.colorScheme.error
    val decoColor = MaterialTheme.colorScheme.error
    val cursorColor = MaterialTheme.colorScheme.onSurface

    // Whether this dive had any deco obligation (a stop/ceiling deeper than the surface).
    val hasDeco = points.any { (it.stopDepthMm ?: 0) > 0 }

    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = labelColor, fontSize = 10.sp)

    var cursor by remember(samples) { mutableStateOf<Int?>(null) }

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .pointerInput(samples) {
                    detectTapGestures { offset -> cursor = nearestIndex(offset.x, size.width, points, maxTime) }
                }
                .pointerInput(samples) {
                    detectDragGestures(
                        onDragStart = { offset -> cursor = nearestIndex(offset.x, size.width, points, maxTime) },
                        onDrag = { change, _ -> cursor = nearestIndex(change.position.x, size.width, points, maxTime) },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxWidth().height(240.dp)) {
                val w = size.width
                val h = size.height
                // Reserve a gutter at the bottom for the time axis so labels never
                // collide with the profile or the temperature trace.
                val axisInset = 16f
                val plotH = h - axisInset
                fun x(t: Int) = t.toFloat() / maxTime * w
                fun yDepth(mm: Int) = mm.toFloat() / maxDepth * plotH

                // Horizontal depth gridlines at round depths, labelled.
                val stepMm = niceDepthStepMm(maxDepth)
                var lineDepth = stepMm
                while (lineDepth < maxDepth) {
                    val y = yDepth(lineDepth)
                    drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                    val label = textMeasurer.measure(Format.depth(lineDepth, unitSystem), labelStyle)
                    drawText(label, topLeft = Offset(4f, y - label.size.height - 1f))
                    lineDepth += stepMm
                }

                // Vertical time gridlines at round intervals, labelled in the gutter.
                val timeStep = niceTimeStepSeconds(maxTime)
                var lineTime = timeStep
                while (lineTime < maxTime) {
                    val tx = x(lineTime)
                    drawLine(gridColor, Offset(tx, 0f), Offset(tx, plotH), strokeWidth = 1f)
                    val label = textMeasurer.measure(Format.duration(lineTime), labelStyle)
                    drawText(label, topLeft = Offset(tx - label.size.width / 2f, plotH + 1f))
                    lineTime += timeStep
                }

                // Depth trace, with a translucent fill below it so it reads as water.
                val depthPath = Path().apply {
                    moveTo(x(points[0].timeOffsetSeconds), yDepth(depths[0]))
                    for (i in 1..points.lastIndex) lineTo(x(points[i].timeOffsetSeconds), yDepth(depths[i]))
                }
                val fillPath = Path().apply {
                    addPath(depthPath)
                    lineTo(x(points.last().timeOffsetSeconds), 0f)
                    lineTo(x(points[0].timeOffsetSeconds), 0f)
                    close()
                }
                drawPath(
                    fillPath,
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to depthColor.copy(alpha = 0.28f),
                        1f to depthColor.copy(alpha = 0.04f),
                        endY = plotH,
                    ),
                )
                drawPath(depthPath, depthColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))

                // Deco ceiling: the shallowest allowed depth while a stop was owed.
                // Drawn only across the segments where a ceiling applied.
                if (hasDeco) {
                    val ceilingPath = Path()
                    var started = false
                    for (p in points) {
                        val ceil = p.stopDepthMm ?: 0
                        if (ceil > 0) {
                            val cx = x(p.timeOffsetSeconds)
                            val cy = yDepth(ceil)
                            if (!started) { ceilingPath.moveTo(cx, cy); started = true } else ceilingPath.lineTo(cx, cy)
                        } else {
                            started = false
                        }
                    }
                    drawPath(
                        ceilingPath,
                        decoColor,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 2.5f,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                        ),
                    )
                }

                // Temperature trace, scaled into the lower portion of its own range.
                if (minTemp != null && maxTemp != null && maxTemp > minTemp) {
                    val span = (maxTemp - minTemp).toFloat()
                    val tempPath = Path()
                    var started = false
                    for (p in points) {
                        val mk = p.temperatureMk ?: continue
                        val ty = plotH - (mk - minTemp) / span * (plotH * 0.3f) - 2f
                        val tx = x(p.timeOffsetSeconds)
                        if (!started) { tempPath.moveTo(tx, ty); started = true } else tempPath.lineTo(tx, ty)
                    }
                    drawPath(tempPath, tempColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                }

                // Event markers.
                for (e in events) {
                    val ex = x(e.timeOffsetSeconds.coerceIn(0, maxTime))
                    val color = when (e.type) {
                        EventType.WARNING, EventType.ASCENT_RATE -> warnColor
                        else -> gasColor
                    }
                    drawLine(color, Offset(ex, 0f), Offset(ex, plotH), strokeWidth = 1.5f)
                }

                // Scrub cursor.
                cursor?.let { idx ->
                    val p = points[idx]
                    val cx = x(p.timeOffsetSeconds)
                    drawLine(cursorColor, Offset(cx, 0f), Offset(cx, plotH), strokeWidth = 1.5f)
                    drawCircle(depthColor, radius = 5f, center = Offset(cx, yDepth(depths[idx])))
                }
            }
        }

        val readout = cursor?.let { idx ->
            val p = points[idx]
            buildString {
                append(Format.duration(p.timeOffsetSeconds)).append("  ")
                append(Format.depth(p.depthMm, unitSystem))
                p.temperatureMk?.let { append("  ").append(Format.temperature(it, unitSystem)) }
                (p.stopDepthMm ?: 0).takeIf { it > 0 }?.let { append("  ceiling ").append(Format.depth(it, unitSystem)) }
            }
        } ?: buildString {
            append("Max ${Format.depth(maxDepth, unitSystem)} · ${Format.duration(maxTime)}")
            if (hasDeco) append(" · deco (dashed)")
        }
        Text(readout, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)

        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LegendItem(depthColor, "Depth")
            if (minTemp != null) LegendItem(tempColor, "Temp")
            if (hasDeco) LegendItem(decoColor, "Ceiling")
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A round depth-gridline spacing (in mm) giving roughly 3-6 lines for the dive. */
private fun niceDepthStepMm(maxDepthMm: Int): Int {
    val maxM = maxDepthMm / 1000.0
    val stepsM = listOf(2, 5, 10, 20, 25, 50, 100)
    val stepM = stepsM.firstOrNull { maxM / it <= 6 } ?: 100
    return stepM * 1000
}

/** A round time-gridline spacing (in seconds) giving roughly 3-6 lines for the dive. */
private fun niceTimeStepSeconds(maxTimeSeconds: Int): Int {
    val steps = listOf(60, 120, 300, 600, 900, 1_800, 3_600)
    return steps.firstOrNull { maxTimeSeconds / it <= 6 } ?: 3_600
}

private fun nearestIndex(x: Float, width: Int, points: List<Sample>, maxTime: Int): Int {
    if (width <= 0) return 0
    val t = (x / width * maxTime).toInt()
    var best = 0
    var bestDiff = Int.MAX_VALUE
    points.forEachIndexed { i, p ->
        val diff = kotlin.math.abs(p.timeOffsetSeconds - t)
        if (diff < bestDiff) { bestDiff = diff; best = i }
    }
    return best
}
