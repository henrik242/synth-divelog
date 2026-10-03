package no.synth.divelog.ui.dive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.Sample
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.format.Format

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
    if (points.size < 2) {
        Text("No profile samples", Modifier.padding(16.dp))
        return
    }

    val maxDepth = points.maxOf { it.depthMm!! }.coerceAtLeast(1)
    val maxTime = points.maxOf { it.timeOffsetSeconds }.coerceAtLeast(1)
    val temps = points.mapNotNull { it.temperatureMk }
    val minTemp = temps.minOrNull()
    val maxTemp = temps.maxOrNull()

    val depthColor = MaterialTheme.colorScheme.primary
    val tempColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val gasColor = MaterialTheme.colorScheme.secondary
    val warnColor = MaterialTheme.colorScheme.error
    val cursorColor = MaterialTheme.colorScheme.onSurface

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
                fun x(t: Int) = t.toFloat() / maxTime * w
                fun yDepth(mm: Int) = mm.toFloat() / maxDepth * h

                // Horizontal depth gridlines (quarters).
                for (i in 1..3) {
                    val y = h * i / 4f
                    drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                }

                // Depth trace.
                val depthPath = Path().apply {
                    moveTo(x(points[0].timeOffsetSeconds), yDepth(points[0].depthMm!!))
                    for (p in points.drop(1)) lineTo(x(p.timeOffsetSeconds), yDepth(p.depthMm!!))
                }
                drawPath(depthPath, depthColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))

                // Temperature trace, scaled into the lower portion of its own range.
                if (minTemp != null && maxTemp != null && maxTemp > minTemp) {
                    val span = (maxTemp - minTemp).toFloat()
                    val tempPath = Path()
                    var started = false
                    for (p in points) {
                        val mk = p.temperatureMk ?: continue
                        val ty = h - (mk - minTemp) / span * (h * 0.3f) - 2f
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
                    drawLine(color, Offset(ex, 0f), Offset(ex, h), strokeWidth = 1.5f)
                }

                // Scrub cursor.
                cursor?.let { idx ->
                    val p = points[idx]
                    val cx = x(p.timeOffsetSeconds)
                    drawLine(cursorColor, Offset(cx, 0f), Offset(cx, h), strokeWidth = 1.5f)
                    drawCircle(depthColor, radius = 5f, center = Offset(cx, yDepth(p.depthMm!!)))
                }
            }
        }

        val readout = cursor?.let { idx ->
            val p = points[idx]
            buildString {
                append(Format.duration(p.timeOffsetSeconds)).append("  ")
                append(Format.depth(p.depthMm, unitSystem))
                p.temperatureMk?.let { append("  ").append(Format.temperature(it, unitSystem)) }
            }
        } ?: "Max ${Format.depth(maxDepth, unitSystem)} · ${Format.duration(maxTime)}"
        Text(readout, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
    }
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
