package com.roadbuddy.feature.hazards

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StubHazardsRepository : HazardsRepository {
    private val lock = Any()
    private var hazards = parseHazardsResponse(
        """
        {
          "hazards": [
            {
              "id": "h_45",
              "type": "pothole",
              "severity": "severe",
              "lat": 40.4228,
              "lng": -86.9198,
              "distance_m": 640,
              "sighting_count": 3,
              "reported_by_me": true,
              "location_name": "Oak St & 4th St"
            },
            {
              "id": "h_46",
              "type": "pothole",
              "severity": "moderate",
              "lat": 40.42315,
              "lng": -86.92224,
              "distance_m": 1770,
              "sighting_count": 5,
              "reported_by_me": false,
              "location_name": "Oak St"
            },
            {
              "id": "h_47",
              "type": "pothole",
              "severity": "severe",
              "lat": 40.42195,
              "lng": -86.921,
              "distance_m": 1950,
              "sighting_count": 1,
              "reported_by_me": false,
              "location_name": "Pine Ave"
            }
          ]
        }
        """.trimIndent(),
    ).toMutableList()

    override suspend fun getHazards(filters: HazardFilters): List<Hazard> =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                hazards.filter { hazard ->
                    (!filters.mine || hazard.reportedByMe) &&
                        (filters.severity == null || hazard.severity == filters.severity)
                }
            }
        }

    override suspend fun updateHazard(id: String, change: HazardChange): HazardUpdate =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val index = hazards.indexOfFirst { it.id == id }
                require(index >= 0) { "Unknown hazard: $id" }
                val hazard = hazards[index]
                when (change) {
                    is HazardChange.SetSeverity -> {
                        hazards[index] = hazard.copy(severity = change.severity)
                    }
                    HazardChange.MarkRemoved -> hazards.removeAt(index)
                }
                HazardUpdate(
                    id = id,
                    severity = when (change) {
                        is HazardChange.SetSeverity -> change.severity
                        HazardChange.MarkRemoved -> hazard.severity
                    },
                    isRemoved = change == HazardChange.MarkRemoved,
                )
            }
        }
}
