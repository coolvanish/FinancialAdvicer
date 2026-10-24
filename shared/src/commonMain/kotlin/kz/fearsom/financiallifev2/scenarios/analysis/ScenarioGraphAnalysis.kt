package kz.fearsom.financiallifev2.scenarios.analysis

import kz.fearsom.financiallifev2.admin.ScenarioGraphDto
import kz.fearsom.financiallifev2.admin.StoryValidationIssue
import kz.fearsom.financiallifev2.admin.StoryValidationReport
import kz.fearsom.financiallifev2.model.GameEvent
import kz.fearsom.financiallifev2.model.MONTHLY_TICK

/**
 * Pure, UI-free analysis of a [ScenarioGraphDto]. Computes a layered layout and a
 * set of validation warnings.
 *
 * Lives in :shared so it is used by BOTH:
 *  - the :admin SPA (scenario viewer + story editor live validation), and
 *  - the :server (publish-time validation of DB-backed stories).
 *
 * Engine facts this relies on (see GameEngine / ScenarioGraph):
 *  - The narrative entry point is the event whose id is "intro".
 *  - Story `events` are entered two ways: directly via `option.next`, OR after a
 *    MONTHLY_TICK the engine draws from `eventPool` / conditional events. So an
 *    event being unreachable via direct `next` edges is NOT a bug if it is a pool
 *    entry or a scheduled-consequence target — only true orphans are flagged.
 *  - `MONTHLY_TICK` is a sentinel `next`, not a concrete event id.
 *  - Endings are leaf nodes and should carry empty `options`.
 */

/** Which visual band a node belongs to: the main narrative tree, or the pool/other grid. */
enum class Band { MAIN, SECONDARY }

/** A laid-out story event. [rank]/[column] are abstract grid coordinates (caller scales to px). */
data class GraphNode(
    val event: GameEvent,
    val rank: Int,
    val column: Int,
    val band: Band,
    val isRoot: Boolean,
    val reachable: Boolean,
    /** true → lives in dto.conditionalEvents (state-triggered after a tick, not via `next`). */
    val isConditional: Boolean = false
)

/** A directed edge between two story events (target resolved within `events`). */
data class GraphEdge(
    val fromId: String,
    val toId: String,
    val optionId: String
)

/** A validation finding surfaced in the inspector. */
data class GraphWarning(
    val severity: Severity,
    val eventId: String,
    val message: String
) {
    enum class Severity { ERROR, WARN, INFO }
}

data class ScenarioStats(
    val eventCount: Int,
    val endingCount: Int,
    val optionCount: Int,
    val conditionalCount: Int,
    val poolCount: Int
)

data class ScenarioAnalysis(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val warnings: List<GraphWarning>,
    val rootId: String?,
    val rankCount: Int,
    val columnCount: Int,
    val stats: ScenarioStats
) {
    fun node(id: String): GraphNode? = nodes.firstOrNull { it.event.id == id }
}

/** Serializable report for the admin API (publish gating / dry-run validation). */
fun ScenarioAnalysis.toValidationReport(): StoryValidationReport {
    val issues = warnings.map {
        StoryValidationIssue(
            severity = it.severity.name,
            eventId  = it.eventId,
            message  = it.message
        )
    }
    return StoryValidationReport(
        errors   = warnings.count { it.severity == GraphWarning.Severity.ERROR },
        warnings = warnings.count { it.severity == GraphWarning.Severity.WARN },
        infos    = warnings.count { it.severity == GraphWarning.Severity.INFO },
        issues   = issues
    )
}

private const val SECONDARY_GRID_COLUMNS = 4

/**
 * @param selfContained when true, the graph must run entirely on its own events:
 *   a missing `intro` root and any option/pool target that resolves to no known
 *   event are ERRORS (not warnings). DB-backed stories are self-contained — the
 *   engine hard-fails without `intro` and stalls on an unresolved `next` — so the
 *   admin editor and the server publish gate pass `true`. The read-only built-in
 *   scenario viewer passes `false`: code graphs may reference shared scam/era
 *   library events that live outside the per-character graph.
 */
fun analyzeScenario(dto: ScenarioGraphDto, selfContained: Boolean = false): ScenarioAnalysis {
    val events = dto.events
    val byId = events.associateBy { it.id }

    if (events.isEmpty()) {
        return ScenarioAnalysis(
            nodes = emptyList(), edges = emptyList(),
            warnings = listOf(
                GraphWarning(GraphWarning.Severity.ERROR, "-", "graph has no events — add an 'intro' event")
            ),
            rootId = null, rankCount = 0, columnCount = 0,
            stats = ScenarioStats(0, 0, 0, dto.conditionalEvents.size, dto.eventPool.size)
        )
    }

    // ── Direct edges (target must resolve to a laid-out node: story OR conditional).
    // Conditional events are laid out too (own band below the pool grid) so the
    // graph view shows how they weave into the narrative; edges FROM conditional
    // events are included so their continuations are visible.
    val condById = dto.conditionalEvents.associateBy { it.id }
    val laidOutIds = byId.keys + condById.keys
    val edges = (events + dto.conditionalEvents).flatMap { e ->
        e.options.mapNotNull { o ->
            if (o.next in laidOutIds) GraphEdge(e.id, o.next, o.id) else null
        }
    }
    val adjacency: Map<String, List<String>> =
        edges.groupBy { it.fromId }.mapValues { (_, es) -> es.map { it.toId } }

    // ── Root: prefer "intro", else an event with no incoming edge, else first ─
    // Only count edges FROM main events when computing targeted: edges from
    // conditional events must not disqualify a main event from being elected root.
    val targeted = edges.filter { it.fromId in byId }.map { it.toId }.toSet()
    val rootId = when {
        "intro" in byId -> "intro"
        else -> events.firstOrNull { it.id !in targeted }?.id ?: events.first().id
    }

    // ── BFS rank from root over direct edges (main narrative tree) ────────────
    val rankOf = HashMap<String, Int>()
    run {
        val queue = ArrayDeque<String>()
        rankOf[rootId] = 0
        queue.add(rootId)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val curRank = rankOf.getValue(cur)
            for (next in adjacency[cur].orEmpty()) {
                if (next !in rankOf) {
                    rankOf[next] = curRank + 1
                    queue.add(next)
                }
            }
        }
    }
    val mainReachable = rankOf.keys.toSet()

    // ── Legitimate non-direct entry points: pool entries + scheduled targets ──
    val poolIds = dto.eventPool.map { it.eventId }.filter { it in byId }.toSet()
    val scheduledIds = events
        .flatMap { it.options }
        .mapNotNull { it.effects.scheduleEvent?.eventId }
        .filter { it in byId }
        .toSet()

    // ── Full reachability: BFS from root AND every legitimate non-direct entry,
    // over direct edges. An event reached only via a pool entry (after a tick) is
    // therefore NOT mis-flagged as an orphan. Conditional events are seeds too —
    // the engine enters them whenever their state conditions hold — so an event
    // reachable only through a conditional event's option is not an orphan either.
    // (This only relaxes warnings; it can never introduce new ERRORs.)
    val fullReachable: Set<String> = run {
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>()
        for (r in (setOf(rootId) + poolIds + scheduledIds + condById.keys)) {
            if (r in laidOutIds && seen.add(r)) queue.add(r)
        }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (next in adjacency[cur].orEmpty()) if (seen.add(next)) queue.add(next)
        }
        seen
    }

    // ── Main-band nodes, grouped by rank, columns by encounter order ──────────
    val mainNodes = ArrayList<GraphNode>()
    val maxMainRank = mainReachable.maxOfOrNull { rankOf.getValue(it) } ?: 0
    for (rank in 0..maxMainRank) {
        val atRank = events.filter { it.id in mainReachable && rankOf.getValue(it.id) == rank }
        atRank.forEachIndexed { col, e ->
            mainNodes += GraphNode(
                event = e, rank = rank, column = col,
                band = Band.MAIN, isRoot = e.id == rootId, reachable = true
            )
        }
    }

    // ── Secondary band: everything not in the main tree, pool entries first ───
    val secondaryEvents = run {
        val notMain = events.filter { it.id !in mainReachable }
        val pooledFirst = notMain.filter { it.id in poolIds }
        val rest = notMain.filter { it.id !in poolIds }
        pooledFirst + rest
    }
    val secondaryStartRank = maxMainRank + 2
    val secondaryNodes = secondaryEvents.mapIndexed { idx, e ->
        val reachable = e.id in fullReachable
        GraphNode(
            event = e,
            rank = secondaryStartRank + idx / SECONDARY_GRID_COLUMNS,
            column = idx % SECONDARY_GRID_COLUMNS,
            band = Band.SECONDARY,
            isRoot = false,
            reachable = reachable
        )
    }

    // ── Conditional band: grid below the secondary band ───────────────────────
    val secondaryRows =
        if (secondaryEvents.isEmpty()) 0
        else (secondaryEvents.size - 1) / SECONDARY_GRID_COLUMNS + 1
    val condStartRank = secondaryStartRank + secondaryRows +
        if (dto.conditionalEvents.isEmpty()) 0 else 1
    val conditionalNodes = dto.conditionalEvents.mapIndexed { idx, e ->
        GraphNode(
            event = e,
            rank = condStartRank + idx / SECONDARY_GRID_COLUMNS,
            column = idx % SECONDARY_GRID_COLUMNS,
            band = Band.SECONDARY,
            isRoot = false,
            reachable = true, // entered by the engine whenever its conditions hold
            isConditional = true
        )
    }

    val nodes = mainNodes + secondaryNodes + conditionalNodes
    val columnCount = (nodes.maxOfOrNull { it.column } ?: 0) + 1
    val rankCount = (nodes.maxOfOrNull { it.rank } ?: 0) + 1

    // ── Warnings ──────────────────────────────────────────────────────────────
    val warnings = ArrayList<GraphWarning>()
    val knownIds = byId.keys + dto.conditionalEvents.map { it.id }.toSet()

    // Self-contained (DB) stories: unresolved targets stall the engine → ERROR.
    // Built-in graphs may reference shared library events → WARN.
    val unresolvedSeverity =
        if (selfContained) GraphWarning.Severity.ERROR else GraphWarning.Severity.WARN

    // GameEngine starts every story at the "intro" event; without it the story
    // can never begin, so a self-contained graph missing "intro" is unpublishable.
    if (selfContained && "intro" !in byId) {
        warnings += GraphWarning(
            GraphWarning.Severity.ERROR, rootId,
            "no 'intro' event — the engine starts every story at 'intro'"
        )
    }

    // Duplicate ids across events + conditionalEvents (authoring error a form editor can produce)
    val duplicateIds = (events.map { it.id } + dto.conditionalEvents.map { it.id })
        .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    for (dup in duplicateIds) {
        warnings += GraphWarning(
            GraphWarning.Severity.ERROR, dup,
            "duplicate event id '$dup' — ids must be unique across events and conditional events"
        )
    }

    for (e in events) {
        // Unresolved option targets: ERROR for self-contained stories (engine stalls),
        // WARN for built-in graphs (may be a shared scam/era-library event).
        for (o in e.options) {
            if (o.next != MONTHLY_TICK && o.next !in knownIds) {
                warnings += GraphWarning(
                    unresolvedSeverity, e.id,
                    "option '${o.id}' → '${o.next}' not found in this graph" +
                        if (selfContained) " — the engine has nowhere to go and stalls"
                        else " (ok if it is a shared scam/era-library event)"
                )
            }
        }
        // Dead-end: a non-ending event the player cannot leave
        if (!e.isEnding && e.options.isEmpty()) {
            warnings += GraphWarning(
                GraphWarning.Severity.ERROR, e.id,
                "non-ending event has no options — player gets stuck"
            )
        }
        // Ending hygiene
        if (e.isEnding && e.options.isNotEmpty()) {
            warnings += GraphWarning(
                GraphWarning.Severity.WARN, e.id,
                "ending event has ${e.options.size} option(s) — endings should be leaves"
            )
        }
        if (e.isEnding && e.endingType == null) {
            warnings += GraphWarning(
                GraphWarning.Severity.INFO, e.id, "ending has no endingType set"
            )
        }
        // True orphan: unreachable from the root, the event pool, and any scheduled consequence
        if (e.id != rootId && e.id !in fullReachable) {
            warnings += GraphWarning(
                GraphWarning.Severity.WARN, e.id,
                "unreachable: not reachable from '$rootId', not in the event pool, " +
                    "and not a scheduled consequence"
            )
        }
    }

    // Pool entries pointing at unknown events
    for (p in dto.eventPool) {
        if (p.eventId !in knownIds) {
            warnings += GraphWarning(
                unresolvedSeverity, p.eventId,
                "pool entry → '${p.eventId}' not found in events/conditional events"
            )
        }
    }

    // A playable story needs at least one reachable ending
    if (events.none { it.isEnding }) {
        warnings += GraphWarning(
            GraphWarning.Severity.ERROR, rootId,
            "graph has no ending events — the story can never finish"
        )
    }

    val stats = ScenarioStats(
        eventCount = events.size,
        endingCount = events.count { it.isEnding },
        optionCount = events.sumOf { it.options.size },
        conditionalCount = dto.conditionalEvents.size,
        poolCount = dto.eventPool.size
    )

    return ScenarioAnalysis(
        nodes = nodes,
        edges = edges,
        warnings = warnings.sortedBy { it.severity.ordinal },
        rootId = rootId,
        rankCount = rankCount,
        columnCount = columnCount,
        stats = stats
    )
}
