# RoadBuddy backend (Flask)

The Flask API the Android app talks to. It implements the contract in
[`shared/openapi.yaml`](../shared/openapi.yaml) (readable version:
[`docs/semester_1/api_contract.md`](../docs/semester_1/api_contract.md)), stores hazards in
PostGIS, and calls the VLM hazard classifier.

Why things are built the way they are: [`docs/semester_1/backend_design_decisions.md`](../docs/semester_1/backend_design_decisions.md).

## Prerequisites

| Tool | Version | Used for |
|---|---|---|
| Python | 3.12 or newer | the app |
| [uv](https://docs.astral.sh/uv/) (or plain `pip` + `venv`) | any recent | virtualenv and installs |
| Docker with Compose v2 | any recent | local PostGIS (`postgis/postgis:16-3.4`) |

You don't need a local Postgres install or `psql`; the container has both.

## First-time setup

All commands run from `backend/`.

```sh
cd backend

# 1. Start PostGIS. It listens on localhost:5433 (not 5432, so it won't clash
#    with a Postgres you already have) and creates two databases:
#    `roadbuddy` (dev) and `roadbuddy_test` (wiped by pytest).
docker compose up -d --wait

# 2. Create a virtualenv and install the app plus dev tools (pytest, ruff, mypy).
uv venv
uv pip install -e '.[dev]'
source .venv/bin/activate          # Windows: .venv\Scripts\activate
#    Without uv: python -m venv .venv && source .venv/bin/activate && pip install -e '.[dev]'

# 3. Configure. Defaults work for local development; see "Configuration" below.
cp ../.env.example .env
set -a; source .env; set +a        # load .env into this shell

# 4. Create the tables.
flask --app app db upgrade
```

## Running the server

```sh
set -a; source .env; set +a
flask --app app run --debug        # http://localhost:5000, reloads on code changes
```

Every `/v1` call needs `Authorization: Bearer <token>`. The Android app sends a Google ID
token. For local testing, set `DEV_AUTH=true` in `.env` and send `Bearer dev:<any-name>`;
each name is its own user.

### Try it with curl

```sh
# Report a detection (any JPEG works; the stub classifier doesn't look at it)
curl -H 'Authorization: Bearer dev:alice' \
  -F 'image=@frame.jpg;type=image/jpeg' \
  -F 'meta={"client_detection_id":"c1","captured_at":"2026-11-18T14:00:00Z","location":{"lat":40.4237,"lng":-86.9212}};type=application/json' \
  localhost:5000/v1/detections

# Undo it (within 5 s)
curl -X DELETE -H 'Authorization: Bearer dev:alice' localhost:5000/v1/detections/d_1
```

To look at the database directly:

```sh
docker compose exec db psql -U roadbuddy -c 'select id, severity, status, sighting_count from hazards'
```

## Configuration

Settings come from environment variables; every one is listed in
[`.env.example`](../.env.example). Never commit `.env`.

| Variable | Default | What it does |
|---|---|---|
| `DATABASE_URL` | local docker DB | SQLAlchemy URL; must use the `postgresql+psycopg://` driver |
| `TEST_DATABASE_URL` | local `roadbuddy_test` | Database pytest uses; **wiped** by the tests |
| `GOOGLE_CLIENT_IDS` | empty | Comma-separated OAuth client IDs the app's ID tokens are issued for. Empty means every real token is rejected |
| `DEV_AUTH` | `false` | Accept `Bearer dev:<name>` tokens. Local only, never on EC2 |
| `IMAGE_STORAGE_DIR` | `./var/images` | Where uploaded frames are written (gitignored) |
| `VLM_CLASSIFIER` | `stub` | Which `HazardClassifier` to use (see `services/vlm_client.py`) |
| `VLM_TIMEOUT_S` | `5` | Seconds before a classify call becomes `503 vlm_unavailable` |
| `HAZARD_MATCH_RADIUS_M` | `15` | Detections this close to a live hazard merge into it |

## Database migrations

The schema is managed with Alembic through Flask-Migrate; the files are in `migrations/versions/`.

```sh
flask --app app db upgrade              # apply everything
flask --app app db downgrade <rev>      # roll back to a revision, e.g. 0001
flask --app app db check                # fails if models.py and the migrations disagree
```

After changing `src/app/models.py`:

```sh
flask --app app db migrate -m "short description"
```

Then **read the generated file before committing**: autogenerate doesn't notice every
change (e.g. edits to an existing check constraint), and the result should be tidied to
match the existing files.
`env.py` limits autogenerate to our own tables, so it ignores the tiger geocoder tables that
ship with the PostGIS image. Number new files after the last one (`0002_…`, `0003_…`).

## Tests and checks

These are the checks the dev process expects CI to run for `backend/`. Run them before
pushing.

```sh
docker compose up -d --wait   # tests need the real PostGIS; ST_DWithin can't be faked
pytest                        # migrates roadbuddy_test, then wipes it between tests
ruff check . && ruff format --check .
mypy
```

`ruff format .` fixes formatting. Tests use a `FakeClassifier` and a fake token verifier
from `tests/conftest.py`, so they need no network or Google credentials.

## Project layout

```text
backend/
├── pyproject.toml            # dependencies + pytest/ruff/mypy config
├── docker-compose.yml        # local PostGIS
├── docker/init-test-db.sql   # creates the test database
├── migrations/               # Alembic
├── src/app/
│   ├── __init__.py           # create_app(): wires config, DB, classifier, auth, routes
│   ├── config.py             # environment variables
│   ├── auth.py               # Google ID token check; creates users on first request
│   ├── errors.py             # {"error": {"code", "message"}} responses
│   ├── models.py             # users, hazards, detections, user_settings
│   ├── serialize.py          # d_/h_ ids, timestamps, shared JSON shapes
│   ├── api/                  # the /v1 blueprint, one module per contract area
│   └── services/
│       ├── hazards.py        # merge a detection into a hazard / take a sighting back
│       ├── storage.py        # frame storage on disk
│       └── vlm_client.py     # HazardClassifier interface + stub (#49 plugs in here)
└── tests/
```

## Troubleshooting

- **`connection refused` on port 5433**: the container isn't running. `docker compose up -d --wait`.
- **`relation "hazards" does not exist`**: run `flask --app app db upgrade`.
- **`roadbuddy_test` doesn't exist**: the init script only runs when the volume is first
  created. Reset with `docker compose down -v && docker compose up -d --wait` (this deletes
  local dev data).
- **Every request is 401 with a real token**: `GOOGLE_CLIENT_IDS` is empty or doesn't contain
  the client ID the Android app signs in with.
- **`ModuleNotFoundError: app`**: the package isn't installed in the active venv;
  run `uv pip install -e '.[dev]'` from `backend/`.
