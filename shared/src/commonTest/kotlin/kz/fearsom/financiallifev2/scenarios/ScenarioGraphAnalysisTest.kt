package kz.fearsom.financiallifev2.scenarios

import kz.fearsom.financiallifev2.admin.ScenarioGraphDto
import kz.fearsom.financiallifev2.model.GameEvent
import kz.fearsom.financiallifev2.model.GameOption
import kz.fearsom.financiallifev2.model.MONTHLY_TICK
import kz.fearsom.financiallifev2.model.PoolEntry
import kz.fearsom.financiallifev2.scenarios.analysis.Band
import kz.fearsom.financiallifev2.scenarios.analysis.GraphWarning
import kz.fearsom.financiallifev2.scenarios.analysis.analyzeScenario
import kz.fearsom.financiallifev2.scenarios.analysis.toValidationReport
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

    private fun ending(id: String) = GameEvent(
        id = id,
        message = "the end",
        options = emptyList(),
        isEnding = true
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
        val b = ending("b")
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
        val end = ending("end")
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
        val c = ending("c")
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
        val end = ending("end")
        val cond = event("cond")
        val result = analyzeScenario(dto(events = listOf(intro, end), conditional = listOf(cond)))
        val condNode = result.nodes.firstOrNull { it.event.id == "cond" }
        assertNotNull(condNode, "conditional event should be a node in the analysis")
        assertTrue(condNode.isConditional)
    }

    // ── 6. Unreachable main event triggers a warning ──────────────────────────

    @Test
    fun `unreachable main event that is not in pool generates a WARN warning`() {
        val intro = event("intro", "end")
        val end = ending("end")
        val orphan = event("orphan")   // not reachable from intro, not in pool
        val result = analyzeScenario(dto(listOf(intro, end, orphan)), selfContained = true)
        assertTrue(
            result.warnings.any { it.severity == GraphWarning.Severity.WARN && it.eventId == "orphan" },
            "Expected a WARN about the unreachable event 'orphan'"
        )
    }

    // ── 7. Graph edge between conditional and main event is included ──────────

    @Test
    fun `edge from conditional event to main event appears in edge list`() {
        val intro = event("intro", "end")
        val end = ending("end")
        val cond = event("cond", "end")   // conditional event that leads to main event "end"
        val result = analyzeScenario(dto(events = listOf(intro, end), conditional = listOf(cond)))
        val condToEnd = result.edges.firstOrNull { it.fromId == "cond" && it.toId == "end" }
        assertNotNull(condToEnd, "Edge from conditional 'cond' to main 'end' must be in result.edges")
    }

    // ── 8. Self-contained graph without "intro" gets an ERROR ────────────────

    @Test
    fun `selfContained graph missing intro event produces ERROR`() {
        val a = event("a", "b")
        val b = ending("b")
        val result = analyzeScenario(dto(listOf(a, b)), selfContained = true)
        assertTrue(
            result.warnings.any { it.severity == GraphWarning.Severity.ERROR && it.message.contains("intro") },
            "Expected ERROR about missing 'intro' in a self-contained graph"
        )
    }

    // ── 9. Duplicate event IDs produce an ERROR ───────────────────────────────

    @Test
    fun `duplicate event id across events and conditional events produces ERROR`() {
        val intro = event("intro", "end")
        val end = ending("end")
        val dupCond = event("end")   // same id as a main event — authoring bug
        val result = analyzeScenario(dto(events = listOf(intro, end), conditional = listOf(dupCond)))
        assertTrue(
            result.warnings.any { it.severity == GraphWarning.Severity.ERROR && it.eventId == "end" },
            "Expected ERROR for duplicate id 'end'"
        )
    }

    // ── 10. Non-ending event with no options is a dead-end ERROR ─────────────

    @Test
    fun `non-ending event with no options produces ERROR about player getting stuck`() {
        val intro = event("intro", "dead")
        val dead = GameEvent(id = "dead", message = "dead end", options = emptyList(), isEnding = false)
        val result = analyzeScenario(dto(listOf(intro, dead)), selfContained = true)
        assertTrue(
            result.warnings.any { it.severity == GraphWarning.Severity.ERROR && it.eventId == "dead" },
            "Expected ERROR for non-ending event 'dead' with no options"
        )
    }

    // ── 11. MONTHLY_TICK as next target is NOT flagged as unresolved ──────────

    @Test
    fun `MONTHLY_TICK next target is not flagged as unresolved`() {
        val intro = event("intro", MONTHLY_TICK)
        val result = analyzeScenario(dto(listOf(intro)), selfContained = true)
        // The only warning may be "no ending" but NOT "unresolved option target"
        assertTrue(
            result.warnings.none { it.message.contains("not found") && it.eventId == "intro" },
            "MONTHLY_TICK must not be flagged as an unresolved option target"
        )
    }

    // ── 12. Graph with no ending events produces ERROR ────────────────────────

    @Test
    fun `graph with no ending events produces ERROR about story never finishing`() {
        val intro = event("intro", "other")
        val other = event("other", "intro")   // cycle, but no endings
        val result = analyzeScenario(dto(listOf(intro, other)))
        assertTrue(
            result.warnings.any { it.severity == GraphWarning.Severity.ERROR && it.message.contains("ending") },
            "Expected ERROR when graph has no ending events"
        )
    }

    // ── 13. toValidationReport correctly counts severities ───────────────────

    @Test
    fun `toValidationReport counts errors warnings and infos correctly`() {
        // selfContained + no intro → at least 1 ERROR; no-ending → another ERROR
        val intro = event("not_intro", "other")
        val other = event("other", "not_intro")
        val result = analyzeScenario(dto(listOf(intro, other)), selfContained = true)
        val report = result.toValidationReport()
        assertEquals(result.warnings.count { it.severity == GraphWarning.Severity.ERROR }, report.errors)
        assertEquals(result.warnings.count { it.severity == GraphWarning.Severity.WARN }, report.warnings)
        assertEquals(result.warnings.count { it.severity == GraphWarning.Severity.INFO }, report.infos)
    }
}
