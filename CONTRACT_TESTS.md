# Contract Test Readiness

Hermex is tested against `hermes-webui` tag `v0.51.85`, peeled commit `f1d399b437c1ca7fe4b6d2093aebe334c32f34a3`.

The machine-readable pin is `UPSTREAM_TESTED_SHA`. Any future contract test runner must clone or check out upstream at that exact SHA unless the pin is intentionally updated after manual verification.

## Advance Policy

`UPSTREAM_TESTED_SHA` records the last upstream commit the app was *validated*
against. Without a rule for moving it, the pin drifts ever further behind
`master` and the contract tests validate against an increasingly ancient
upstream. This section is that rule.

**Trigger.** A green live-smoke against the target upstream release:

1. All read-only endpoint groups in this file's "Endpoint Priority" list return
   and decode through the app's tolerant models.
2. The mutating checks run against **one disposable session only** (create a
   throwaway session, exercise branch/truncate/rename/pin/archive/move/delete,
   then delete it). No production or owner session is touched.

Only after both pass is the target release "validated".

**Cadence and owner.** The repo owner runs the smoke and advances the pin
**after each successful smoke** — there is no fixed calendar; the smoke is the
gate. When a smoke is green, move the pin to the latest release it validated.
When a smoke is skipped or held, the pin stays and the reason is recorded in
the relevant issue.

**How to advance.** Replace the SHA in `UPSTREAM_TESTED_SHA` with the peeled
commit of the validated release, then update the human-readable tag references
in this file and `README.md` to match. Commit the pin move together with a
one-line note of which smoke validated it.

## Current Coverage

The Android unit tests verify the app's endpoint matrix: HTTP method intent,
path, and query parameters for health, auth, sessions, destructive session
actions, streaming, uploads, workspaces/files, models/providers/profiles/
reasoning, slash-command endpoints, read-only server panels, skills, memory,
and analytics source endpoints. Focused request tests also assert native POST
calls do not send `Origin` or `Referer`, preserving the upstream CSRF
contract for non-browser clients; multipart upload tests follow the same rule.

Not covered yet: no live calls to a real server, no Docker-backed upstream
harness, and no mutating checks against a real upstream instance.

## Endpoint Priority

Read-only checks, safe for live contract smoke tests:
- `GET /health`
- `GET /api/auth/status`
- `GET /api/sessions`
- `GET /api/session?session_id=...&messages=...`
- `GET /api/session/status?session_id=...`
- `GET /api/projects`
- `GET /api/workspaces`
- `GET /api/workspaces/suggest?prefix=...`
- `GET /api/list?session_id=...&path=...`
- `GET /api/file?session_id=...&path=...`
- `GET /api/file/raw?session_id=...&path=...`
- `GET /api/models`
- `GET /api/providers`
- `GET /api/settings`
- `GET /api/reasoning`
- `GET /api/profiles`
- `GET /api/personalities`
- `GET /api/commands`
- `GET /api/crons`
- `GET /api/crons/status`
- `GET /api/crons/output?job_id=...&limit=...`
- `GET /api/skills`
- `GET /api/skills/content?name=...`
- `GET /api/memory`

State-changing checks, only safe against disposable test data:
- `POST /api/auth/login`
- `POST /api/auth/logout`
- `POST /api/session/new`
- `POST /api/session/rename`
- `POST /api/session/delete`
- `POST /api/session/pin`
- `POST /api/session/archive`
- `POST /api/session/move`
- `POST /api/session/branch`
- `POST /api/session/truncate`
- `POST /api/session/update`
- `POST /api/session/compress`
- `POST /api/session/undo`
- `POST /api/session/retry`
- `POST /api/chat/start`
- `GET /api/chat/cancel?stream_id=...`
- `POST /api/chat/steer`
- `POST /api/default-model`
- `POST /api/reasoning`
- `POST /api/profile/switch`
- `POST /api/personality/set`

Streaming and async checks:
- `GET /api/chat/stream?stream_id=...`
- `GET /api/chat/stream/status?stream_id=...`
- `POST /api/btw`
- `POST /api/background`
- `GET /api/background/status?session_id=...`

Upload checks:
- `POST /api/upload`

## Future Full Contract Target

The full v1 target should:

1. Clone `nesquena/hermes-webui`.
2. Check out the SHA in `UPSTREAM_TESTED_SHA`.
3. Start upstream in Docker with a disposable workspace and password.
4. Run a command-line contract harness against the local upstream base URL.
5. Exercise read-only endpoints first.
6. Create one disposable session for mutating endpoints, then run branch/truncate/rename/pin/archive/move/delete checks only against that disposable session.
7. Assert each JSON response decodes through the app's tolerant models.
8. Verify SSE event decoding from a controlled stream fixture or a short disposable chat turn.
9. Run on pull requests and nightly.

Until that target exists, the existing mock tests and this endpoint matrix are the readiness gate for request shape drift inside the Android client.
