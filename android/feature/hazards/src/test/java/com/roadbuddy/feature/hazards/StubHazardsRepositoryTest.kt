package com.roadbuddy.feature.hazards

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StubHazardsRepositoryTest {
    @Test
    fun filtersMineAndSevereHazards() = runBlocking {
        val repository = StubHazardsRepository()

        assertEquals(
            listOf("h_45"),
            repository.getHazards(HazardFilters(mine = true)).map { it.id },
        )
        assertEquals(
            listOf("h_45", "h_47"),
            repository.getHazards(HazardFilters(severity = HazardSeverity.SEVERE)).map { it.id },
        )
    }

    @Test
    fun updatesSeverityAndRemovesHazards() = runBlocking {
        val repository = StubHazardsRepository()

        repository.updateHazard("h_46", HazardChange.SetSeverity(HazardSeverity.SEVERE))
        assertEquals(
            HazardSeverity.SEVERE,
            repository.getHazards(HazardFilters()).first { it.id == "h_46" }.severity,
        )

        val update = repository.updateHazard("h_46", HazardChange.MarkRemoved)
        assertTrue(update.isRemoved)
        assertFalse(repository.getHazards(HazardFilters()).any { it.id == "h_46" })
    }
}
