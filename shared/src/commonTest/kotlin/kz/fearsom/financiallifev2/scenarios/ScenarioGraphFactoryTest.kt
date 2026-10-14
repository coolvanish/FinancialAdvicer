package kz.fearsom.financiallifev2.scenarios

import kz.fearsom.financiallifev2.data.SeedData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScenarioGraphFactoryTest {

    @Test
    fun `factory returns empty graph for each supported era`() {
        assertIs<Kz90sScenarioGraph>(ScenarioGraphFactory.forCharacter("any", "kz_90s"))
        assertIs<Kz2005ScenarioGraph>(ScenarioGraphFactory.forCharacter("any", "kz_2005"))
        assertIs<Kz2015ScenarioGraph>(ScenarioGraphFactory.forCharacter("any", "kz_2015"))
        assertIs<Kz2024ScenarioGraph>(ScenarioGraphFactory.forCharacter("any", "kz_2024"))
        assertIs<Kz2024ScenarioGraph>(ScenarioGraphFactory.forCharacter("any", "modern_kz_2024"))
    }

    @Test
    fun `seed data exposes only compatible predefined characters per era`() {
        val characterIds = SeedData.predefinedCharacters.map { it.id }.toSet()
        SeedData.eras.forEach { era ->
            era.availableCharacterIds.forEach { characterId ->
                assertTrue(characterId in characterIds, "Missing predefined character $characterId for era ${era.id}")
                val character = SeedData.predefinedCharacters.first { it.id == characterId }
                assertTrue(era.id in character.compatibleEraIds, "Character $characterId is exposed in ${era.id} but is not compatible")
            }
        }
    }

    @Test
    fun `each era exposes the current predefined characters`() {
        val expected = mapOf(
            "kz_90s" to listOf("aidar_90s", "daniyar_90s", "aigul_90s"),
            "kz_2005" to listOf("aidar", "daniyar", "serik"),
            "kz_2015" to listOf("dana", "marat_2015"),
            "kz_2024" to listOf("asan", "aidana_2024", "timur_2024")
        )
        SeedData.eras.forEach { era ->
            assertEquals(expected.getValue(era.id), era.availableCharacterIds)
        }
    }
}
