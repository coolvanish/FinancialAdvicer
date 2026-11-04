package kz.fearsom.financiallifev2.scenarios

import kz.fearsom.financiallifev2.model.GameEvent
import kz.fearsom.financiallifev2.model.PlayerState
import kz.fearsom.financiallifev2.model.PoolEntry
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNull

class EventPoolSelectorTest {

    private val baseState = PlayerState(
        characterId = "test",
        eraId = "kz_2024",
        month = 6,
        year = 2024
    )

    private fun event(id: String, cooldown: Int = 0) = GameEvent(
        id = id,
        message = "msg",
        options = emptyList(),
        cooldownMonths = cooldown
    )

    @Test
    fun `empty pool returns null`() {
        val result = EventPoolSelector.selectNext(
            pool = emptyList(),
            allEvents = { null },
            state = baseState,
            random = Random(0)
        )
        assertNull(result, "selectNext on an empty pool must return null")
    }

    @Test
    fun `pool with all events in cooldown returns null`() {
        // The only pool event has an active cooldown that has not yet expired.
        val evt = event("cooldown_event", cooldown = 3)
        val absMonth = baseState.absoluteMonth  // 2024*12+6 = 24294
        val stateWithCooldown = baseState.copy(
            eventCooldowns = mapOf("cooldown_event" to absMonth + 2)  // expires 2 months ahead
        )

        val result = EventPoolSelector.selectNext(
            pool = listOf(PoolEntry("cooldown_event", 100)),
            allEvents = { id -> if (id == "cooldown_event") evt else null },
            state = stateWithCooldown,
            random = Random(0)
        )
        assertNull(result, "selectNext must return null when all pool events are still in cooldown")
    }
}
