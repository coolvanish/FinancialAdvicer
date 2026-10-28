package kz.fearsom.financiallifev2.scenarios

import kz.fearsom.financiallifev2.admin.ScenarioGraphDto
import kz.fearsom.financiallifev2.model.GameEvent
import kz.fearsom.financiallifev2.model.GameOption
import kz.fearsom.financiallifev2.model.PoolEntry
import kz.fearsom.financiallifev2.scenarios.analysis.Band
import kz.fearsom.financiallifev2.scenarios.analysis.GraphWarning
import kz.fearsom.financiallifev2.scenarios.analysis.analyzeScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for [analyzeScenario] covering root election, conditional-event edge handling,
 *  and warning generation for common authoring mistakes. */
class ScenarioGraphAnalysisTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun event(id: String, vararg nextIds: String) = GameEvent(
        id = id,
        message = "msg",
        options = nextIds.mapIndexed { i, next ->
            GameOption(id = "${id}_opt$i", text = "opt $i", emoji = "▶", next = next)
        }
    )

    private fun dto(
        events: List<GameEvent>,
        conditional: List<GameEvent> = emptyList(),
        pool: List<PoolEntry> = emptyList()
    ) = ScenarioGraphDto(events = events, conditionalEvents = conditional, eventPool = pool)

    // ── 1. "intro" node always wins root election ─────────────────────────────

    @Test
    fun `intro is elected root even when it has incoming edges`() {
        // A → intro → B  (intro has an incoming edge from A, but "intro" id takes priority)
        val a = event("a", "intro")
        val intro = event("intro", "b")
        val b = event("b")
        val result = analyzeScenario(dto(listOf(a, intro, b)))
        assertEquals("intro", result.rootId)
        val introNode = result.nodes.first { it.event.id == "intro" }
        assertTrue(introNode.isRoot)
    }

    // ── 2. Conditional event pointing to a main event must not block root ─────

    @Test
    fun `conditional event targeting a main event does not disqualify that event from being root`() {
        // Graph: intro → end.  Conditional event cond → intro (points back to intro).
        // Without the fix, `targeted` would include "intro" (because cond→intro is an edge),
        // so the fallback election skips "intro" and picks the wrong root.
        val intro = event("intro", "end")
        val end = event("end")
        val cond = event("cond", "intro")   // conditional event pointing to intro
        val result = analyzeScenario(dto(events = listOf(intro, end), conditional = listOf(cond)))
        assertEquals("intro", result.rootId)
    }

    // ── 3. Event with no incoming edge (no "intro") is elected root ───────────

    @Test
    fun `event with no incoming main edge is elected root when intro absent`() {
        // a → b → c (a has no incoming edge)
        val a = event("a", "b")
        val b = event("b", "c")
        val c = event("c")
        val result = analyzeScenario(dto(listOf(a, b, c)))
        assertEquals("a", result.rootId)
    }

    // ── 4. Empty event list produces an ERROR warning ─────────────────────────

    @Test
    fun `empty graph returns error warning and null rootId`() {
        val result = analyzeScenario(dto(emptyList()))
        assertNull(result.rootId)
        assertTrue(result.warnings.any { it.severity == GraphWarning.Severity.ERROR })
    }

    // ── 5. Conditional events are placed in a separate band ───────────────────

    @Test
    fun `conditional events appear in SECONDARY band separate from main events`() {
        val intro = event("intro", "end")
        val end = event("end")
        val cond = event("cond")
        val result = analyzeScenario(dto(events = listOf(intro, end), conditional = listOf(cond)))
        val condNode = result.nodes.firstOrNull { it.event.id == "cond" }
        assertNotNull(condNode, "conditional event should be a node in the analysis")
        assertTrue(condNode.isConditional)
    }

    // ── 6. Unreachable main event triggers a warning ──────────────────────────

    @Test
    fun `unreachable main event that is not in pool generates a warning`() {
        val intro = event("intro", "end")
        val end = event("end")
        val orphan = event("orphan")   // not reachable from intro, not in pool
        val result = analyzeScenario(dto(listOf(intro, end, orphan)), selfContained = false)
        assertTrue(
            result.warnings.any { it.severity == GraphWarning.Severity.WARN && it.eventId == "orphan" },
            "Expected a WARN-level warning specifically for 'orphan'"
        )
    }

    // ── 7. Graph edge between conditional and main event is included ──────────

    @Test
    fun `edge from conditional event to main event appears in edge list`() {
        val intro = event("intro", "end")
        val end = event("end")
        val cond = event("cond", "end")   // conditional event that leads to main event "end"
        val result = analyzeScenario(dto(events = listOf(intro, end), conditional = listOf(cond)))
        val condToEnd = result.edges.firstOrNull { it.fromId == "cond" && it.toId == "end" }
        assertNotNull(condToEnd, "Edge from conditional 'cond' to main 'end' must be in result.edges")
    }

    // ── 8. Conditional event targeting a *non-intro* root must not block election ─
    //       This is the real regression test for the conditional-edge fix: "intro" always
    //       wins the when-branch before the fix is needed, so test #2 never actually
    //       exercises the targeted-set logic. This test removes "intro" so the fallback
    //       election runs, and verifies the conditional edge does not disqualify the root.

    @Test
    fun `conditional event targeting non-intro root does not prevent correct root election`() {
        // Graph: start → end.  Conditional event cond → start.
        // Without the fix, edges.filter { it.fromId in byId } would *include* cond→start
        // (cond is in byId only if we forget the filter), adding "start" to targeted and
        // causing the fallback to pick "end" as root instead of "start".
        val start = event("start", "end")
        val end = event("end")
        val cond = event("cond", "start")  // conditional event points to the natural root
        val result = analyzeScenario(dto(events = listOf(start, end), conditional = listOf(cond)))
        assertEquals("start", result.rootId,
            "root must be 'start' — a conditional event pointing to it must not disqualify it")
    }

    // ── 9. Conditional events are assigned Band.SECONDARY (not Band.MAIN) ─────

    @Test
    fun `conditional events are placed in Band SECONDARY`() {
        val intro = event("intro", "end")
        val end = event("end")
        val cond = event("cond")
        val result = analyzeScenario(dto(events = listOf(intro, end), conditional = listOf(cond)))
        val condNode = result.nodes.firstOrNull { it.event.id == "cond" }
        assertNotNull(condNode, "conditional event must be present as a node")
        assertEquals(Band.SECONDARY, condNode.band,
            "conditional events must live in the SECONDARY band, not MAIN")
    }

    // ── 10. Duplicate event id across events and conditionalEvents is an ERROR ─

    @Test
    fun `duplicate event id across events and conditional events is an error`() {
        val intro = event("intro", "end")
        val end = event("end")
        val dupCond = event("intro")  // same id as the main "intro" event
        val result = analyzeScenario(dto(events = listOf(intro, end), conditional = listOf(dupCond)))
        assertTrue(
            result.warnings.any { it.severity == GraphWarning.Severity.ERROR && it.eventId == "intro" },
            "Duplicate id 'intro' spanning events and conditionalEvents must produce an ERROR"
        )
    }
}
