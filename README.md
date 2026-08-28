# Pomodorough for Android

<p align="center">
  Native Jetpack Compose client for the local-first Pomodorough timer.
</p>

<p align="center">
  <a href="https://pomodorough.egigoka.me">Web app</a> |
  <a href="https://github.com/Pomodoro-Everywhere/pomodorough-server">Server</a> |
  <a href="https://pomodorough.egigoka.me/openapi.yaml">API specification</a>
</p>

Pomodorough for Android keeps focus timers, breaks, tasks, duration preferences,
and history available without an account or network connection. Optional Google
sign-in adds encrypted, local-first synchronization with the rest of the
Pomodorough client family.

## Highlights

- Native Material 3 interface built with Jetpack Compose
- Focus, short-break, and long-break timers with optional automatic breaks
- Durable Room queues for timer commands, tasks, and duration changes
- Per-focus task assignment with deterministic identities and daily totals
- Optimistic local replay over canonical server state
- Google sign-in through Android Credential Manager
- Keystore-encrypted access and rotating refresh tokens
- OkHttp Server-Sent Event stream for low-latency revision updates
- AlarmManager completion alarms and native notifications
- Room schema export and tested migrations
- Offline-first operation without an account

## Requirements

- Android Studio with JDK 21
- Android SDK Platform 36
- Android SDK Build Tools 36.0.0
- Android 8.0 (API 26) or newer on the target device

The application compiles against and targets API 36. Android 15 and Android 16
are included in the required release-device test matrix.

## Getting started

Build a debug APK:

```sh
./gradlew :app:assembleDebug
```

Install it on a connected emulator or device:

```sh
./gradlew :app:installDebug
```

Generated APKs are written below `app/build/outputs/apk/`.

## Configuration

Production values are configured by default. Override them with Gradle
properties when targeting another deployment:

```sh
./gradlew :app:assembleDebug \
  -PPOMODOROUGH_API_BASE_URL=https://example.com/api/v1 \
  -PPOMODOROUGH_GOOGLE_SERVER_CLIENT_ID=example.apps.googleusercontent.com
```

| Property | Purpose |
| --- | --- |
| `POMODOROUGH_API_BASE_URL` | Base URL for authenticated API requests and the revision stream |
| `POMODOROUGH_GOOGLE_SERVER_CLIENT_ID` | OAuth audience requested through Credential Manager |

The API base URL should include the `/api/v1` path and omit a trailing slash.

## Architecture

| Package | Responsibility |
| --- | --- |
| `ui/` | Compose screens, theme, lifecycle collection, and view-model orchestration |
| `data/local/` | Room entities, migrations, canonical snapshots, settings, and pending queues |
| `data/api/` | JSON API and OkHttp revision stream |
| `data/auth/` | Nonce-bound Google exchange, serialized refresh, and encrypted token storage |
| `domain/TimerReducer.kt` | Deterministic timer command replay |
| `domain/TaskReducer.kt` | Deterministic task projection and identity normalization |
| `data/TimerRepository.kt` | Durable actions, sync serialization, retries, reconciliation, and alarms |

## Synchronization model

Every timer, task, or duration operation is committed to Room before the UI
reflects it. Synchronization submits immutable pending operations and the last
known server revision. Exact acknowledgements remove submitted entries; any
newer local work is replayed over the canonical response.

After authentication, the client reads the account bootstrap before sending any
queued operation. First-account sign-in preserves unowned local history and
automatically keeps whichever side has history; when both sides do, the user can
keep local, keep remote, or merge. The chosen CAS request is stored in Room so a
network failure or process restart retries the same request ID and payload.

Hybrid logical clocks preserve deterministic ordering across offline devices.
The SSE stream only announces revisions, while the HTTP sync endpoint remains
authoritative. Refresh calls are globally serialized because reusing a rotated
refresh token invalidates its session family.

## Google sign-in

Credential Manager requests an ID token for
`POMODOROUGH_GOOGLE_SERVER_CLIENT_ID`. Google Cloud must also contain an Android
OAuth client for package `me.egigoka.pomodorough` and each signing certificate
used by distributed builds.

Inspect local signing fingerprints with:

```sh
./gradlew signingReport
```

The server's `GOOGLE_NATIVE_CLIENT_IDS` must accept every possible token `aud`
and `azp` value. The production web client ID is:

```text
614768274539-5jrk37jie6415babe51ae4qiupif0m7v.apps.googleusercontent.com
```

## Testing

Run JVM tests, lint, and both build variants:

```sh
./gradlew \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug \
  :app:assembleRelease
```

Run Room migration, persistence, Compose, and repository integration tests on
an emulator or connected device:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Before tagging a release, boot exactly one API 36 emulator or connect one API
36 device, then run the complete local release gate:

```sh
bash .github/scripts/run-local-release-gate.sh
```

The local gate runs unit tests, lint, debug and release builds, then all
instrumented tests independently at 1.0x and 2.0x font scale. Hosted CI also
runs `connectedDebugAndroidTest` on an API 36 Google APIs emulator. Run the local
font-scale matrix before tagging so release verification covers both dimensions.

## Release builds

Release builds enable code shrinking and resource optimization but are unsigned
by default. Production-keystore signing is intentionally excluded from the current
tag workflow. It performs two clean release builds and requires byte-identical
APK/AAB payloads, publishes clearly named `release-unsigned` artifacts, checks
that no production signature was introduced, and generates checksums, SBOMs,
and provenance attestations. Before comparing the bundles, CI normalizes only
R8's diagnostic `buildTimeNs` metadata field; executable and resource payloads
are compared unchanged.

The API 36 release-smoke job copies the exact unsigned APK, applies a one-use
runner-temporary CI test identity, clean-installs it on a fresh emulator, and
launches `MainActivity`. That ephemeral signed copy and key are never published.
Published artifacts cannot be installed or sent to Play until an authorized release
applies the appropriate production/upload signature.

For a future credentialed local build, set all four values below through
environment variables or private Gradle properties in
`~/.gradle/gradle.properties`:

```text
POMODOROUGH_RELEASE_STORE_FILE=/absolute/path/to/release.jks
POMODOROUGH_RELEASE_STORE_PASSWORD=...
POMODOROUGH_RELEASE_KEY_ALIAS=...
POMODOROUGH_RELEASE_KEY_PASSWORD=...
```

Never store signing credentials or keystores in this repository. A partial
configuration fails during Gradle setup rather than silently choosing an
unexpected identity. Before any future signed distribution, verify both
signatures explicitly:

```sh
./gradlew :app:bundleRelease :app:assembleRelease
jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

Keep Room schema JSON files under `app/schemas/` with every release so migration
tests can validate upgrade paths.

## Shared experience contract

Navigation, timer-state language, account safety, completion guarantees,
accessibility, and localization semantics are defined in the
[cross-client experience contract](https://github.com/Pomodoro-Everywhere/pomodorough-server/blob/master/docs/client-experience-contract.md).

## Pomodorough projects

- [Server (Web/PWA and sync)](https://github.com/Pomodoro-Everywhere/pomodorough-server)
- [Apple](https://github.com/Pomodoro-Everywhere/pomodorough-apple)
- [Android (this project)](https://github.com/Pomodoro-Everywhere/pomodorough-android)
- [Desktop](https://github.com/Pomodoro-Everywhere/pomodorough-desktop)

## License

Pomodorough for Android is licensed under the GNU General Public License v3.0
or later. See [LICENSE](LICENSE). Iroh and JNA native dependency notices ship
with the app and are recorded in [IROH_THIRD_PARTY_LICENSES.md](IROH_THIRD_PARTY_LICENSES.md).
