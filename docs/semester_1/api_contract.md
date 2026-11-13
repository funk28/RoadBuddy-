# RoadBuddy API Contract (Flow 2 walking skeleton)

Status: **draft**, not yet agreed on by the team.

**Shared conventions**
- Base URL: `/v1`. The Android app talks only to Flask; Flask calls the VLM and PostGIS.
- Auth: every request sends `Authorization: Bearer <Google ID token>`.
- Format: times are ISO-8601 UTC, distances are in meters.
- Errors: `{"error": {"code": "...", "message": "..."}}`.
- `severity`: `minor | moderate | severe`. `status`: `live | expired | removed`.

| # | Call | Screen | Real or mocked |
|---|---|---|---|
| 1 | `POST /v1/detections` ★ **end to end** | Navigation | Real |
| 2 | `DELETE /v1/detections/{id}` | Navigation | Real |
| 3 | `classify()` (VLM) | Called by #1 | Stub first |
| 4 | `GET /v1/hazards` | Hazards | Real |
| 5 | `PATCH /v1/hazards/{id}` | Hazards | Real |
| 6 | `GET /v1/me/reports` | Reports | Real |
| 7 | `DELETE /v1/me/reports/{id}` | Reports | Real |
| 8 | `GET` / `PUT /v1/me/settings` | Settings | Real |

The navigation route itself is **mocked** for now: a hardcoded polyline, with no Google Routes API call.

---

### 1. Report a detected pothole ★ (must work end to end)

| Field | Answer |
|---|---|
| What it does | Uploads a flagged frame and its GPS location. The server classifies it with the VLM, merges it with any existing hazard within ~15 m, and stores it in PostGIS. |
| Type | Python backend + database (calls the ML model) |
| Endpoint | `/v1/detections` |
| Method | `POST` (multipart: `image` JPEG + `meta` JSON) |
| Request | `{"client_detection_id": "8f1c…", "captured_at": "2026-11-13T14:48:02Z", "location": {"lat": 40.4237, "lng": -86.9212}, "heading_deg": 92, "local_confidence": 0.81}` |
| Response | `{"detection_id": "d_123", "outcome": "created", "hazard": {"id": "h_45", "severity": "severe", "lat": 40.4237, "lng": -86.9212, "sighting_count": 1}, "undo_expires_at": "2026-11-13T14:48:09Z"}` (`outcome` is `created`, `matched` or `rejected`) |
| If it fails or is slow | The detection is saved on the phone and the screen shows **"1 report waiting to send"**. It retries automatically with the same `client_detection_id`, so no duplicates are created. The driver never has to tap anything. |
| Which screen calls it | Navigation (driving with detection on) |

### 2. Undo a detection

| Field | Answer |
|---|---|
| What it does | Removes a detection within 5 s if the driver says the hazard isn't there. |
| Type | Python backend + database |
| Endpoint | `/v1/detections/{id}` |
| Method | `DELETE` |
| Request | *(no body)* `DELETE /v1/detections/d_123` |
| Response | `{"detection_id": "d_123", "undone": true}` |
| If it fails or is slow | The alert shows "Couldn't undo, remove it from My reports". The report stays and can be deleted later (#7). |
| Which screen calls it | Navigation (the hazard alert's Undo button) |

### 3. Classify a frame (VLM)

| Field | Answer |
|---|---|
| What it does | Confirms whether a frame shows a pothole and rates its severity. |
| Type | ML model (cloud VLM behind a Python interface; a stub returns fixed results for now) |
| Endpoint | `HazardClassifier.classify(image, hint)` (Python function, not HTTP) |
| Method | n/a (function call) |
| Request | `{"image": "<jpeg bytes>", "hint": {"label": "pothole", "confidence": 0.81}}` |
| Response | `{"is_hazard": true, "hazard_type": "pothole", "severity": "severe", "confidence": 0.92, "model_id": "stub-0.1"}` |
| If it fails or is slow | Times out after ~5 s. #1 then returns `503 vlm_unavailable` and the phone queues the detection and retries it, the same way it handles being offline. |
| Which screen calls it | None directly. It runs inside #1, and its severity is shown on Hazards and Reports. |

### 4. List hazards near me

| Field | Answer |
|---|---|
| What it does | Returns live hazards within a radius, for the map pins and the list. |
| Type | Python backend + database (PostGIS `ST_DWithin`) |
| Endpoint | `/v1/hazards?lat=…&lng=…&radius_m=…&severity=…&mine=…` |
| Method | `GET` |
| Request | `GET /v1/hazards?lat=40.4237&lng=-86.9212&radius_m=2000&severity=severe` |
| Response | `{"hazards": [{"id": "h_45", "type": "pothole", "severity": "severe", "lat": 40.4237, "lng": -86.9212, "distance_m": 640, "sighting_count": 3, "reported_by_me": true}]}` |
| If it fails or is slow | The list shows a loading placeholder and the map keeps the last pins it loaded. On failure: a "Couldn't load hazards" banner with Retry. |
| Which screen calls it | Hazards (map pins, list, and the All / Mine / Severe only filters) |

### 5. Modify a hazard

| Field | Answer |
|---|---|
| What it does | Updates a detected pothole: corrects its severity or marks it as not a hazard. |
| Type | Python backend + database |
| Endpoint | `/v1/hazards/{id}` |
| Method | `PATCH` |
| Request | `{"severity": "moderate"}` or `{"status": "removed"}` |
| Response | `{"id": "h_45", "severity": "moderate", "status": "live"}` |
| If it fails or is slow | The screen updates right away, but the change is undone with the toast "Couldn't save change" if the request fails. |
| Which screen calls it | Hazards |

### 6. List my reports

| Field | Answer |
|---|---|
| What it does | Returns the user's own detections with severity and status. |
| Type | Python backend + database |
| Endpoint | `/v1/me/reports?trip_id=…` (leave out `trip_id` for all reports) |
| Method | `GET` |
| Request | `GET /v1/me/reports?trip_id=t_9` |
| Response | `{"reports": [{"id": "d_123", "hazard_id": "h_45", "type": "pothole", "severity": "severe", "location_name": "Oak St near 4th St", "captured_at": "2026-11-13T14:48:02Z", "badge": "submitted", "image_url": "https://…"}]}` (`badge` is `submitted`, `matched`, `expired` or `pending_upload`) |
| If it fails or is slow | A loading placeholder, then "Couldn't load reports" with Retry. Reports still waiting to upload are shown from the phone's own queue. |
| Which screen calls it | Reports (the list and the detail view) |

### 7. Delete a report

| Field | Answer |
|---|---|
| What it does | Deletes the user's report. If it was the only sighting of that hazard, the hazard is removed too. |
| Type | Python backend + database |
| Endpoint | `/v1/me/reports/{id}` |
| Method | `DELETE` |
| Request | *(no body)* `DELETE /v1/me/reports/d_123` |
| Response | `{"id": "d_123", "deleted": true, "hazard_removed": true}` |
| If it fails or is slow | The report stays in the list with the toast "Couldn't remove, try again". |
| Which screen calls it | Reports (the detail view's "Not a hazard? Remove" button) |

### 8. Get / save drive settings

| Field | Answer |
|---|---|
| What it does | Loads and saves the user's drive settings. |
| Type | Python backend + database |
| Endpoint | `/v1/me/settings` |
| Method | `GET` / `PUT` |
| Request | `PUT {"audible_alerts": true, "detection_enabled": true, "suggest_reroutes": true}` |
| Response | `{"audible_alerts": true, "detection_enabled": true, "suggest_reroutes": true, "updated_at": "2026-11-13T15:02:00Z"}` |
| If it fails or is slow | Settings are saved on the phone first, so toggles take effect immediately. The phone syncs them in the background and retries on failure. |
| Which screen calls it | Settings |
