# RoadBuddy Development Process

## Repo Architecture

repo/
├── android/                 # Gradle multi-module project
│   ├── app/
│   ├── feature/  (camera, map, results…)
│   ├── core/     (network, data, model, ui)
│   ├── secrets.properties   # gitignored
│   └── local.defaults.properties
├── backend/                 # Flask
│   ├── pyproject.toml
│   ├── src/app/
│   │   ├── \_\_init\_\_.py  # create_app()
│   │   ├── api/             # blueprints
│   │   └── services/        # maps.py, vlm_client.py
│   └── tests/
├── vlm/                     # its own Python package
│   ├── src/vlm/  (preprocess, inference)
│   ├── tests/
│   └── models/              # gitignored / LFS
├── shared/openapi.yaml      # Android↔Flask contract
├── docs/
├── .github/workflows/       # CI per folder
├── .env.example
└── README.md

Descriptions:

- android: top level android app with different modules for app, feature, and core;
secrets.properties will be where the Google Maps API key is stored
- backend: Flask app directory structure, with required configuration, api
directory for blueprints, and services directory of logic pertaining to the
mapping services and vlm classifier
- vlm: this will hold the vlm classifier
- shared/openapi.yaml: the API contract between the app and the backend,
possibly adding an API contract between the VLM and the backend
- docs: where this documentation lives
- .github/workflows: CI workflow configurations
- .env.example: Lists every environment variable the project needs

Sources:

- <https://developer.android.com/topic/modularization>
- <https://flask.palletsprojects.com/tutorial>
- <https://packaging.python.org/discussions/src-layout-vs-flat-layout/>
- <https://developer.google.com/maps/documentation/android-sdk/secrets-gradle-plugin>

## Branching Model

Model:

We will be using a feature branch (or GitHub flow) branching model, where each
member will create a branch off of the main trunk when they want to develop a
feature. They will open up a pull request when they want to merge.

main is the main development branch where CI will run.

Naming Convention:

type/area-short-description

Types:

- feat: new feature
- fix: bug fix
- test: adding or fixing tests
- docs: documentation only

Areas:

- android
- backend
- vlm
- mapping

Rules:

- lowercase only
- keep it short and specific

## Code Development & Review Policy
