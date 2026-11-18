# RoadBuddy backend (Flask)

Implements the Android ↔ Flask contract in [`shared/openapi.yaml`](../shared/openapi.yaml).

## Run it locally

```sh
cd backend
docker compose up -d              # PostGIS on localhost:5433 (dev + test databases)
uv venv && uv pip install -e '.[dev]'
cp ../.env.example .env           # then edit; see the comments in the file
export $(grep -v '^#' .env | xargs)
flask --app app db upgrade        # apply migrations
flask --app app run
```

With `DEV_AUTH=true`, any `Authorization: Bearer dev:<name>` header is accepted, so
you can try calls without a Google ID token (never enable it on EC2):

```sh
curl -H 'Authorization: Bearer dev:alice' \
  -F 'image=@frame.jpg;type=image/jpeg' \
  -F 'meta={"client_detection_id":"c1","captured_at":"2026-11-18T14:00:00Z","location":{"lat":40.4237,"lng":-86.9212}};type=application/json' \
  localhost:5000/v1/detections
```

## Checks (the same ones CI runs)

```sh
pytest                 # needs the docker compose database running
ruff check . && ruff format --check .
mypy
```

## Layout

- `src/app/api/`: the `/v1` blueprint, one module per contract area.
- `src/app/services/vlm_client.py`: the `HazardClassifier` interface. `VLM_CLASSIFIER=stub`
  uses the fixed stub until the real adapter (#49) is plugged in here.
- `src/app/services/hazards.py`: merging a detection into a hazard within
  `HAZARD_MATCH_RADIUS_M` (15 m), or taking a sighting back out.
- `migrations/`: Alembic (Flask-Migrate). After changing `models.py`, run
  `flask --app app db migrate -m "..."`, review the file, then `flask --app app db upgrade`.
