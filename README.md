# fassistant-apps

A sideloaded Android app that lists every Fassistant app and installs or updates it in one tap.

## Problem

Each Fassistant app lives in its own GitHub repo and publishes exactly one release, carrying the
APK and an `update.json` manifest. Once an app is on the phone it can update itself, but getting it
there the first time is manual: find the repo, find the release, download the APK, clear the
unknown-sources gate, install. Every new app in the family repeats that, on every phone. There is
nowhere to look to find out what exists.

## What it does

- Lists every Fassistant app, discovered from GitHub by the repo topic `fassistant` — no list to
  maintain by hand.
- For each one, shows the installed version against the latest released version.
- Installs, updates or opens an app from its row, and updates everything at once.
- Verifies each download against the SHA-256 in the release manifest and against the signing
  certificate, before the install prompt appears.

## Out of scope (for now)

- Any app outside the Fassistant family — this is not a general sideloading client.
- Private repositories. Every Fassistant repo is public, so the app needs no GitHub token.
- Background update checks and notifications. The list refreshes when you open it or pull to
  refresh.
- Uninstalling. Android has a screen for that.

## Stack

Proposed: Kotlin, minSdk 24 (Android 7.0), no runtime dependencies, no AndroidX, views built in
code — the same build as `fassistant-android` and `fassistant-click`, so the three repos stay
readable as one family. The catalogue is itself a Fassistant app: it is signed with the same key,
publishes the same release shape, and appears in its own list.

## Status

Milestone one of seven. The build, the bundled root certificate and the download check are done and
produce a signed release; the check still has to be run on a phone.

The phones are never connected to a computer, so a release on GitHub is the only way anything
reaches one. Open the release's `fassistant-apps.apk` in the phone's browser, install it, tap **Run
the check**, then **Send the report**.

Execution plan: https://claude.ai/code/artifact/8a737649-ddc9-4db5-951f-d154b8f37b0c
(also at `docs/fassistant-apps-plan.html`)

## Why the app ships a certificate

GitHub serves release files from a host whose certificate chains up to ISRG Root X1, and Android
only added that root in 7.1.1 — it is absent from 7.0. Without help, a 7.0 phone lists every app
with the right version number and then fails every download, with an error that reads like a server
problem. So the root travels inside the APK as an extra trust anchor, declared in
`app/src/main/res/xml/network_security_config.xml`. It costs about 1.4 KB, and on 7.1.1 and later it
is simply redundant.

The same applies to `fassistant-android` and `fassistant-click`: both download their own updates
from that host, so on Android 7.0 and older their self-update has never been able to work.

