<!-- Thanks for contributing! Please read CONTRIBUTING.md before opening a PR. -->

## Linked issue

<!-- Every PR should close an issue, e.g. "Fixes #123". If there is no issue yet, open one first. -->

Fixes #

## What changed

<!-- A short, plain-English summary of the change and why it's the right fix. -->

## How it was tested

<!-- e.g. ./gradlew testDebugUnitTest lintDebug (command + result), emulator/device steps, screenshots for UI changes. -->

## Checklist

- [ ] Unit tests and lint pass locally (`cd android && ./gradlew testDebugUnitTest lintDebug`)
- [ ] New/changed models decode tolerantly (optionals for fields the server might add or rename)
- [ ] No new third-party dependencies without approval
- [ ] No invented API endpoints or JSON shapes (verified against upstream source or a running server)
