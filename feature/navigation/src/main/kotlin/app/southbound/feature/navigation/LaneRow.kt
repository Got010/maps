package app.southbound.feature.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.southbound.core.routing.Lane
import app.southbound.core.routing.LaneDirection
import app.southbound.core.routing.LaneWording
import app.southbound.core.routing.Lanes
import app.southbound.core.routing.StepLanes
import uniffi.ferrostar.TripState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Lanes are drawn from this far before the maneuver they're for. */
internal const val LANES_SHOW_METERS = 1_000.0

/**
 * The lanes for the trip's next maneuver, drawn under the instruction banner
 * once it is within [LANES_SHOW_METERS]; nothing when Valhalla gave no lanes.
 */
@Composable
internal fun CurrentLanes(trip: TripState.Navigating, modifier: Modifier = Modifier) {
    val step = trip.remainingSteps.firstOrNull() ?: return
    if (trip.progress.distanceToNextManeuver > LANES_SHOW_METERS) return
    val lanes = remember(step) { StepLanes.of(step) }
    LaneRow(lanes, modifier)
}

/**
 * A row of lane arrows, left to right: the lanes to use bright, the others
 * dimmed; within a lane to use, the arrow the maneuver follows is bright.
 * Draws nothing unless [Lanes.worthShowing]. Reads the spoken advice
 * ("Use the left two lanes") to screen readers.
 */
@Composable
internal fun LaneRow(lanes: List<Lane>, modifier: Modifier = Modifier) {
    if (!Lanes.worthShowing(lanes)) return
    val use = remember(lanes) { Lanes.toUse(lanes).toSet() }
    val spoken = remember(lanes) { LaneWording.phrase(lanes)?.replaceFirstChar { it.uppercaseChar() } }
    val ink = MaterialTheme.colorScheme.onSurface
    val dim = ink.copy(alpha = DIM_ALPHA)
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = if (spoken != null) modifier.semantics { contentDescription = spoken } else modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            lanes.forEachIndexed { i, lane ->
                if (i > 0) Box(Modifier.width(1.dp).height(28.dp).background(dim))
                LaneGlyph(lane, i in use, ink, dim, Modifier.size(width = 30.dp, height = 34.dp))
            }
        }
    }
}

/** One lane's arrows, dim ones first so the bright one is drawn on top. */
@Composable
private fun LaneGlyph(lane: Lane, use: Boolean, ink: Color, dim: Color, modifier: Modifier) {
    val arrows = lane.directions.ifEmpty { listOf(LaneDirection.STRAIGHT) }
    val bright: (LaneDirection) -> Boolean = { use && (lane.use == null || it == lane.use) }
    Canvas(modifier) {
        val stroke = size.minDimension * 0.11f
        for (direction in arrows.sortedBy { bright(it) }) {
            drawLaneArrow(direction, if (bright(direction)) ink else dim, stroke)
        }
    }
}

/** An arrow from the bottom centre: up, then bent toward [direction], with a head. */
private fun DrawScope.drawLaneArrow(direction: LaneDirection, color: Color, stroke: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val path = Path().apply { moveTo(cx, h * 0.94f) }
    val tip: Offset
    // Radians from straight up, clockwise.
    val heading: Double
    if (direction == LaneDirection.UTURN) {
        // Up, over to the left in a half circle, and back down.
        val r = w * 0.2f
        val top = h * 0.4f
        path.lineTo(cx, top)
        for (k in 1..8) {
            val a = PI * k / 8
            path.lineTo((cx - r + r * cos(a)).toFloat(), (top - r * sin(a)).toFloat())
        }
        tip = Offset(cx - 2 * r, h * 0.66f)
        path.lineTo(tip.x, tip.y)
        heading = PI
    } else {
        val degrees = when (direction) {
            LaneDirection.SHARP_LEFT -> -135.0
            LaneDirection.LEFT -> -90.0
            LaneDirection.SLIGHT_LEFT -> -45.0
            LaneDirection.SLIGHT_RIGHT -> 45.0
            LaneDirection.RIGHT -> 90.0
            LaneDirection.SHARP_RIGHT -> 135.0
            else -> 0.0
        }
        heading = degrees * PI / 180
        if (degrees == 0.0) {
            tip = Offset(cx, h * 0.1f)
        } else {
            val bend = h * 0.5f
            path.lineTo(cx, bend)
            val len = w * 0.36f
            tip = Offset((cx + len * sin(heading)).toFloat(), (bend - len * cos(heading)).toFloat())
        }
        path.lineTo(tip.x, tip.y)
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    val head = w * 0.22f
    for (spread in listOf(-0.55, 0.55)) {
        val a = heading + PI + spread
        val end = Offset((tip.x + head * sin(a)).toFloat(), (tip.y - head * cos(a)).toFloat())
        drawLine(color, tip, end, strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

private const val DIM_ALPHA = 0.3f
