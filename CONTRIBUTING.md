# Contributing to Hermex

Thanks for your interest in contributing! This document covers local setup,
running tests, and the PR workflow. Please also read the
[Code of Conduct](CODE_OF_CONDUCT.md).

## Local setup

- **JDK 17** and **Android SDK Platform 36** (the app targets API 36 and
  supports API 26+).
- Clone the repo and open the `android/` project in Android Studio, or build
  from the command line with `./gradlew` — see
  [`android/README.md`](android/README.md) for the exact commands.
- To actually use the app you need your own
  [hermes-webui](https://github.com/nesquena/hermes-webui) server — the app is
  a client only. See the [README](README.md) for reachable-server options
  (Cloudflare Tunnel, reverse proxy, or Tailscale).

## Running tests

Unit tests, lint, and the debug build are the repo's green bar — they must
pass before any PR:

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Instrumented tests need a device or emulator:

```bash
./gradlew connectedDebugAndroidTest
```

The same checks run in CI on every push and pull request.

## What PRs we welcome (and what we don't)

Bug fixes, test coverage, and focused improvements are always welcome. For
anything larger than a small fix, **open an issue first and wait for a
maintainer nod before writing code** — it protects your time as much as the
review queue. Drive-by rewrites, reformat-the-world diffs, and unannounced
architecture overhauls will be closed without detailed review.

Keep each PR to **one logical change** with a reviewable diff. If a change is
independently useful, it deserves its own PR.

## App bug or server bug?

Hermex is a thin client over [hermes-webui](https://github.com/nesquena/hermes-webui),
so a fair share of apparent app bugs are really server bugs. Before filing a
bug here, reproduce it in the hermes-webui **web UI** against the same server:

- **Breaks in the web UI too** → it's a server bug. File it
  [upstream](https://github.com/nesquena/hermes-webui/issues); if the app
  should still handle it more gracefully, open an issue here that links the
  upstream ticket.
- **Only breaks in the app** → file it here with the bug-report form.

## PR workflow

1. **Start from an issue.** Every change should trace to a GitHub issue —
   comment on it so work isn't duplicated, or open one first (bug/feature
   templates are provided).
2. **Branch** from `master` as `issue/<number>-<short-slug>` (e.g.
   `issue/42-fix-session-search`).
3. **Make the change**, keeping these repo hard rules:
   - **Tolerant decoding:** every model decodes with optionals for fields the
     server might add or rename — never crash on unknown fields.
   - **Never invent API endpoints or JSON shapes** — verify against the pinned
     upstream `hermes-webui` source or your own running server.
   - **No new third-party dependencies** without approval.
4. **Run the tests and lint** (commands above) and make sure they pass.
5. **Open a PR** against `master` using the PR template — link the issue with
   `Fixes #<number>`, describe what changed and how you tested it. CI must be
   green; the maintainer reviews and merges.
6. **Disclose AI usage** in one line of the PR description: the tool/model
   used (e.g. "built with Claude Code"), or "human-authored". This repo is
   itself built with coding agents, so it's normal context for review — not a
   gate.

`master` is the protected release-candidate branch. Signed releases are
published with the `Android Named Release` workflow (maintainer-only); see
[`android/RELEASE.md`](android/RELEASE.md).

## Questions

Ask in [GitHub Discussions](https://github.com/iaeluk/hermex/discussions)
if something here is unclear or wrong — docs fixes are welcome contributions
too.
