# Backend design decisions

Status: written alongside the first backend PRs (#42, #39, #40, #41), 2026-11-18. When a
decision here changes, update this file in the same PR.

This records **why** the Flask backend works the way it does, so teammates can build
against it without reading all the code. How to build and run it is in
[`backend/README.md`](../../backend/README.md); the request and response shapes are in
[`shared/openapi.yaml`](../../shared/openapi.yaml).

Contents:

1. [Plugging in the VLM classifier (#49)](#1-plugging-in-the-vlm-classifier-49): read this first if you work on `classify()`
2. [Stack and project structure](#2-stack-and-project-structure)
3. [API-wide conventions](#3-api-wide-conventions)
4. [Detections: POST and DELETE /v1/detections (#42)](#4-detections-post-and-delete-v1detections-42)
5. [Hazards: GET and PATCH /v1/hazards (#39)](#5-hazards-get-and-patch-v1hazards-39)
6. [Reports: GET and DELETE /v1/me/reports (#40)](#6-reports-get-and-delete-v1mereports-40)
7. [Settings: GET and PUT /v1/me/settings (#41)](#7-settings-get-and-put-v1mesettings-41)
8. [Contract changes made by these PRs](#8-contract-changes-made-by-these-prs)
9. [Known gaps](#9-known-gaps)

---

## 1. Plugging in the VLM classifier (#49)

**For @nlaury509.** The backend already calls the classifier; you only need to supply the
implementation. Everything lives in
[`backend/src/app/services/vlm_client.py`](../../backend/src/app/services/vlm_client.py).

### The interface

```python
@dataclass(frozen=True)
class ClassifyHint:              # ClassifyHint in openapi.yaml
    label: str                   # always "pothole" for now
    confidence: float            # the phone's local_confidence, or 0.0 if it didn't send one

@dataclass(frozen=True)
class ClassificationResult:      # ClassificationResult in openapi.yaml
    is_hazard: bool
    hazard_type: str             # e.g. "pothole"; at most 32 characters
    severity: str                # must be exactly "minor", "moderate" or "severe"
    confidence: float            # 0..1
    model_id: str                # at most 64 characters, e.g. "stub-0.1"

class HazardClassifier(Protocol):
    def classify(self, image: bytes, hint: ClassifyHint) -> ClassificationResult: ...
```

`image` is the raw JPEG bytes the phone uploaded. The backend has only checked that it
starts with the JPEG magic bytes and is under 10 MB; it hasn't decoded it.

### Adding the real classifier

1. Write a class with that `classify` method (in `vlm_client.py`, or in your own module
   that `vlm_client.py` imports). It doesn't need to subclass anything.
2. Add a branch to `build_classifier(name)`, e.g. `if name == "vlm": return VlmClassifier(...)`.
3. Read any API key or model name from environment variables in that branch, and add
   them to [`.env.example`](../../.env.example) (names only, never values).
4. Deploy with `VLM_CLASSIFIER=vlm`. `stub` stays the default so tests and local
   development never call the paid API.

If `vlm/` becomes its own package (as the dev process's repo layout suggests), the adapter
class can live there and `build_classifier` just imports it. Either works for the backend.

### What the backend does with your result

| Your classifier... | The API answers |
|---|---|
| returns `is_hazard=True` | merges into a hazard within 15 m, or creates one: `outcome: created` / `matched` |
| returns `is_hazard=False` | stores the detection and frame, makes no hazard: `outcome: rejected` |
| raises any exception | `503 vlm_unavailable`; nothing is stored, and the phone retries later |
| takes longer than `VLM_TIMEOUT_S` (5 s) | `503 vlm_unavailable`, the same way |

Things to know:

- **Validate the VLM's output before returning it.** `severity` goes into a column with a
  check constraint; anything other than `minor`/`moderate`/`severe` makes the request fail
  with a 500 instead of a clean 503. If the model's JSON is malformed, raise
  `ClassifierUnavailable` (or any exception) so the phone retries.
- **Set your own HTTP timeout too** (a bit under 5 s). The backend stops *waiting* after
  `VLM_TIMEOUT_S`, but Python can't kill the worker thread, so a hung HTTP call keeps
  running in the background. Up to 8 calls run at once (`ThreadPoolExecutor(max_workers=8)`).
- **Retries don't call you twice.** A retried upload (same `client_detection_id`) is
  answered from the database before the classifier is called, so the VLM is billed once
  per detection.
- **Severity on a matched hazard** becomes the worse of the existing severity and yours
  (see §4).
- **Tests:** `tests/conftest.py` has a `FakeClassifier` whose `result` and `error` tests
  can set. Unit tests for the real adapter belong in its own test file and shouldn't need
  the database.

---

## 2. Stack and project structure

| Decision | Why |
|---|---|
| Flask app factory (`create_app()`) with one `/v1` blueprint | Tests build an app with fakes injected (classifier, token verifier); route modules (`api/*.py`) attach to the shared blueprint so auth runs once for all of them |
| SQLAlchemy 2 + GeoAlchemy2 (via Flask-SQLAlchemy) | Typed models, and PostGIS functions (`ST_DWithin`, `ST_Distance`) in normal queries |
| Alembic migrations via Flask-Migrate, hand-tidied | The schema is versioned for RDS. Autogenerate output is reviewed and rewritten, because the PostGIS image's tiger tables confused it (now filtered in `env.py`) |
| psycopg 3 driver | Current, maintained, with binary wheels |
| Local PostGIS `postgis/postgis:16-3.4` on port 5433 | Matches what RDS offers; 5433 avoids clashing with a local Postgres |
| Tests run against real PostGIS (`roadbuddy_test`) | Geography distance and locking behavior can't be faked meaningfully |
| Frames on local disk (`IMAGE_STORAGE_DIR`) behind a small `ImageStore` class | Enough for the prototype; switching to S3 only changes `storage.py` |

---

## 3. API-wide conventions

- **IDs** are integer primary keys exposed with a prefix: detections and reports
  `d_<n>`, hazards `h_<n>`. A malformed id (`abc`, `h_1` where a `d_` is expected) is a
  404, the same as an id that doesn't exist, so callers can't probe.
- **Auth.** Every `/v1` route requires `Authorization: Bearer <Google ID token>`, verified
  with `google-auth` against `GOOGLE_CLIENT_IDS`. A `users` row is created the first time a
  Google account (`sub`) calls. `DEV_AUTH=true` also accepts `Bearer dev:<name>` for local
  curl testing and must stay off on EC2. Unknown routes return 404 before auth runs.
- **"Not yours" is 404, not 403.** Undoing or deleting another user's detection looks
  exactly like a missing one.
- **Errors** always use `{"error": {"code", "message"}}`. Codes in use:
  `invalid_request` (400), `client_detection_id_conflict` (400), `unauthorized` (401),
  `not_found` (404), `undo_expired` (409), `request_entity_too_large` (413),
  `vlm_unavailable` (503), `internal_error` (500).
- **Timestamps** in responses are ISO-8601 UTC to whole seconds, with a `Z`
  (`2026-11-13T14:48:02Z`). Timestamps in requests must include an offset; a bare local
  time is rejected rather than guessed.
- **Validation is strict about types:** `true` is not a number, `"40.1"` is not a
  latitude, and `NaN` or infinity are rejected.

---

## 4. Detections: POST and DELETE /v1/detections (#42)

### Order of operations in `POST /v1/detections`

1. Validate `meta` and the image (400s), before any expensive work.
2. **Idempotency check:** if `client_detection_id` already exists for this user, return
   that detection's result. The classifier is not called again.
3. Call the classifier with the timeout (503 on failure, with nothing stored).
4. Save the frame, then merge into or create a hazard, then insert the detection, all in
   one transaction.
5. If two retries race and both reach step 4, the `UNIQUE` constraint on
   `client_detection_id` stops the second insert. It rolls back, deletes its saved frame,
   and returns the winner's result.

### Idempotency

- `client_detection_id` is unique **across all users**, as the issue specified. If a
  different user sends an id that is already used, the API returns
  `400 client_detection_id_conflict` rather than revealing the other user's result. With
  phone-generated UUIDs this should never happen.
- A replay returns the hazard's **current** state (for example a higher `sighting_count`
  if others reported it since), not a snapshot from the first response.

### Merging into hazards

- A detection merges into the **nearest live** hazard within `HAZARD_MATCH_RADIUS_M`
  (15 m), found with `ST_DWithin` on `geography` columns, so distances are true meters.
- The hazard **type is not compared**: a pothole report next to a debris hazard merges.
  Only potholes exist for now; revisit when more types are added.
- On a match: `sighting_count` goes up by 1; `last_seen` becomes the later of the two
  times; **severity becomes the worse of the two** (a milder new reading never
  downgrades it, so one optimistic frame can't hide a bad pothole); the hazard's location
  stays where it was first reported.
- **Removed and expired hazards are never matched.** A new report next to one starts a
  fresh hazard.
- **Concurrency:** the find-or-create step takes a transaction-scoped Postgres advisory
  lock, so two reports of the same pothole arriving together can't both create a hazard.
  The lock is global, so merges happen one at a time; that's fine at prototype scale. If it
  ever becomes a bottleneck, lock per geohash cell instead.

### Rejected detections

When the classifier says `is_hazard: false`, the detection is still stored (row and
frame) with `outcome: rejected` for later review or model evaluation. The response has no
`hazard` and no `undo_expires_at`.

### Undo (`DELETE /v1/detections/{id}`)

- The 5 s window is measured from when the **server received** the upload
  (`received_at`), not from `captured_at`. A phone that uploads a queued detection
  minutes later still gets a real undo window.
- After the window: `409 undo_expired`. The phone then shows "Couldn't undo, remove it
  from My reports", and `DELETE /v1/me/reports/{id}` (#40) works at any time.
- Undo is a **soft delete** (`removed_at` is set). Keeping the row means a late retry of
  the same `client_detection_id` can't bring the detection back.
- Undo takes one sighting off the hazard. If that was its last sighting, the hazard's
  status becomes `removed` (the row is kept; nothing is hard-deleted).

### Schema notes (migration `0001`)

- `hazards.location` is `geography(POINT, 4326)` with a GiST index. `lat` and `lng` are also
  stored as plain floats so responses don't need PostGIS calls.
- `detections` stores what the classifier said (`is_hazard`, `severity`,
  `classifier_confidence`, `model_id`) separately from the hazard, so the VLM's per-frame
  output can be compared with the merged hazard later.
- `user_settings` is created here (used by #41) so the later PRs don't need competing
  migrations.

---

## 5. Hazards: GET and PATCH /v1/hazards (#39)

- **Only `live` hazards are listed.** Expired and removed ones never appear, even with
  filters.
- **Sorted nearest first**, with `distance_m` from `ST_Distance` on geography (true
  meters), rounded to 0.1 m.
- **`radius_m` is capped at 50 km** and results at 500, so one request can't scan the
  whole table. The Hazards screen asks for about 2 km.
- **`reported_by_me`** is true when the user has a detection on that hazard that hasn't
  been undone or deleted. `mine=true` filters on the same rule, so undoing your only
  report makes the hazard leave "Mine".
- **Query parameters are strict:** `mine` accepts `true`/`false`/`1`/`0`, `radius_m` must
  be a whole number, and `severity` must be one of the three values. Anything else is a
  400 rather than being silently ignored.
- **Any signed-in user can PATCH any hazard.** The Hazards screen offers "Edit severity" and
  "Not a hazard" on every pin, not just your own. There's no moderation or audit log
  yet; see §9.
- **PATCH accepts any `HazardStatus`** (`live`, `expired`, `removed`), matching the
  contract, and rejects unknown fields and bad values with 400 without changing anything.
  A PATCHed severity sticks until a new sighting reports something worse (§4).
- **Seed data:** `flask --app app seed-hazards` adds six potholes around campus. It skips
  any point that already has a live hazard within the match radius, so it can be re-run.
  Points along Aiden's mocked route can be added once that route (#38) is merged.

---

## 6. Reports: GET and DELETE /v1/me/reports (#40)

_Filled in by the #40 PR._

---

## 7. Settings: GET and PUT /v1/me/settings (#41)

_Filled in by the #41 PR._

---

## 8. Contract changes made by these PRs

The dev process requires 2 approvals for these, since they change `shared/openapi.yaml`.

| PR | Change | Why |
|---|---|---|
| #42 | `DELETE /detections/{id}` can return `409 undo_expired` | The contract had no response for "the undo window has closed" |

---

## 9. Known gaps

Deliberately left for later:

- **Hazard expiry.** Nothing sets a hazard to `expired` yet; that needs a scheduled job
  (for example, live hazards not seen for N days).
- **Image checks.** Only the JPEG magic bytes and the 10 MB size limit are checked; the
  frame isn't decoded or resized.
- **S3.** Frames are on the instance's disk, which an EC2 replacement would lose.
- **CI.** No `.github/workflows/` for `backend/` yet; the checks in `backend/README.md`
  are the ones it should run.
- **Hazard edits aren't moderated.** Any user can change any hazard's severity or remove
  it (#39), with no record of who did.
- **Rate limiting.** None yet.
- **Production server.** Only `flask run` (the development server) is set up; EC2 needs a
  WSGI server such as gunicorn, which hasn't been chosen or added.
