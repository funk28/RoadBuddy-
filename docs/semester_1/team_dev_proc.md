# RoadBuddy Development Process

## Repo Architecture

```text
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
│   │   ├── __init__.py      # create_app()
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
```

Descriptions:

- android: top level android app with different modules for app, feature, and core;
secrets.properties will be where the Google Maps API key is stored
- backend: Flask app directory structure, with required configuration, api
directory for blueprints, and services directory of logic pertaining to the
mapping services and vlm classifier
- vlm: this will hold the vlm classifier
- shared/openapi.yaml: the API contract between the app and the backend
(OpenAPI 3.1), which also describes the VLM classifier's input and output
schemas
- docs: where this documentation lives
- .github/workflows: CI workflow configurations
- .env.example: Lists every environment variable the project needs

Sources:

- <https://developer.android.com/topic/modularization>
- <https://flask.palletsprojects.com/tutorial>
- <https://packaging.python.org/discussions/src-layout-vs-flat-layout/>
- <https://developers.google.com/maps/documentation/android-sdk/secrets-gradle-plugin>

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
- docs

Rules:

- lowercase only
- keep it short and specific

## Code Development & Review Policy

### Protecting main

- main is a protected branch: no direct pushes, no force pushes, no branch
deletion. Every change, including docs, reaches main through a pull request.
Docs-only PRs don't need an approving review (see Code Review & Approval
Process).
- main should always build and pass CI. If main breaks, fixing it is the
team's top priority, and the person whose merge broke it owns the fix (or the
revert).
- Branch protection rules on GitHub enforce the approval and CI requirements
below, so the policy does not rely on memory.

### Pull Request Frequency & Size

- Open small, focused PRs often rather than one large PR at the end of a
feature. We recommend keeping PRs to about 400 changed lines or fewer
(excluding generated files, lock files, and model weights). This is a
guideline, not a limit: a larger PR is fine when the change can't be split
sensibly, as long as the description says why.
- Each team member should aim to open at least one PR per week during active
development, so work stays visible and merge conflicts stay small.
- Feature branches should be short-lived: merge or rebase onto main within a
few days. A branch older than one week should be rebased on main, split up, or
discussed at the next team meeting.
- A PR touches one area (android, backend, vlm, mapping) when possible.
Cross-area changes (e.g. an API change that touches both android and backend)
must update shared/openapi.yaml in the same PR. One approval is enough;
ideally the reviewer works in one of the affected areas, and the other area's
owners are tagged so they know about the change.
- Draft PRs are encouraged for early feedback; they are not reviewed for
approval until marked "Ready for review."

### Opening a Pull Request

- PR title follows the branch convention: `type(area): short description`
(e.g. `feat(android): add camera capture screen`).
- The PR description uses the repository PR template and includes:
  - What changed and why
  - Link to the related issue/task (e.g. `Closes #12`)
  - How it was tested (unit tests, emulator/device, manual API calls)
  - Screenshots or screen recordings for any UI change
  - Any new environment variables (also added to .env.example)
- The author self-reviews the diff on GitHub before requesting reviewers,
removing debug prints, commented-out code, and stray files.
- No secrets in PRs: API keys belong in secrets.properties or .env, never in
committed code. If a secret is committed, rotate it immediately; deleting the
commit is not enough.

### Code Review & Approval Process

- At least **1 approving review** from a teammate other than the author is
required to merge a code change. This includes changes to shared/openapi.yaml,
CI workflows, and the VLM inference interface; since those affect everyone,
tag the people who depend on them so they see the change.
- **Docs-only PRs** (PRs that only change files under docs/ or Markdown files
such as READMEs) don't need an approving review. The author can merge once
CI passes. Teammates can still comment, and anyone can fix a docs mistake
with a follow-up PR. A change to the API contract is not docs-only: it goes
through shared/openapi.yaml and needs a review.
- Reviewers are requested by the author, preferably someone who works in the
affected area, plus one person outside the area now and then to spread
knowledge across the team.
- Review turnaround: reviewers respond within **24 hours** on weekdays (48 hours
on weekends). If a review is blocking someone, say so in the team chat.
- Reviewers check for:
  - Correctness and edge cases
  - Tests covering the new or changed behavior
  - Readability, naming, and consistency with the existing module structure
  - Adherence to the API contract
  - Security issues (secrets, unvalidated input, permissions)
- Review comment conventions:
  - **blocking:** must be addressed before merge
  - **nit:** minor or stylistic, optional
  - **question:** request for clarification
- Use "Request changes" only for blocking issues. The author resolves each
conversation by fixing the issue or replying with a reason; the reviewer who
opened a thread is the one who marks it resolved.
- New commits pushed after an approval dismiss the stale approval, so the
final code is what gets reviewed.

### CI Checks

CI runs on every push to a PR branch and on every merge to main, using GitHub
Actions workflows in .github/workflows/. Workflows are path-filtered so each
folder only runs its own checks:

- **android/**: `./gradlew build`, unit tests (`./gradlew test`), and Android
Lint / ktlint
- **backend/**: install via pyproject.toml, `pytest`, linting/formatting
(e.g. ruff), and type checking (e.g. mypy)
- **vlm/**: `pytest` on preprocessing and inference code paths (using a small
stub or sample model, not full weights), plus the same linting as backend
- **shared/openapi.yaml**: schema validation of the OpenAPI spec
- **all PRs**: secret scanning, and a check that the branch name matches the
naming convention

Rules:

- All required CI checks must pass before merging. No merging on red, and no
disabling or skipping a check to get a PR through. If a check is flaky, open an
issue and fix it.
- Tests are written alongside the code they cover, in the same PR.
- CI should stay fast (target under ~10 minutes) so it does not discourage
frequent PRs.

### Merging into main

- The PR author merges their own PR once it has the required approval (none
for docs-only PRs) and passing CI; this keeps ownership clear.
- Branches must be up to date with main before merging. Rebase or merge main
into the branch and let CI re-run.
- Use **squash and merge** so each PR becomes one clean commit on main, with
the PR title as the commit message.
- Delete the feature branch after merging.
- Avoid merging large or risky changes right before a demo or deadline. A
**merge freeze** on main starts 24 hours before major milestones, and only
fixes approved by the team go in during it.
- If a merge breaks main, revert first (`git revert` via a new PR) and fix
forward on a new branch afterwards.

### Policy Enforcement & Revisions

- GitHub settings enforcing this policy: branch protection on main, required
status checks, required approvals, dismissing stale approvals, linear history,
and auto-deleting head branches.
- A PR template (`.github/pull_request_template.md`) and a CODEOWNERS file map
each folder to its area owners so reviewers are requested automatically.
- The team reviews this policy at the end of each sprint and changes it by
consensus. Policy changes are made through a PR like any other change.

## Project Board Workflow

All work is tracked as GitHub issues on the team's project board
(<https://github.com/users/funk28/projects/1>), which is linked to this
repository. Each issue has a Status, Start date, Target date, Track, and
Sprint, plus a Sprint milestone and area labels (android, backend, vlm,
mapping, qa, verification, documentation).

### Creating an Issue

- Anyone can open an issue for new work, a bug, or a question that needs a
decision.
- Every issue gets a short title, a **Scope** checklist of what has to be done,
and a **Done when** statement that makes it clear when the issue can be closed.
- Add the area label(s), the Sprint milestone, and the Track, and set an
assignee once someone owns the work.
- Large pieces of work are split into issues small enough to finish within one
sprint.

### Status Columns

Issues move across the board from left to right:

```text
Todo → In Progress → In Review → Done
```

| Status | Meaning | Who moves it, and when |
|---|---|---|
| **Todo** | Planned, but no one has started. New issues start here. | Set when the issue is created or added to a sprint. |
| **In Progress** | Someone is actively working on it on a feature branch. | The assignee moves it when they create the branch and start work, and sets the Start date. |
| **In Review** | A pull request is open and ready for review. | The assignee moves it when the PR is marked "Ready for review". The PR links the issue (e.g. `Closes #12`). |
| **Done** | The PR is merged into main and the "Done when" criteria are met. | Moves automatically when the linked PR merges and closes the issue. |

Rules:

- Each person should have only one or two issues **In Progress** at a time.
- If a review asks for changes, the issue stays **In Review** while the author
addresses them. If the work turns out to need much more effort, move it back
to **In Progress** and convert the PR to a draft.
- If an issue is blocked, leave it in its column, comment on what is blocking
it, and bring it up in the team chat or at the next meeting.
- An issue that won't make its Target date is discussed at the next team
meeting and either moved to a later Sprint or split up.
- Issues are closed only through a merged PR or by team agreement (e.g. a
duplicate or work that is no longer needed), with a comment explaining why.

## Using Coding Agents

Team members may use AI coding agents (e.g. Claude Code, GitHub Copilot) to
help write code, tests, and documentation. Agent work follows exactly the same
development process as human work, and the team member running the agent is
responsible for everything it produces.

### Starting an Agent Session

The first instruction in every agent session points the agent to this
document, so it follows the development process before doing anything else.
For example:

```text
Read docs/semester_1/team_dev_proc.md and follow the development process it
describes. Then work on issue #<number>: <short description>.
```

This tells the agent the repo layout, branch naming, PR rules, CI checks, and
how issues move across the board.

### Best Practices

- **One issue per session.** Give the agent a single issue to work on, and
link it so the agent can read the Scope and "Done when" criteria.
- **Always work on a branch.** The agent creates a branch named by the
`type/area-short-description` convention. It never commits to main, force
pushes, or changes branch protection, CI workflows, or other GitHub settings
unless the issue is specifically about them.
- **Respect the API contract.** Changes that touch the Android ↔ Flask
interface must match shared/openapi.yaml, or update it in the same PR.
- **Keep PRs small.** Ask the agent for focused changes, around the ~400-line
recommendation. Split larger work into several issues and PRs when it can be
split sensibly.
- **Tests come with the code.** Have the agent write or update tests for the
behavior it changes, and run the area's tests and linters locally before
pushing.
- **Review everything it writes.** Read the whole diff before opening a PR, as
you would your own code. Make sure you understand it and can explain it in
review. A teammate's approval is still required for code changes.
- **Never share secrets.** Don't paste API keys, tokens, `.env`, or
secrets.properties contents into an agent. Agents must not commit secrets or
add them to code.
- **Check its facts.** Agents can make up APIs, library versions, or links.
Verify dependencies, sources, and any claims against the official
documentation.
- **Be transparent.** Mention in the PR description when an agent wrote a
significant part of the change, and keep any co-author lines it adds to
commits.
- **Keep instructions in the repo.** If the team finds a rule agents keep
missing, add it to this document (through a PR) instead of repeating it in
each session.
