package kz.fearsom.financiallifev2.model

import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyFormatTest {

    @Test
    fun `kzt values use tenge symbol once`() {
        assertEquals("80k ₸", 80_000L.moneyFormat())
        assertEquals("2M ₸", 2_000_000L.moneyFormat())
    }

    @Test
    fun `negative kzt values are returned as raw numbers without compact suffix`() {
        // netMonthlyFlow can be negative; moneyFormat must not produce garbled output.
        // Compaction is intentionally skipped for negatives (< 0 fails >= 1_000 check).
        assertEquals("-50000 ₸", (-50_000L).moneyFormat())
        assertEquals("-1500000 ₸", (-1_500_000L).moneyFormat())
    }

    @Test
    fun `usd compact formatting at exact boundary values`() {
        assertEquals("1k $", 1_000L.moneyFormat(CurrencyCode.USD))
        assertEquals("1M $", 1_000_000L.moneyFormat(CurrencyCode.USD))
        assertEquals("999 $", 999L.moneyFormat(CurrencyCode.USD))
    }
}
