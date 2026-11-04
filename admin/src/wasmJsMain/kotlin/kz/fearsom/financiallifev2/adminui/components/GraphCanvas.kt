package kz.fearsom.financiallifev2.adminui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.fearsom.financiallifev2.model.EndingType
import kz.fearsom.financiallifev2.model.MONTHLY_TICK
import kz.fearsom.financiallifev2.scenarios.analysis.GraphNode
import kz.fearsom.financiallifev2.scenarios.analysis.ScenarioAnalysis

// ── Canvas layout constants (world units) ───────────────────────────────────────
private const val NODE_W = 168f
private const val NODE_H = 56f
private const val H_GAP  = 28f
private const val V_GAP  = 44f
private const val PAD    = 28f

private const val MIN_SCALE = 0.25f
private const val MAX_SCALE = 3f

/** Color coding for ending nodes, shared by the scenario viewer and story editor. */
fun endingColor(type: EndingType?): Color = when (type) {
    EndingType.WEALTH               -> Color(0xFF2E7D32)
    EndingType.FINANCIAL_FREEDOM    -> Color(0xFF388E3C)
    EndingType.FINANCIAL_STABILITY  -> Color(0xFF1565C0)
    EndingType.PAYCHECK_TO_PAYCHECK -> Color(0xFFF9A825)
    EndingType.BANKRUPTCY           -> Color(0xFFC62828)
    null                            -> Color(0xFF6A6A6A)
}

/**
 * Viewport state for [ScenarioCanvas]. Hoist it (via [rememberScenarioCanvasState])
 * to keep pan/zoom alive while the canvas leaves composition — e.g. the story
 * editor's Form ⇄ Graph toggle. The default (un-hoisted) state resets whenever the
 * canvas re-enters composition, which is what the read-only scenario viewer wants.
 */
@Stable
class ScenarioCanvasState internal constructor() {
    var viewScale by mutableStateOf(1f)
    var offset by mutableStateOf(Offset(PAD, PAD))
    internal var canvasSize by mutableStateOf(IntSize.Zero)
    internal var didAutoFit by mutableStateOf(false)
}

@Composable
fun rememberScenarioCanvasState(): ScenarioCanvasState = remember { ScenarioCanvasState() }

// ── Pre-measured render model (text layout is expensive; never do it per frame) ──

private class NodeRender(
    val node: GraphNode,
    val topLeft: Offset,
    val fill: Color,
    val label: TextLayoutResult,
    val tick: TextLayoutResult?
)

private class EdgeRender(
    val fromId: String,
    val start: Offset,
    val end: Offset,
    val label: TextLayoutResult,
    val labelHighlighted: TextLayoutResult
)

private class RenderModel(val nodes: List<NodeRender>, val edges: List<EdgeRender>)

private fun nodeTopLeft(n: GraphNode) = Offset(
    x = PAD + n.column * (NODE_W + H_GAP),
    y = PAD + n.rank * (NODE_H + V_GAP)
)

private fun buildRenderModel(
    analysis: ScenarioAnalysis,
    measurer: TextMeasurer,
    normalFill: Color,
    normalText: Color,
    rootFill: Color,
    rootText: Color,
    tickColor: Color,
    edgeHighlight: Color
): RenderModel {
    val nodes = analysis.nodes.map { n ->
        val ending = n.event.isEnding
        val fill = when {
            ending   -> endingColor(n.event.endingType).copy(alpha = 0.9f)
            n.isRoot -> rootFill
            else     -> normalFill
        }
        val textColor = when {
            ending   -> Color.White
            n.isRoot -> rootText
            else     -> normalText
        }
        val subtitle =
            if (ending) "🏁 ${n.event.endingType ?: "ending"}"
            else n.event.message.replace('\n', ' ').take(40)
        val prefix = if (n.isConditional) "◈ " else ""
        val label = "$prefix${n.event.flavor} ${n.event.id}" +
            if (subtitle.isNotBlank()) "\n$subtitle" else ""
        NodeRender(
            node = n,
            topLeft = nodeTopLeft(n),
            fill = fill,
            label = measurer.measure(
                text = label,
                style = TextStyle(color = textColor, fontSize = 11.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = (NODE_W - 16).toInt())
            ),
            tick = if (!ending && n.event.options.any { it.next == MONTHLY_TICK }) {
                measurer.measure(
                    text = "⟳",
                    style = TextStyle(
                        color = if (n.isRoot) rootText else tickColor,
                        fontSize = 12.sp
                    )
                )
            } else null
        )
    }

    val posById = nodes.associate { it.node.event.id to it.topLeft }
    val edges = analysis.edges.mapNotNull { edge ->
        val from = posById[edge.fromId] ?: return@mapNotNull null
        val to   = posById[edge.toId] ?: return@mapNotNull null
        val opt = analysis.node(edge.fromId)?.event?.options?.firstOrNull { it.id == edge.optionId }
        val text = opt?.let { "${it.emoji} ${it.text}".trim().ifBlank { edge.optionId } } ?: edge.optionId
        fun measureLabel(color: Color) = measurer.measure(
            text = text,
            style = TextStyle(color = color, fontSize = 9.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = 150)
        )
        EdgeRender(
            fromId = edge.fromId,
            start = Offset(from.x + NODE_W / 2f, from.y + NODE_H),
            end   = Offset(to.x + NODE_W / 2f, to.y),
            label = measureLabel(normalText),
            labelHighlighted = measureLabel(edgeHighlight)
        )
    }
    return RenderModel(nodes, edges)
}

/**
 * Pannable/zoomable canvas rendering a [ScenarioAnalysis].
 *
 * Interactions:
 *  - drag / pinch — pan & zoom (mouse wheel also zooms, anchored at the cursor)
 *  - tap a node — select (tap empty space to deselect)
 *  - selected node's outgoing edges are highlighted and always show option labels;
 *    all labels appear once zoomed in past ~90%
 *  - ⟳ marker — the event has a MONTHLY_TICK option (not a dead-end)
 *  - ◈ + dashed outline — conditional event (state-triggered after a tick)
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ScenarioCanvas(
    analysis: ScenarioAnalysis,
    selectedEventId: String?,
    onSelect: (String?) -> Unit,
    state: ScenarioCanvasState = rememberScenarioCanvasState()
) {
    val currentOnSelect by rememberUpdatedState(onSelect)
    val measurer = rememberTextMeasurer()

    // Theme colors captured for use inside the (non-composable) DrawScope.
    val cs = MaterialTheme.colorScheme
    val edgeColor       = cs.outline.copy(alpha = 0.7f)
    val edgeHighlight   = cs.primary
    val labelBg         = cs.surface.copy(alpha = 0.88f)
    val normalFill      = cs.surfaceVariant
    val normalText      = cs.onSurfaceVariant
    val rootFill        = cs.primaryContainer
    val rootText        = cs.onPrimaryContainer
    val selectedBorder  = cs.primary
    val unreachBorder   = cs.error
    val condBorder      = cs.tertiary
    val canvasBg        = cs.surface

    // All text measured once per analysis/theme — draw frames only blit cached layouts.
    val render = remember(analysis, cs) {
        buildRenderModel(analysis, measurer, normalFill, normalText, rootFill, rootText, condBorder, edgeHighlight)
    }
    val currentRender by rememberUpdatedState(render)

    fun hitTest(tap: Offset): GraphNode? {
        val world = (tap - state.offset) / state.viewScale
        return currentRender.nodes.firstOrNull { r ->
            world.x in r.topLeft.x..(r.topLeft.x + NODE_W) &&
                world.y in r.topLeft.y..(r.topLeft.y + NODE_H)
        }?.node
    }

    fun contentSize(): Size {
        val maxX = (analysis.nodes.maxOfOrNull { PAD + it.column * (NODE_W + H_GAP) } ?: 0f) + NODE_W + PAD
        val maxY = (analysis.nodes.maxOfOrNull { PAD + it.rank * (NODE_H + V_GAP) } ?: 0f) + NODE_H + PAD
        return Size(maxX, maxY)
    }

    fun fitToContent() {
        if (analysis.nodes.isEmpty() || state.canvasSize == IntSize.Zero) return
        val c = contentSize()
        val s = minOf(state.canvasSize.width / c.width, state.canvasSize.height / c.height)
            .coerceIn(MIN_SCALE, 1.25f)
        state.viewScale = s
        state.offset = Offset(
            (state.canvasSize.width - c.width * s) / 2f,
            (state.canvasSize.height - c.height * s) / 2f
        )
    }

    // When the analysis itself changes (new scenario loaded), reset the guard so the
    // new graph gets auto-fitted once regardless of canvas size staying the same.
    LaunchedEffect(analysis) { state.didAutoFit = false }

    // Auto-fit exactly once per analysis (first non-zero canvas size); window resizes
    // after the initial fit never yank the viewport away from the user.
    LaunchedEffect(state.canvasSize, analysis) {
        if (!state.didAutoFit && state.canvasSize.width > 0 && analysis.nodes.isNotEmpty()) {
            fitToContent()
            state.didAutoFit = true
        }
    }

    Box(
        Modifier.fillMaxSize().clipToBounds().background(canvasBg)
            .onSizeChanged { state.canvasSize = it }
    ) {
        if (analysis.nodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Empty graph.", color = normalText)
            }
        } else {
            Canvas(
                modifier = Modifier.fillMaxSize()
                    // Mouse-wheel zoom anchored at the cursor.
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        val change = event.changes.firstOrNull() ?: return@onPointerEvent
                        val delta = change.scrollDelta.y
                        if (delta == 0f) return@onPointerEvent
                        val factor = if (delta > 0f) 1f / 1.15f else 1.15f
                        val newScale = (state.viewScale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
                        state.offset = change.position -
                            (change.position - state.offset) * (newScale / state.viewScale)
                        state.viewScale = newScale
                        event.changes.forEach { it.consume() }
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val newScale = (state.viewScale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                            state.offset = centroid -
                                (centroid - state.offset) * (newScale / state.viewScale) + pan
                            state.viewScale = newScale
                        }
                    }
                    .pointerInput(Unit) {
                        // Single-tap only: registering a double-tap handler would delay
                        // selection by the double-tap timeout (~300ms).
                        detectTapGestures { tap -> currentOnSelect(hitTest(tap)?.event?.id) }
                    }
            ) {
                translate(left = state.offset.x, top = state.offset.y) {
                    scale(scaleX = state.viewScale, scaleY = state.viewScale, pivot = Offset.Zero) {
                        val showAllLabels = state.viewScale >= 0.9f

                        // ── Edges (under nodes); selected node's outgoing edges on top ──
                        val (highlighted, normal) = render.edges.partition { it.fromId == selectedEventId }
                        for (edge in normal + highlighted) {
                            val isHi = edge.fromId == selectedEventId
                            val color = if (isHi) edgeHighlight else edgeColor
                            val width = if (isHi) 2.6f else 1.6f
                            drawLine(color, edge.start, edge.end, strokeWidth = width)
                            // Arrival chevron pointing into the target's top edge.
                            val end = edge.end
                            drawLine(color, end, Offset(end.x - 5f, end.y - 8f), strokeWidth = width)
                            drawLine(color, end, Offset(end.x + 5f, end.y - 8f), strokeWidth = width)

                            // ── Cached option label at the edge midpoint ──
                            if (isHi || showAllLabels) {
                                val layout = if (isHi) edge.labelHighlighted else edge.label
                                val mid = (edge.start + edge.end) / 2f
                                val tl = Offset(
                                    mid.x - layout.size.width / 2f,
                                    mid.y - layout.size.height / 2f
                                )
                                drawRoundRect(
                                    color = labelBg,
                                    topLeft = Offset(tl.x - 4f, tl.y - 2f),
                                    size = Size(layout.size.width + 8f, layout.size.height + 4f),
                                    cornerRadius = CornerRadius(4f, 4f)
                                )
                                drawText(layout, topLeft = tl)
                            }
                        }

                        // ── Nodes ──
                        for (r in render.nodes) {
                            val n = r.node
                            val tl = r.topLeft
                            drawRoundRect(
                                color = r.fill,
                                topLeft = tl,
                                size = Size(NODE_W, NODE_H),
                                cornerRadius = CornerRadius(10f, 10f)
                            )
                            // Border: selected > unreachable > conditional > none.
                            when {
                                n.event.id == selectedEventId -> drawRoundRect(
                                    color = selectedBorder, topLeft = tl, size = Size(NODE_W, NODE_H),
                                    cornerRadius = CornerRadius(10f, 10f),
                                    style = Stroke(width = 3f)
                                )
                                !n.reachable -> drawRoundRect(
                                    color = unreachBorder, topLeft = tl, size = Size(NODE_W, NODE_H),
                                    cornerRadius = CornerRadius(10f, 10f),
                                    style = Stroke(
                                        width = 1.5f,
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                                    )
                                )
                                n.isConditional -> drawRoundRect(
                                    color = condBorder, topLeft = tl, size = Size(NODE_W, NODE_H),
                                    cornerRadius = CornerRadius(10f, 10f),
                                    style = Stroke(
                                        width = 1.5f,
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))
                                    )
                                )
                            }
                            drawText(r.label, topLeft = Offset(tl.x + 8f, tl.y + 8f))
                            r.tick?.let { tick ->
                                drawText(
                                    tick,
                                    topLeft = Offset(
                                        tl.x + NODE_W - tick.size.width - 6f,
                                        tl.y + NODE_H - tick.size.height - 2f
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Zoom / fit / reset overlay.
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SmallControl("−") { state.viewScale = (state.viewScale * 0.8f).coerceIn(MIN_SCALE, MAX_SCALE) }
                SmallControl("+") { state.viewScale = (state.viewScale * 1.25f).coerceIn(MIN_SCALE, MAX_SCALE) }
                SmallControl("Fit") { fitToContent() }
                SmallControl("Reset") { state.viewScale = 1f; state.offset = Offset(PAD, PAD) }
            }
        }
    }
}

@Composable
private fun SmallControl(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}
