package com.roadbuddy.feature.reports

object StubReportsRepository {
    private const val response = """
        {
          "reports": [
            {
              "id": "d_123",
              "hazard_id": "h_45",
              "type": "pothole",
              "severity": "severe",
              "location_name": "Oak St near 4th St",
              "captured_at": "2026-11-18T14:48:02Z",
              "badge": "submitted",
              "image_url": null,
              "trip_id": "t_9",
              "lane_coverage": "lane-wide",
              "confirmations": 2,
              "sighting_count": 1
            },
            {
              "id": "d_124",
              "hazard_id": "h_46",
              "type": "pothole",
              "severity": "moderate",
              "location_name": "Oak St",
              "captured_at": "2026-11-18T14:44:00Z",
              "badge": "matched",
              "image_url": null,
              "trip_id": "t_9",
              "lane_coverage": null,
              "confirmations": 5,
              "sighting_count": 5
            },
            {
              "id": "d_125",
              "hazard_id": "h_47",
              "type": "pothole",
              "severity": "minor",
              "location_name": "River Rd",
              "captured_at": "2026-11-17T14:00:00Z",
              "badge": "expired",
              "image_url": null,
              "trip_id": "t_9",
              "lane_coverage": null,
              "confirmations": 0,
              "sighting_count": 0
            },
            {
              "id": "d_126",
              "hazard_id": "h_48",
              "type": "pothole",
              "severity": "minor",
              "location_name": "Pine Rd",
              "captured_at": "2026-11-17T13:00:00Z",
              "badge": "expired",
              "image_url": null,
              "trip_id": "t_8",
              "lane_coverage": null,
              "confirmations": 0,
              "sighting_count": 0
            }
          ]
        }
    """

    fun getReports(): List<UserReport> = ReportsResponse.fromJson(response).reports
}
